package com.duckreader.app

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A shelf made of a folder rather than of imported copies: the scan puts the book on the shelf, the
 * reader opens a copy unpacked into the cache, and taking the book or the folder away never touches
 * the file the reader keeps.
 */
class FolderShelfTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var folder: File
    private lateinit var cache: File
    private lateinit var context: Context
    private lateinit var access: FakeFolders
    private lateinit var store: EpubStore

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "folder-shelf-test-${UUID.randomUUID()}"
        folder = File(target.cacheDir, key).apply { mkdirs() }
        cache = File(folder, "cache")
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = folder
            override fun getCacheDir() = cache
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$key-$name", mode)
        }
        access = FakeFolders()
        store = EpubStore(context, FolderLibrary(File(folder, "folders.json"), cache, access))
    }

    @After fun cleanup() { folder.deleteRecursively() }

    private fun factory() = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(context, store) as T
        }
    }

    private fun await(model: LibraryViewModel, condition: (LibraryState) -> Boolean): LibraryState =
        runBlocking { withTimeout(15_000) { model.state.first(condition) } }

    private fun epub(title: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("book.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">test-book</dc:identifier><dc:title>$title</dc:title><dc:creator>An Author</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>""")
            entry("one.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1><p>Words.</p></body></html>""")
        }
        return bytes.toByteArray()
    }

    private fun grantFolder(bytes: ByteArray = epub("Metro 2033")): String {
        val tree = "tree:books"
        val document = "$tree/novel.epub"
        access.trees[tree] = "doc:books-root"
        access.labels[tree] = "Books"
        access.folders[tree to "doc:books-root"] = mutableListOf(
            FolderEntry(document, "novel.epub", false, bytes.size.toLong(), 1L))
        access.files[document] = bytes
        return tree
    }

    @Test fun aBookInAFolderIsScannedIntoTheShelfAndOpensFromTheCache() {
        val tree = grantFolder()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }

            scenario.onActivity { model.addFolder(Uri.parse(tree)) }
            val shelf = await(model) { it.books.size == 1 && !it.scanningFolders }
            val book = shelf.books.single()
            assertEquals("Metro 2033", book.title)
            assertTrue(book.source is BookSource.Folder)
            assertEquals("Books", (book.source as BookSource.Folder).label)
            assertFalse("A folder's books are not written into the imported shelf.",
                File(folder, "library.json").exists())

            scenario.onActivity { model.open(book) }
            val opened = await(model) { it.openBookId != null && !it.preparing }
            assertEquals(book.id, opened.openBookId)
            assertTrue(store.folder(book).isDirectory)
            assertTrue(File(store.folder(book), "one.xhtml").isFile)
        }
    }

    @Test fun aCacheTheSystemHasClearedIsBuiltAgainWhenTheBookIsOpened() {
        val tree = grantFolder()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.addFolder(Uri.parse(tree)) }
            val book = await(model) { it.books.size == 1 && !it.scanningFolders }.books.single()
            scenario.onActivity { model.open(book) }
            await(model) { it.openBookId != null && !it.preparing }
            scenario.onActivity { model.consumeOpened() }
            await(model) { it.openBookId == null }

            assertTrue(store.folder(book).deleteRecursively())
            scenario.onActivity { model.open(book) }

            val opened = await(model) { it.openBookId == book.id && !it.preparing }
            assertNotNull(opened.openBookId)
            assertTrue(File(store.folder(book), "one.xhtml").isFile)
        }
    }

    @Test fun takingAFolderBookOffTheShelfLeavesTheFileAndCanBeUndone() {
        val tree = grantFolder()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.addFolder(Uri.parse(tree)) }
            val book = await(model) { it.books.size == 1 && !it.scanningFolders }.books.single()
            val original = access.files.values.single()

            scenario.onActivity { model.delete(book) }
            val empty = await(model) { it.books.isEmpty() }
            assertEquals(1, empty.setAside)
            assertArrayEquals("The book stays in its folder.", original, access.files.values.single())
            assertFalse("The cached copy may go.", store.folder(book).exists())

            scenario.onActivity { model.restoreSetAside() }
            val back = await(model) { it.books.size == 1 && it.setAside == 0 }
            assertEquals(book.id, back.books.single().id)
        }
    }

    @Test fun removingTheFolderTakesItsBooksOffTheShelfAndKeepsTheGrantedFiles() {
        val tree = grantFolder()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: LibraryViewModel
            scenario.onActivity { model = ViewModelProvider(it, factory())[LibraryViewModel::class.java] }
            await(model) { it.available }
            scenario.onActivity { model.addFolder(Uri.parse(tree)) }
            val folderRow = await(model) { it.books.size == 1 && it.folders.isNotEmpty() }.folders.single()

            scenario.onActivity { model.removeFolder(folderRow) }
            val left = await(model) { it.folders.isEmpty() && it.books.isEmpty() }
            assertTrue(left.books.isEmpty())
            assertTrue("The file itself is never removed.", access.files.isNotEmpty())
        }
    }

    /** A granted folder held in memory: the scan sees exactly these files. */
    private class FakeFolders : FolderAccess {
        val trees = mutableMapOf<String, String>()
        val labels = mutableMapOf<String, String>()
        val folders = mutableMapOf<Pair<String, String>, MutableList<FolderEntry>>()
        val files = mutableMapOf<String, ByteArray>()

        override fun persist(tree: String) = Unit
        override fun release(tree: String) = Unit
        override fun label(tree: String) = labels[tree]
        override fun root(tree: String): String? = trees[tree]
        override fun children(tree: String, folder: String): List<FolderEntry>? =
            folders[tree to folder].orEmpty()

        override fun open(document: String): InputStream =
            files[document]?.inputStream() ?: throw IllegalStateException("The file has gone.")
    }
}
