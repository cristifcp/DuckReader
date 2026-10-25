package com.duckreader.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * The system's document tree, read through the reader's own granted folders. Only what the reader
 * chose in the folder picker is ever looked at, and only for reading: nothing here writes to a folder.
 */
internal class SafFolderAccess(private val resolver: ContentResolver) : FolderAccess {
    override fun persist(tree: String) {
        // Some providers have no long-lived grant to keep; the folder still works for this session.
        runCatching {
            resolver.takePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun release(tree: String) {
        runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun label(tree: String): String? {
        val uri = Uri.parse(tree)
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val document = runCatching { DocumentsContract.buildDocumentUriUsingTree(uri, documentId) }.getOrNull()
        val named = document?.let(::displayName)
        return named?.takeIf { it.isNotBlank() }
            ?: documentId.substringAfterLast(':').takeIf { it.isNotBlank() }
    }

    override fun root(tree: String): String? = runCatching {
        val uri = Uri.parse(tree)
        DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)).toString()
    }.getOrNull()

    override fun children(tree: String, folder: String): List<FolderEntry>? = runCatching {
        val treeUri = Uri.parse(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(Uri.parse(folder)))
        val found = mutableListOf<FolderEntry>()
        resolver.query(childrenUri, arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        ), null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val id = if (idColumn >= 0) cursor.getString(idColumn) else null
                val name = if (nameColumn >= 0) cursor.getString(nameColumn) else null
                if (id == null || name == null) continue
                val mime = if (mimeColumn >= 0) cursor.getString(mimeColumn) else null
                found += FolderEntry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                    name = name,
                    directory = mime == Document.MIME_TYPE_DIR,
                    // A provider may not report either; a scan then reads the file again to be sure.
                    size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else -1L,
                    modified = if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) {
                        cursor.getLong(modifiedColumn)
                    } else -1L,
                )
            }
        }
        found
    }.getOrNull()

    override fun open(document: String): InputStream =
        resolver.openInputStream(Uri.parse(document)) ?: error("This book could not be opened.")

    private fun displayName(document: Uri): String? = runCatching {
        resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
