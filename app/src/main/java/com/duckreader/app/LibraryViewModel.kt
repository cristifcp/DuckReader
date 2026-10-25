package com.duckreader.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

internal data class LibraryState(
    val books: List<Book> = emptyList(), val loading: Boolean = true,
    val importing: Boolean = false, val available: Boolean = false,
    val message: String? = null, val messageTitle: String? = null, val loadFailure: Boolean = false,
    /** A book handed to the app from outside, to open as soon as the shelf is ready. */
    val openBookId: String? = null,
    /** An incoming book that matches one already on the shelf, waiting for the reader's choice. */
    val duplicate: Duplicate? = null,
    /** A backup is being written, or one is being put back. */
    val backingUp: Boolean = false, val restoring: Boolean = false,
    /** The reader's own folders, as Settings lists them. */
    val folders: List<SavedFolder> = emptyList(),
    /** A folder is being looked through for books, or one book is being unpacked to open it. */
    val scanningFolders: Boolean = false, val preparing: Boolean = false,
    /** Books taken off the shelf whose files are still in a folder, waiting to be brought back. */
    val setAside: Int = 0,
) {
    /** True while the library is being changed, so one job never runs over another. */
    val busy: Boolean get() = importing || backingUp || restoring || scanningFolders || preparing
}

/** An incoming book that looks like one already on the shelf, and the copy already there. */
internal data class Duplicate(val staged: Book, val existing: Book)

