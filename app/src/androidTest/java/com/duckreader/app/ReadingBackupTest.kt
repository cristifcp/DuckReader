package com.duckreader.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A folder-only reader's backup carries where the reader was, their bookmarks, highlights, notes and
 * settings — not the books, which stay in their own folders. An older backup that still carries books
 * is restored as it always was, so nothing already saved is lost.
 */
@RunWith(AndroidJUnit4::class)
class ReadingBackupTest {
    private lateinit var mine: Phone
    private lateinit var theirs: Phone
    private lateinit var files: SharedBooks

    @Before fun setup() {
        files = SharedBooks()
        mine = Phone("backup-from")
        theirs = Phone("backup-to")
    }

    @After fun cleanup() {
        mine.cleanUp()
        theirs.cleanUp()
    }

    @Test fun aNewBackupCarriesTheReadingButNeverABookFile() {
        val book = mine.folderBook(files)
        val passage = passageIn(0)
        mine.store.savePosition(book, 1, .45f, TextAnchor(1, 13, "particular"))
        mine.store.addBookmark(book, 1, .45f, "One More Page")
        mine.store.highlights.add(book, passage, HighlightColor.MINT, "The quiet I was looking for.")
        mine.store.saveAppearance(26, 2, spacing = 2, inset = 0, font = 1)
        mine.store.saveNightDim(40)

        val saved = mine.save()

        val entries = entriesOf(saved)
        assertFalse("a new backup has no shelf index", BackupFormat.LIBRARY in entries)
        assertFalse("a new backup carries no book files",
            entries.keys.any { it.startsWith(BackupFormat.BOOKS) })
        assertTrue("but it does carry where the reader was", BackupFormat.READING in entries)
        assertTrue("and the marks made around the book", entries.keys.any { it.startsWith(BackupFormat.HIGHLIGHTS) })
        assertEquals(0, JSONObject(entries.getValue(BackupFormat.MANIFEST)).getInt("books"))
    }

    @Test fun aBackupCarriesTheReadingLifeToTheSameFolderBookOnAnotherPhone() {
        val book = mine.folderBook(files)
        val back = theirs.folderBook(files)
        assertEquals("The same file in a folder keeps the same name.", book.id, back.id)
        val passage = passageIn(0)
        mine.store.savePosition(book, 1, .45f, TextAnchor(1, 13, "particular"))
        mine.store.addBookmark(book, 1, .45f, "One More Page")
        mine.store.highlights.add(book, passage, HighlightColor.MINT, "The quiet I was looking for.")
        mine.store.saveAppearance(26, 2, spacing = 2, inset = 0, font = 1)
        mine.store.saveSwipeToTurn(false)
        mine.store.saveKeepAwake(true)
        mine.store.saveDarkTheme(false)
        mine.store.saveNightDim(40)

        val tally = theirs.backup.restore(ByteArrayInputStream(mine.save()))

        assertEquals(BackupTally(places = 1, bookmarks = 1, highlights = 1, notes = 1), tally)
        val restored = theirs.store.load().single()
        assertEquals(1, theirs.store.chapter(restored))
        assertEquals(.45f, theirs.store.position(restored), .001f)
        assertEquals(TextAnchor(1, 13, "particular"), theirs.store.anchor(restored))
        assertEquals(1, theirs.store.bookmarks(restored).size)
        val mark = theirs.store.highlights.load(restored).single()
        assertEquals(passage, mark.passage)
        assertEquals(HighlightColor.MINT, mark.color)
        assertEquals("The quiet I was looking for.", mark.note)
        assertEquals(26, theirs.store.fontSize())
        assertEquals(2, theirs.store.paper())
        assertEquals("A backup carries how the page was set", 2, theirs.store.lineSpacing())
        assertEquals(0, theirs.store.pageInset())
        assertEquals(1, theirs.store.pageFont())
        assertEquals(40, theirs.store.nightDim())
        assertFalse(theirs.store.swipeToTurn())
        assertTrue(theirs.store.keepAwake())
        assertFalse("A backup carries the theme the reader chose", theirs.store.darkTheme())
    }

