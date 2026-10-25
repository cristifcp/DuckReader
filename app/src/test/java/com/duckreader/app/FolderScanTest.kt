package com.duckreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A folder book is named from the file's own document address, so a scan repeated after a restart
 * finds the same book with the same reading place; and only files that could be books are read.
 */
class FolderScanTest {
    @Test fun aBookInAFolderKeepsItsNameAcrossScans() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2Fnovel.epub"
        assertEquals(folderBookId(uri), folderBookId(uri))
        assertTrue("The name must be safe to use as a folder.", BOOK_ID.matches(folderBookId(uri)))
        assertNotEquals(folderBookId(uri), folderBookId("$uri.other"))
    }

    @Test fun onlyFilesThatCouldBeBooksAreLookedFor() {
        listOf("novel.epub", "NOVEL.EPUB", "book.fb2", "book.mobi", "book.prc", "book.azw").forEach {
            assertTrue("$it should be looked at", looksLikeBookFile(it))
        }
        listOf("cover.jpg", "notes.txt", "book.epub.part", "epub", "backup.zip").forEach {
            assertFalse("$it should be left alone", looksLikeBookFile(it))
        }
    }

    @Test fun aBookReadFromAFolderSaysWhereItComesFrom() {
        val book = Book("f1", "Metro 2033", "Dmitry Glukhovsky", listOf("one.xhtml"),
            source = BookSource.Folder("tree", "document", "Books"))
        val source = book.source as BookSource.Folder
        assertEquals("Books", source.label)
        assertEquals(BookSource.Imported, Book("f2", "Other", "Author", listOf("one.xhtml")).source)
    }
}
