package com.duckreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

/**
 * The folder shelf without a real document provider: a stand-in tree holds the files, and the
 * unpacking is replaced by one that writes a chapter and reports what it found. Everything else —
 * the scan, the index, the cache, removable books and a cleared cache — is the real thing.
 */
class FolderLibraryTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var index: File
    private lateinit var cache: File
    private lateinit var access: FakeAccess
    private lateinit var library: FolderLibrary
    private var extractions = 0
    private var lastUnpacked = ""

    private val unpack: (InputStream, File) -> ExtractedBook = { input, folder ->
        extractions++
        val text = input.readBytes().toString(Charsets.UTF_8)
        lastUnpacked = text
        folder.mkdirs()
        File(folder, "chapter.xhtml").writeText("<html><body><p>$text</p></body></html>")
        ExtractedBook(text, "An Author", listOf("chapter.xhtml"), null, "print-$text")
    }

    private fun setup() {
        index = File(temporary.root, "folders.json")
        cache = File(temporary.root, "cache")
        access = FakeAccess()
        library = FolderLibrary(index, cache, access)
    }

    private fun fold(name: String, label: String = "Books"): String {
        val tree = "tree:$name"
        access.trees[tree] = "doc:$name-root"
        access.labels[tree] = label
        return tree
    }

    private fun file(tree: String, parent: String, name: String, body: String, modified: Long = 1L): FolderEntry {
        val uri = "$tree/$parent/$name"
        access.files[uri] = body.toByteArray()
        val entry = FolderEntry(uri, name, false, body.length.toLong(), modified)
        access.folders.getOrPut(tree to parent) { mutableListOf() }.add(entry)
        return entry
    }

    private fun directory(tree: String, parent: String, name: String): String {
        val uri = "$tree/$parent/$name"
        access.folders.getOrPut(tree to parent) { mutableListOf() }
            .add(FolderEntry(uri, name, true))
        access.folders.getOrPut(tree to uri) { mutableListOf() }
        return uri
    }

    @Test fun aScanFindsBooksInTheFolderAndItsSubfoldersOnly() {
        setup()
        val tree = fold("books")
        file(tree, "doc:books-root", "metro.epub", "Metro")
        file(tree, "doc:books-root", "cover.jpg", "not a book")
        file(tree, "doc:books-root", "notes.txt", "not a book")
        val nested = directory(tree, "doc:books-root", "Series")
        file(tree, nested, "piranesi.fb2", "Piranesi")
        library.add(tree)

        val books = library.scan(unpack)

        assertEquals(setOf("Metro", "Piranesi"), books.map { it.title }.toSet())
        assertEquals(2, extractions)
        assertEquals(2, library.books().size)
        val metro = books.first { it.title == "Metro" }
        assertEquals(BookSource.Folder(tree, (metro.source as BookSource.Folder).document, "Books"), metro.source)
        assertTrue(File(cache, metro.id).isDirectory)
    }

    @Test fun anUnchangedBookIsNotUnpackedAgain() {
        setup()
        val tree = fold("books")
        file(tree, "doc:books-root", "metro.epub", "Metro")
        library.add(tree)
        library.scan(unpack)
        assertEquals(1, extractions)

        library.scan(unpack)

        assertEquals(1, extractions)
        assertEquals("Metro", library.books().single().title)
    }

    @Test fun aChangedBookIsReadAgain() {
        setup()
        val tree = fold("books")
        val entry = file(tree, "doc:books-root", "metro.epub", "First")
        library.add(tree)
        library.scan(unpack)
        assertEquals("First", library.books().single().title)

        access.files[entry.uri] = "Second edition".toByteArray()
        access.folders[tree to "doc:books-root"] = mutableListOf(
            FolderEntry(entry.uri, "metro.epub", false, 14L, 2L))
        val books = library.scan(unpack)

        assertEquals(2, extractions)
        assertEquals("Second edition", books.single().title)
    }

    @Test fun aBookTakenOffTheShelfCanBeBroughtBack() {
        setup()
        val tree = fold("books")
        file(tree, "doc:books-root", "metro.epub", "Metro")
        library.add(tree)
        val book = library.scan(unpack).single()

        library.hide(book.id)
        assertTrue(library.books().isEmpty())
        assertEquals(1, library.hiddenCount())
        assertEquals("The file itself must not be touched.", "Metro", access.files.values.single().toString(Charsets.UTF_8))
        // A later scan must not quietly put it back.
        assertTrue(library.scan(unpack).isEmpty())

        library.restoreHidden()
        assertEquals(book.id, library.books().single().id)
        assertEquals(0, library.hiddenCount())
    }

    @Test fun removingAFolderTakesItsBooksAndTheirCachedCopiesWithIt() {
        setup()
        val first = fold("first")
        val second = fold("second", "Other")
        file(first, "doc:first-root", "metro.epub", "Metro")
        file(second, "doc:second-root", "piranesi.epub", "Piranesi")
        library.add(first)
        library.add(second)
        val books = library.scan(unpack)
        val metro = books.first { it.title == "Metro" }
        assertTrue(File(cache, metro.id).isDirectory)

        library.remove(first)

        assertEquals(listOf("Other"), library.folders().map { it.label })
        assertEquals(listOf("Piranesi"), library.books().map { it.title })
        assertFalse(File(cache, metro.id).exists())
        assertTrue(library.scan(unpack).any { it.title == "Piranesi" })
    }

    @Test fun aFolderThatCannotBeReadKeepsWhatWasAlreadyKnown() {
        setup()
        val tree = fold("books")
        file(tree, "doc:books-root", "metro.epub", "Metro")
        library.add(tree)
        library.scan(unpack)

        access.unreadable = true
        val books = library.scan(unpack)

        assertEquals("Metro", books.single().title)
        assertEquals(1, library.books().size)
    }

    @Test fun aClearedCacheIsUnpackedAgainWhenTheBookIsOpened() {
        setup()
        val tree = fold("books")
        file(tree, "doc:books-root", "metro.epub", "Metro")
        library.add(tree)
        val book = library.scan(unpack).single()
        val cached = File(cache, book.id)
        cached.deleteRecursively()
        assertEquals(1, extractions)

        assertTrue(library.materialize(book, unpack))

        assertEquals(2, extractions)
        assertTrue(File(cached, "chapter.xhtml").isFile)
    }

    @Test fun theFolderShelfIsRememberedWithoutScanningAgain() {
        setup()
        val tree = fold("books")
        file(tree, "doc:books-root", "metro.epub", "Metro")
        library.add(tree)
        library.scan(unpack)

        val reopened = FolderLibrary(index, cache, access)

        assertEquals(listOf("Books"), reopened.folders().map { it.label })
        assertEquals("Metro", reopened.books().single().title)
    }

    @Test fun aDamagedIndexIsTreatedAsAnEmptyShelfRatherThanStoppingTheApp() {
        setup()
        index.writeText("not an index")
        assertTrue(library.folders().isEmpty())
        assertTrue(library.books().isEmpty())
        // The folder can be added again, and the scan rebuilds the index.
        val tree = fold("books")
        file(tree, "doc:books-root", "metro.epub", "Metro")
        library.add(tree)
        assertEquals("Metro", library.scan(unpack).single().title)
    }

    @Test fun aMissingFolderRootIsNotABookBeingRemoved() {
        setup()
        val tree = fold("books")
        file(tree, "doc:books-root", "metro.epub", "Metro")
        library.add(tree)
        library.scan(unpack)
        access.missingRoots = true

        assertEquals("Metro", library.scan(unpack).single().title)
    }

    @Test fun addingAFolderTwiceKeepsOneRowAndOneGrant() {
        setup()
        val tree = fold("books")
        library.add(tree)
        library.add(tree)

        assertEquals(1, library.folders().size)
    }

    @Test fun aBookThatCannotBeUnpackedIsLeftOffTheShelf() {
        setup()
        val tree = fold("books")
        file(tree, "doc:books-root", "metro.epub", "Metro")
        library.add(tree)

        val books = library.scan { _, _ -> throw IllegalStateException("Not a book.") }

        assertTrue(books.isEmpty())
        assertNull(library.books().singleOrNull())
        assertNotNull(library.folders().singleOrNull())
    }

    /** A document tree small enough to keep in memory, with the one switch a failing folder needs. */
    private class FakeAccess : FolderAccess {
        val trees = mutableMapOf<String, String>()
        val labels = mutableMapOf<String, String>()
        val folders = mutableMapOf<Pair<String, String>, MutableList<FolderEntry>>()
        val files = mutableMapOf<String, ByteArray>()
        var unreadable = false
        var missingRoots = false

        override fun persist(tree: String) = Unit
        override fun release(tree: String) = Unit
        override fun label(tree: String) = labels[tree]
        override fun root(tree: String): String? =
            if (missingRoots) null else trees[tree]

        override fun children(tree: String, folder: String): List<FolderEntry>? =
            if (unreadable) null else folders[tree to folder].orEmpty()

        override fun open(document: String): InputStream =
            files[document]?.inputStream() ?: throw IllegalStateException("The file has gone.")
    }
}
