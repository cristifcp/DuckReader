package com.duckreader.app

import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Where a book on the shelf is read from. An imported book is a copy the app made and owns; a book
 * in a folder is read where it lies, in a folder the reader granted through the system's own picker.
 */
sealed interface BookSource {
    data object Imported : BookSource

    /**
     * [tree] is the granted folder, [document] the book's own file inside it, and [label] the name
     * the folder was given, kept so the shelf can say where a book comes from without asking again.
     */
    data class Folder(val tree: String, val document: String, val label: String) : BookSource
}

/** A folder the reader granted Duck Reader, kept by the system's own document tree. */
internal data class SavedFolder(val tree: String, val label: String)

/** One file or folder inside a granted folder, as plain values so a scan can be checked in a test. */
internal data class FolderEntry(
    val uri: String, val name: String, val directory: Boolean,
    val size: Long = 0L, val modified: Long = 0L,
)

/**
 * The system's document tree behind a small interface. A fake can stand in for it in a test, and a
 * scan never needs to know that a book arrived through a content resolver rather than a plain file.
 */
internal interface FolderAccess {
    fun persist(tree: String)
    fun release(tree: String)
    fun label(tree: String): String?
    fun root(tree: String): String?
    fun children(tree: String, folder: String): List<FolderEntry>?

    /** Opens a document for reading, or throws when the folder no longer allows it. */
    fun open(document: String): InputStream
}

/** What reading one book file produces, whichever format it arrived in. */
internal data class ExtractedBook(
    val title: String,
    val author: String,
    val chapters: List<String>,
    val cover: String?,
    val fingerprint: String?,
)

/** A book found in a granted folder, with the metadata read the last time it changed. */
internal data class FolderBook(
    val id: String,
    val document: String,
    val tree: String,
    val label: String,
    val title: String,
    val author: String,
    val chapters: List<String>,
    val cover: String?,
    val fingerprint: String?,
    val size: Long,
    val modified: Long,
) {
    fun asBook() = Book(id, title, author, chapters, cover, fingerprint,
        BookSource.Folder(tree, document, label))
}

/**
 * The books in the reader's own folders. A folder is added and removed by the reader; its files are
 * scanned for books, and each book is unpacked into the app's cache so the rest of the reader can
 * open a page exactly as it does for an imported copy. The index keeps what a scan learned, so the
 * shelf is there at once after a restart without unpacking every book again. The cache is the
 * system's to clear, so a book is unpacked again whenever it is opened and its copy has gone.
 */