/** Import jobs and their UI state survive Activity recreation, without retaining an Activity. */
internal class LibraryViewModel(
    context: Context,
    internal val store: EpubStore = EpubStore(context),
    private val openStream: (Uri) -> InputStream? = { context.contentResolver.openInputStream(it) },
    /** Where a backup is written; kept apart from the shelf so a test can keep one in memory. */
    private val openOutput: (Uri) -> OutputStream? = { context.contentResolver.openOutputStream(it) },
    private val backup: ReadingBackup = ReadingBackup(context, store),
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryState())
    val state = mutableState.asStateFlow()

    /** A book handed to the app while the shelf was still getting ready. */
    private var waiting: Uri? = null

    /** Set while a book is being taken off the shelf, so one removal cannot overlap another. */
    private var removing = false

    init { reload() }

    fun reload() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(loading = true, available = false, message = null, loadFailure = false)
        viewModelScope.launch {
            try {
                val (books, folders, setAside) = withContext(Dispatchers.IO) {
                    Triple(store.load(), store.folders(), store.setAsideCount())
                }
                mutableState.value = LibraryState(books = books, loading = false, available = true,
                    folders = folders, setAside = setAside)
                takeWaiting()
                scanFolders()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(loading = false, available = false,
                    loadFailure = true, messageTitle = "Couldn’t load your library",
                    message = "Your saved library couldn’t be opened. Your book files have not been changed.")
            }
        }
    }

    /**
     * Looks through the reader's folders again, so a book dropped into one appears on the shelf
     * without the app being restarted. The shelf keeps its current books while the scan runs.
     */
    fun scanFolders() {
        val state = mutableState.value
        if (!state.available || state.scanningFolders || state.folders.isEmpty()) return
        mutableState.value = state.copy(scanningFolders = true, message = null, messageTitle = null)
        viewModelScope.launch {
            try {
                val (books, folders, setAside) = withContext(Dispatchers.IO) {
                    store.scanFolders()
                    Triple(store.load(), store.folders(), store.setAsideCount())
                }
                if (mutableState.value.loading) return@launch
                mutableState.value = mutableState.value.copy(books = books, folders = folders,
                    setAside = setAside, scanningFolders = false)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(scanningFolders = false,
                    messageTitle = "Couldn’t look in your folders",
                    message = failure.message
                        ?: "The folders couldn’t be read just now. The books already on your shelf are still there.")
            }
        }
    }

    /** Adds a folder the reader chose, then looks through it for books straight away. */
    fun addFolder(uri: Uri) {
        if (mutableState.value.busy || !mutableState.value.available) return
        mutableState.value = mutableState.value.copy(message = null, messageTitle = null)
        viewModelScope.launch {
            try {
                val folders = withContext(Dispatchers.IO) {
                    store.addFolder(uri.toString())
                    store.folders()
                }
                mutableState.value = mutableState.value.copy(folders = folders)
                scanFolders()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(
                    messageTitle = "Couldn’t add this folder",
                    message = failure.message ?: "The folder couldn’t be opened. Nothing has been changed.")
            }
        }
    }

    /** Stops reading from a folder; its books leave the shelf and nothing in the folder is touched. */
    fun removeFolder(folder: SavedFolder) {
        if (mutableState.value.busy) return
        viewModelScope.launch {
            try {
                val (books, folders, setAside) = withContext(Dispatchers.IO) {
                    store.removeFolder(folder.tree)
                    Triple(store.load(), store.folders(), store.setAsideCount())
                }
                mutableState.value = mutableState.value.copy(books = books, folders = folders, setAside = setAside)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(
                    messageTitle = "Couldn’t remove this folder",
                    message = failure.message ?: "The folder couldn’t be removed. Nothing has been changed.")
            }
        }
    }

    /** Brings back the folder books the reader had taken off the shelf. */
    fun restoreSetAside() {
        if (mutableState.value.busy) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.restoreSetAside() }
                scanFolders()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(
                    messageTitle = "Couldn’t bring those books back",
                    message = failure.message ?: "The folders couldn’t be read just now.")
            }
        }
    }

    /**
     * Opens a book. A book read where it lies is unpacked into the cache first, if the system has
     * cleared that copy, so the reader always gets pages rather than an empty folder.
     */
    fun open(book: Book) {
        if (mutableState.value.busy) return
        if (book.source !is BookSource.Folder) {
            mutableState.value = mutableState.value.copy(openBookId = book.id, message = null, messageTitle = null)
            return
        }
        mutableState.value = mutableState.value.copy(preparing = true, message = null, messageTitle = null)
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) { runCatching { store.prepare(book) }.getOrDefault(false) }
            mutableState.value = mutableState.value.copy(preparing = false,
                openBookId = if (ready) book.id else null,
                messageTitle = if (ready) null else "Couldn’t open this book",
                message = if (ready) null else folderOpenFailure(book))
        }
    }

    fun dismissMessage() { mutableState.value = mutableState.value.copy(message = null) }

    /**
     * A book handed over from a file manager or a share is no longer imported: Duck Reader reads
     * books from folders now, so it says where the book belongs instead of taking a copy of it.
     */
    fun explainFolderOnly() {
        mutableState.value = mutableState.value.copy(
            messageTitle = "Books are read from folders now",
            message = "Duck Reader doesn’t import books any more. Add the folder that holds this book " +
                "with + on the shelf, then open it from there.")
    }

    internal fun importBook(create: () -> Book) {
        if (mutableState.value.busy || !mutableState.value.available) return
        mutableState.value = mutableState.value.copy(importing = true, message = null, loadFailure = false)
        viewModelScope.launch {
            try {
                val books = withContext(Dispatchers.IO) { store.importIntoLibrary(create) }
                mutableState.value = mutableState.value.copy(books = books, importing = false)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(importing = false,
                    messageTitle = "Couldn’t import this book",
                    message = failure.message ?: "This book couldn’t be imported. Please try another DRM-free EPUB.")
            }
        }
    }

    /**
     * Takes a book off the shelf for good. The copy Duck Reader made goes with it, along with the
     * reading position, bookmarks and highlights saved for it. The file it was imported from is
     * never touched.
     */
    fun delete(book: Book) {
        if (removing || mutableState.value.busy) return
        removing = true
        mutableState.value = mutableState.value.copy(message = null, messageTitle = null)
        viewModelScope.launch {
            try {
                val books = withContext(Dispatchers.IO) { store.removeFromLibrary(book) }
                val setAside = withContext(Dispatchers.IO) { store.setAsideCount() }
                mutableState.value = mutableState.value.copy(books = books, setAside = setAside)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(
                    messageTitle = "Couldn’t remove this book",
                    message = failure.message ?: "This book couldn’t be removed.")
            } finally { removing = false }
        }
    }

    /**
     * Writes everything the reader has made — their books, reading places, bookmarks, highlights and
     * notes — into a file they chose to keep it in, so it survives a phone being lost or replaced.
     */
    fun backupTo(uri: Uri) {
        if (mutableState.value.busy || !mutableState.value.available) return
        mutableState.value = mutableState.value.copy(backingUp = true, message = null, messageTitle = null)
        viewModelScope.launch {
            try {
                val tally = withContext(Dispatchers.IO) {
                    val output = openOutput(uri) ?: error("That file could not be written.")
                    output.use { backup.save(it) }
                }
                mutableState.value = mutableState.value.copy(backingUp = false,
                    messageTitle = "Backup saved",
                    message = (if (tally.nothing) "Saved your reading settings"
                    else "Saved ${tally.describe()}") +
                        " into the file you chose. Books stay in their folders; a backup carries " +
                        "the places, marks and settings made around them. Keep it somewhere other " +
                        "than this phone, such as a computer or a cloud drive.")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(backingUp = false,
                    messageTitle = "Couldn’t save your backup",
                    message = failure.message ?: "The backup couldn’t be written. Nothing has been changed.")
            }
        }
    }

    /**
     * Adds everything a backup holds, without removing anything already here: a book already on the
     * shelf stays exactly as it is, a reading place only moves when the backup is further along, and a
     * note written since the backup was made is never taken back.
     */
    fun restoreFrom(uri: Uri) {
        if (mutableState.value.busy || !mutableState.value.available) return
        mutableState.value = mutableState.value.copy(restoring = true, message = null, messageTitle = null)
        viewModelScope.launch {
            try {
                val tally = withContext(Dispatchers.IO) {
                    val input = openStream(uri) ?: error("That file could not be opened.")
                    input.use { backup.restore(it) }
                }
                val books = withContext(Dispatchers.IO) { store.load() }
                mutableState.value = mutableState.value.copy(books = books, restoring = false,
                    messageTitle = if (tally.nothing) "Already on this phone" else "Backup restored",
                    message = if (tally.nothing) "Everything in this backup is already on your phone."
                    else "Brought back ${tally.describe()}. Anything already here was kept.")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(restoring = false,
                    messageTitle = "Couldn’t restore this backup",
                    message = failure.message ?: "This file couldn’t be read. Nothing has been changed.")
            }
        }
    }

    /**
     * Takes a book handed to the app from outside — "Open with Duck Reader" from a file manager, or a
     * share. The file is unpacked first, then weighed against the shelf: the very same file opens the
     * copy already there, a lookalike asks the reader, and anything genuinely new is simply added.
     */
    fun openIncoming(uri: Uri) {
        if (!mutableState.value.available || mutableState.value.busy) { waiting = uri; return }
        mutableState.value = mutableState.value.copy(importing = true, message = null, loadFailure = false)
        viewModelScope.launch {
            var staged: Book? = null
            try {
                val (books, known) = withContext(Dispatchers.IO) { store.load() to store.knownFiles() }
                staged = withContext(Dispatchers.IO) {
                    val input = openStream(uri) ?: error("This file could not be opened.")
                    input.use { store.importBook(it) }
                }
                when (val incoming = decide(staged, books, known)) {
                    is Incoming.Same -> {
                        discard(staged)
                        val ready = readyToOpen(incoming.book)
                        mutableState.value = mutableState.value.copy(importing = false,
                            openBookId = if (ready) incoming.book.id else null,
                            messageTitle = if (ready) null else "Couldn’t open this book",
                            message = if (ready) null else folderOpenFailure(incoming.book))
                    }
                    is Incoming.Ask -> mutableState.value = mutableState.value.copy(
                        importing = false, duplicate = Duplicate(staged, incoming.existing))
                    Incoming.Add -> {
                        val updated = withContext(Dispatchers.IO) { store.addToLibrary(staged) }
                        mutableState.value = mutableState.value.copy(
                            books = updated, importing = false, openBookId = staged.id)
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // A file that could not be read must leave the shelf exactly as it was.
                staged?.let { unreadable -> discard(unreadable) }
                mutableState.value = mutableState.value.copy(importing = false,
                    messageTitle = "Couldn’t import this book",
                    message = failure.message ?: "This book couldn’t be opened.")
            }
            takeWaiting()
        }
    }

    /** Keeps the incoming file as a second copy of a book that is already on the shelf. */
    fun addSecondCopy() {
        val pending = mutableState.value.duplicate ?: return
        mutableState.value = mutableState.value.copy(duplicate = null, importing = true)
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { store.addToLibrary(pending.staged) }
                mutableState.value = mutableState.value.copy(
                    books = updated, importing = false, openBookId = pending.staged.id)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                discard(pending.staged)
                mutableState.value = mutableState.value.copy(importing = false,
                    messageTitle = "Couldn’t add this book",
                    message = failure.message ?: "This book couldn’t be added.")
            }
            takeWaiting()
        }
    }

    /** Opens the copy already on the shelf and drops the incoming file. */
    fun openExistingCopy() {
        val pending = mutableState.value.duplicate ?: return
        mutableState.value = mutableState.value.copy(duplicate = null,
            preparing = pending.existing.source is BookSource.Folder)
        viewModelScope.launch {
            // Remember the answer, so this file opens straight into the same book from now on.
            withContext(Dispatchers.IO) {
                runCatching { store.rememberCopy(pending.existing, pending.staged.fingerprint) }
            }
            discard(pending.staged)
            val ready = readyToOpen(pending.existing)
            mutableState.value = mutableState.value.copy(preparing = false,
                openBookId = if (ready) pending.existing.id else null,
                messageTitle = if (ready) null else "Couldn’t open this book",
                message = if (ready) null else folderOpenFailure(pending.existing))
            takeWaiting()
        }
    }

    /** Leaves the shelf untouched and drops the incoming file. */
    fun cancelIncoming() {
        val pending = mutableState.value.duplicate ?: return
        mutableState.value = mutableState.value.copy(duplicate = null)
        viewModelScope.launch { discard(pending.staged); takeWaiting() }
    }

    /** Marks the book handed to the app as opened, so it is not opened twice. */
    fun consumeOpened() { mutableState.value = mutableState.value.copy(openBookId = null) }

    private suspend fun discard(book: Book) {
        withContext(Dispatchers.IO) { runCatching { store.discard(book) } }
    }

    /** A folder book needs its cached copy before the reader can show a page of it. */
    private suspend fun readyToOpen(book: Book): Boolean =
        book.source !is BookSource.Folder ||
            withContext(Dispatchers.IO) { runCatching { store.prepare(book) }.getOrDefault(false) }

    private fun takeWaiting() {
        waiting?.let { uri -> waiting = null; openIncoming(uri) }
    }
}

/** Said when a book's folder no longer gives access to its file, without alarming the reader. */
internal fun folderOpenFailure(book: Book): String =
    "“${book.title}” couldn’t be read from its folder. It may have been moved or removed, or access " +
        "to the folder may have been taken away. Your reading place and marks are safe."