    @Test fun restoringAnOlderBackupNeverMovesAReaderBackwards() {
        val book = mine.folderBook(files)
        mine.store.savePosition(book, 0, .2f)
        mine.store.highlights.add(book, passageIn(0), HighlightColor.YELLOW, "An early thought.")
        val older = mine.save()

        // The reader carries on: further into the book, with a newer thought on the same passage.
        mine.store.savePosition(book, 1, .8f)
        val mark = mine.store.highlights.load(book).single()
        mine.store.highlights.update(book, mark.id, note = "A better thought.")

        val tally = mine.backup.restore(ByteArrayInputStream(older))

        assertEquals(1, mine.store.chapter(book))
        assertEquals(.8f, mine.store.position(book), .001f)
        assertEquals("A better thought.", mine.store.highlights.load(book).single().note)
        assertTrue("nothing is taken back", tally.nothing)
    }

    @Test fun restoringTheSameBackupTwiceBringsNothingNewTheSecondTime() {
        val book = mine.folderBook(files)
        mine.store.savePosition(book, 1, .4f, TextAnchor(1, 5, "kind"))
        mine.store.addBookmark(book, 1, .4f, "One More Page")
        mine.store.highlights.add(book, passageIn(1), HighlightColor.ROSE, "A note.")
        val saved = mine.save()

        val first = mine.backup.restore(ByteArrayInputStream(saved))
        val again = mine.backup.restore(ByteArrayInputStream(saved))

        assertTrue("nothing new the first time either", first.nothing)
        assertTrue("nothing new the second time", again.nothing)
        val back = mine.store.load().single()
        assertEquals(1, mine.store.highlights.load(back).size)
        assertEquals(1, mine.store.bookmarks(back).size)
    }

    @Test fun anOlderBackupStillBringsItsBooksBack() {
        val book = Book("legacy-book", "Legacy", "Nobody", listOf("one.xhtml"))
        val legacy = zipOf(mapOf(
            BackupFormat.MANIFEST to BackupFormat.manifest(1, 0, 0, 0L).toString(),
            BackupFormat.LIBRARY to mine.store.libraryJson(listOf(book)).toString(),
            "${BackupFormat.BOOKS}${book.id}/one.xhtml" to "<html><body><p>The old words.</p></body></html>",
        ))

        val tally = mine.backup.restore(ByteArrayInputStream(legacy))

        assertEquals(1, tally.books)
        assertTrue(mine.store.load().any { it.id == book.id })
    }

    @Test fun aFileThatIsNotABackupIsRefusedAndChangesNothing() {
        val book = mine.folderBook(files)
        val notABackup = listOf(
            "just some text, not even a zip".toByteArray(),
            zipOf(mapOf("holiday-photo.jpg" to "not ours")),
        )
        notABackup.forEach { bytes ->
            val failure = runCatching { mine.backup.restore(ByteArrayInputStream(bytes)) }
            assertEquals(BackupFormat.NOT_A_BACKUP, failure.exceptionOrNull()?.message)
        }
        assertEquals(listOf(book), mine.store.load())
    }

    @Test fun aFileThatIsNotOneOfOurBackupsIsRefusedHoweverItIsShaped() {
        val manifest = BackupFormat.manifest(books = 2, highlights = 5, notes = 1, at = 1234L)
        assertEquals("moonlight-pages", manifest.getString("app"))
        assertEquals(BackupFormat.VERSION, manifest.getInt("format"))
        assertEquals(2, BackupFormat.check(manifest.toString()).getInt("books"))
        // Anything that is not ours, in any shape, is refused the same plain way.
        listOf("not json at all", "", "{}", """{"app":"someone-else"}""").forEach { text ->
            assertEquals("refused: $text", BackupFormat.NOT_A_BACKUP,
                runCatching { BackupFormat.check(text) }.exceptionOrNull()?.message)
        }
    }

    @Test fun aBackupFromANewerDuckReaderIsRefused() {
        val newer = zipOf(mapOf(BackupFormat.MANIFEST to BackupFormat.manifest(0, 0, 0, 0L)
            .put("format", BackupFormat.VERSION + 1).toString(), BackupFormat.LIBRARY to "[]"))
        val failure = runCatching { mine.backup.restore(ByteArrayInputStream(newer)) }
        assertEquals("This backup was made by a newer version of Duck Reader.", failure.exceptionOrNull()?.message)
        assertTrue(mine.store.load().isEmpty())
    }