internal class FolderLibrary(
    indexFile: File,
    private val cacheRoot: File,
    private val access: FolderAccess,
) {
    private val index = AtomicFile(indexFile)

    private data class State(
        val folders: List<SavedFolder> = emptyList(),
        val books: List<FolderBook> = emptyList(),
        val hidden: Set<String> = emptySet(),
    )

    fun folders(): List<SavedFolder> = read().folders

    /** The books already known from the granted folders, without touching the folders themselves. */
    fun books(): List<Book> {
        val state = read()
        val current = state.folders.map { it.tree }.toSet()
        return state.books
            .filter { it.tree in current && it.id !in state.hidden }
            .map { it.asBook() }
    }

    fun hiddenCount(): Int = read().hidden.size

    @Synchronized fun add(tree: String) {
        val state = read()
        if (state.folders.any { it.tree == tree }) return
        access.persist(tree)
        val label = access.label(tree)?.takeIf { it.isNotBlank() } ?: "Folder"
        write(state.copy(folders = state.folders + SavedFolder(tree, label)))
    }

    /**
     * Stops reading from a folder. The books leave the shelf and their cached copies go, but the
     * reader's places, bookmarks and highlights stay where they are: adding the folder again brings
     * the books back as they were. Nothing in the folder itself is touched.
     */
    @Synchronized fun remove(tree: String) {
        val state = read()
        if (state.folders.none { it.tree == tree }) return
        val leaving = state.books.filter { it.tree == tree }
        leaving.forEach { File(cacheRoot, it.id).deleteRecursively() }
        runCatching { access.release(tree) }
        write(state.copy(
            folders = state.folders.filterNot { it.tree == tree },
            books = state.books.filterNot { it.tree == tree },
            hidden = state.hidden - leaving.map { it.id }.toSet(),
        ))
    }

    /**
     * Looks through every granted folder for books, unpacking only the books that are new or whose
     * file has changed. A folder that cannot be read this time keeps the books already known from
     * it rather than emptying that part of the shelf. Returns the books that were found.
     */
    @Synchronized fun scan(unpack: (InputStream, File) -> ExtractedBook): List<Book> {
        val state = read()
        val known = state.books.associateBy { it.id }
        val found = LinkedHashMap<String, FolderBook>()
        val unreadable = HashSet<String>()
        val stillSetAside = HashSet<String>()
        for (folder in state.folders) {
            val root = access.root(folder.tree)
            if (root == null) { unreadable += folder.tree; continue }
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.addLast(root to 0)
            var seen = 0
            var failed = false
            while (queue.isNotEmpty() && seen <= MAX_TREE_ENTRIES) {
                val (directory, depth) = queue.removeFirst()
                val children = access.children(folder.tree, directory)
                if (children == null) { failed = true; continue }
                for (entry in children) {
                    if (++seen > MAX_TREE_ENTRIES) break
                    if (entry.directory) {
                        if (depth < MAX_TREE_DEPTH && !entry.name.startsWith('.')) {
                            queue.addLast(entry.uri to depth + 1)
                        }
                    } else if (looksLikeBookFile(entry.name)) {
                        val id = folderBookId(entry.uri)
                        if (id in state.hidden) {
                            // A set-aside book is not on the shelf but is still known, so bringing it
                            // back does not need the file to be read again.
                            stillSetAside += id
                            known[id]?.let { found[id] = it }
                            continue
                        }
                        val old = known[id]
                        val record = if (old != null && old.document == entry.uri &&
                            old.tree == folder.tree && old.size == entry.size && old.modified == entry.modified) {
                            old.copy(label = folder.label)
                        } else {
                            extract(folder, entry, id, old, unpack)
                        }
                        if (record != null) found[id] = record
                    }
                }
            }
            if (failed) unreadable += folder.tree
        }
        // A folder that could not be read keeps what was known about it; a book that has really gone
        // from a readable folder is dropped with its cached copy.
        state.books.filter { it.tree in unreadable }.forEach { found.putIfAbsent(it.id, it) }
        // A book whose file has really gone is no longer waiting to be brought back; one in a folder
        // that could not be read this time still is.
        val hidden = stillSetAside + state.hidden.filter { id ->
            state.books.any { it.id == id && it.tree in unreadable }
        }
        cacheRoot.listFiles()?.forEach { cached -> if (cached.name !in found) cached.deleteRecursively() }
        val books = found.values.toList()
        write(state.copy(books = books, hidden = hidden))
        return books.filter { it.id !in hidden }.map { it.asBook() }
    }

    /** Takes a book off the shelf without touching the file: a later scan will not bring it back. */
    @Synchronized fun hide(id: String) {
        val state = read()
        if (id in state.hidden) return
        write(state.copy(hidden = state.hidden + id))
    }

    /** Brings back every book that was removed from a folder's part of the shelf. */
    @Synchronized fun restoreHidden() {
        val state = read()
        if (state.hidden.isEmpty()) return
        write(state.copy(hidden = emptySet()))
    }

    /**
     * Makes sure a folder book has a readable copy in the cache, unpacking it again when the system
     * has cleared it. True when the book can be opened now.
     */
    @Synchronized fun materialize(book: Book, unpack: (InputStream, File) -> ExtractedBook): Boolean {
        val source = book.source as? BookSource.Folder ?: return true
        val cached = File(cacheRoot, book.id)
        if (cached.isDirectory && book.chapters.all { bookFile(cached, it).isFile }) return true
        cached.deleteRecursively()
        cached.mkdirs()
        return try {
            val extracted = access.open(source.document).use { unpack(it, cached) }
            extracted.chapters.forEach { require(bookFile(cached, it).isFile) { "The book is incomplete." } }
            true
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            cached.deleteRecursively()
            false
        }
    }

    /** The folder a book's cached copy is unpacked into. */
    fun cacheFolder(id: String) = File(cacheRoot, id)

    private fun extract(
        folder: SavedFolder, entry: FolderEntry, id: String, old: FolderBook?,
        unpack: (InputStream, File) -> ExtractedBook,
    ): FolderBook? {
        val cached = File(cacheRoot, id)
        cached.deleteRecursively()
        cached.mkdirs()
        return try {
            val extracted = access.open(entry.uri).use { unpack(it, cached) }
            extracted.chapters.forEach { require(bookFile(cached, it).isFile) { "The book is incomplete." } }
            FolderBook(id, entry.uri, folder.tree, folder.label, extracted.title, extracted.author,
                extracted.chapters, extracted.cover?.takeIf { bookFile(cached, it).isFile },
                extracted.fingerprint, entry.size, entry.modified)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            cached.deleteRecursively()
            // A file that has changed but cannot be read keeps the name it was known by rather than
            // vanishing from the shelf without explanation.
            old?.copy(label = folder.label)
        }
    }

    // The index is the folder shelf's memory, not the books themselves: a damaged one is treated as
    // empty rather than stopping the app, since scanning the folders builds it again.

    private fun read(): State = runCatching {
        if (!index.baseFile.exists() && !File(index.baseFile.path + ".bak").exists()) return State()
        val json = JSONObject(index.openRead().bufferedReader().use { it.readText() })
        val folders = json.optJSONArray("folders").objects().mapNotNull { item ->
            runCatching {
                val tree = item.getString("tree")
                require(tree.isNotEmpty())
                SavedFolder(tree, item.optString("label").ifBlank { "Folder" })
            }.getOrNull()
        }
        val books = json.optJSONArray("books").objects().mapNotNull(::readBook)
        val hidden = json.optJSONArray("hidden").strings().toSet()
        State(folders, books, hidden)
    }.getOrDefault(State())

    private fun readBook(item: JSONObject): FolderBook? = runCatching {
        val id = item.getString("id")
        require(BOOK_ID.matches(id))
        val chapters = item.getJSONArray("chapters")
        require(chapters.length() > 0)
        val tree = item.getString("tree")
        val document = item.getString("document")
        require(tree.isNotEmpty() && document.isNotEmpty())
        FolderBook(id, document, tree, item.optString("label").ifBlank { "Folder" },
            item.getString("title"), item.getString("author"),
            (0 until chapters.length()).map { chapters.getString(it) },
            item.optString("cover").takeIf { it.isNotEmpty() },
            item.optString("fingerprint").takeIf { it.isNotEmpty() },
            item.optLong("size"), item.optLong("modified"))
    }.getOrNull()

    private fun write(state: State) {
        val json = JSONObject().put("version", INDEX_VERSION)
            .put("folders", JSONArray().apply {
                state.folders.forEach { put(JSONObject().put("tree", it.tree).put("label", it.label)) }
            })
            .put("books", JSONArray().apply {
                state.books.forEach { book ->
                    put(JSONObject().put("id", book.id).put("document", book.document)
                        .put("tree", book.tree).put("label", book.label)
                        .put("title", book.title).put("author", book.author)
                        .put("chapters", JSONArray(book.chapters))
                        .put("cover", book.cover.orEmpty())
                        .put("fingerprint", book.fingerprint.orEmpty())
                        .put("size", book.size).put("modified", book.modified))
                }
            })
            .put("hidden", JSONArray(state.hidden.toList()))
        val output = index.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(output)
        } catch (failure: Exception) {
            index.failWrite(output)
            throw failure
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }

/** A book's identifier in a folder is derived from its document, so a rescan finds the same book. */
internal fun folderBookId(document: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(document.toByteArray(Charsets.UTF_8))
    return "f" + digest.take(12).joinToString("") { "%02x".format(it) }
}

/** The file types Duck Reader reads, recognised by name before anything is unpacked. */
private val BOOK_FILE = Regex("(?i).+\\.(epub|fb2|mobi|prc|azw)")

internal fun looksLikeBookFile(name: String): Boolean = BOOK_FILE.matches(name.trim())

/** A folder is walked this deep and no deeper, so a whole phone's storage cannot hold a scan up. */
private const val MAX_TREE_DEPTH = 8
private const val MAX_TREE_ENTRIES = 5000
private const val INDEX_VERSION = 1