    @Test fun anOlderBackupCannotWriteOutsideItsOwnBookFolder() {
        val book = Book("escape-book", "Escape", "Nobody", listOf("one.xhtml"))
        val bytes = zipOf(mapOf(
            BackupFormat.MANIFEST to BackupFormat.manifest(1, 0, 0, 0L).toString(),
            BackupFormat.LIBRARY to mine.store.libraryJson(listOf(book)).toString(),
            "${BackupFormat.BOOKS}${book.id}/one.xhtml" to "the chapter",
            "${BackupFormat.BOOKS}${book.id}/../../escaped.txt" to "this must never land anywhere",
        ))

        val failure = runCatching { mine.backup.restore(ByteArrayInputStream(bytes)) }

        assertNotNull("a file that tries to escape is refused", failure.exceptionOrNull())
        assertFalse(File(mine.folder, "escaped.txt").exists())
        assertFalse(File(mine.folder, "books/${book.id}").exists())
        assertTrue(mine.store.load().isEmpty())
    }

    @Test fun anOlderBackupWithHalfABookLeavesItOffTheShelf() {
        val book = Book("half-a-book", "Half a Book", "Nobody", listOf("missing.xhtml"))
        val bytes = zipOf(mapOf(
            BackupFormat.MANIFEST to BackupFormat.manifest(1, 0, 0, 0L).toString(),
            BackupFormat.LIBRARY to mine.store.libraryJson(listOf(book)).toString(),
            "${BackupFormat.BOOKS}${book.id}/other.xhtml" to "not the chapter the index names",
        ))

        val failure = runCatching { mine.backup.restore(ByteArrayInputStream(bytes)) }

        assertTrue("says which book is incomplete",
            failure.exceptionOrNull()?.message.orEmpty().contains("incomplete"))
        assertTrue(mine.store.load().isEmpty())
        assertFalse(File(mine.folder, "books/${book.id}").exists())
    }

    /** Saves everything this phone holds, exactly as the reader's own backup would. */
    private fun Phone.save(): ByteArray = ByteArrayOutputStream().also { backup.save(it) }.toByteArray()

    /** The same file in the same folder, seen by phones that share nothing but the folder. */
    private fun Phone.folderBook(shared: SharedBooks): Book {
        store.addFolder(shared.tree)
        return store.scanFolders().single()
    }
}

/** One phone's own private storage, so a backup can be made on one and read on another. */
private class Phone(name: String) {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "$name-${UUID.randomUUID()}"
    val folder: File = File(target.cacheDir, key).apply { mkdirs() }
    private val isolated = object : ContextWrapper(target) {
        override fun getFilesDir() = folder
        override fun getCacheDir() = File(folder, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("$key-$name", mode)
    }
    private val shared = SharedBooks()
    val store = EpubStore(isolated, FolderLibrary(File(folder, "folders.json"), File(folder, "cache"), shared))
    val backup = ReadingBackup(isolated, store)

    fun cleanUp() {
        isolated.getSharedPreferences("reading", Context.MODE_PRIVATE).edit().clear().commit()
        folder.deleteRecursively()
    }
}

/** A granted folder held in memory: both phones see the same file, under the same document name. */
private class SharedBooks : FolderAccess {
    val tree = "tree:reading-backup"
    private val document = "$tree/novel.epub"
    private val bytes = sampleEpubBytes()
    private val root = "$tree/root"

    override fun persist(tree: String) = Unit
    override fun release(tree: String) = Unit
    override fun label(tree: String) = "Books"
    override fun root(tree: String) = if (tree == this.tree) root else null

    override fun children(tree: String, folder: String): List<FolderEntry>? =
        if (tree == this.tree && folder == root) {
            listOf(FolderEntry(document, "novel.epub", false, bytes.size.toLong(), 1L))
        } else emptyList()

    override fun open(document: String): InputStream =
        if (document == this.document) bytes.inputStream() else throw IllegalStateException("The file has gone.")
}

/** A passage that lands inside one of the sample book's chapters, for a mark to be saved on. */
private fun passageIn(chapter: Int) = SelectedPassage(
    chapter, TextAnchor(1, 13, "particular"), TextAnchor(1, 23), "particular", .2f, "A Quiet Beginning")

/** A zip file built here, so the tests can hand the app files of their own making. */
private fun zipOf(entries: Map<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

/** Names and text of every entry a backup holds, for checking what did and did not travel. */
private fun entriesOf(bytes: ByteArray): Map<String, String> {
    val entries = mutableMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
        }
    }
    return entries
}
