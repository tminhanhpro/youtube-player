package vn.savify.karaoke

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

data class LocalItem(
    val id: String, val title: String, val folder: String,
    val sizeBytes: Long, val uri: String, val isVideo: Boolean
)

/**
 * Nhac & video trong may: nguoi dung chon file le hoac thu muc (Storage Access Framework, khong can quyen rieng).
 * Bai local co videoId dang "local:xxxxxxxx"; Resolver phat thang tu content:// uri.
 */
object LocalLib {
    val AUDIO = setOf("mp3", "m4a", "wav", "flac", "ogg", "opus", "aac")
    val VIDEO = setOf("mp4", "mkv", "webm", "mov", "m4v")
    private const val MAX_ITEMS = 5000

    private var app: Context? = null
    private val byId = ConcurrentHashMap<String, LocalItem>()
    @Volatile private var scanned = false

    fun init(ctx: Context) { if (app == null) app = ctx.applicationContext }

    private fun sp() = app?.getSharedPreferences("locallib", Context.MODE_PRIVATE)

    private fun readList(key: String): List<String> {
        val raw = sp()?.getString(key, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(key: String, l: List<String>) {
        sp()?.edit()?.putString(key, JSONArray(l).toString())?.apply()
    }

    fun trees(): List<String> = readList("trees")
    fun files(): List<String> = readList("files")
    fun addTree(u: String) { if (u !in trees()) writeList("trees", trees() + u) }
    fun removeTree(u: String) { writeList("trees", trees() - u) }
    fun addFiles(l: List<String>) { writeList("files", (files() + l).distinct()) }
    fun clearFiles() { writeList("files", emptyList()) }

    fun idOf(uri: String): String = "local:" + Integer.toHexString(uri.hashCode()) + Integer.toHexString(uri.length)

    fun treeLabel(uri: String): String = try {
        DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfterLast('/').substringAfterLast(':').ifBlank { "Thư mục" }
    } catch (e: Exception) {
        "Thư mục"
    }

    /** Dung cho Resolver (luong nen). Neu chua quet lan nao thi quet mot lan. */
    fun uriFor(id: String): String? {
        if (!scanned) scan()
        return byId[id]?.uri
    }

    fun titleFor(id: String): String = byId[id]?.title ?: ""

    /** Blocking: goi tu luong nen. */
    fun scan(): List<LocalItem> {
        val ctx = app ?: return emptyList()
        val out = ArrayList<LocalItem>()
        for (t in trees()) {
            try {
                val tu = Uri.parse(t)
                walk(ctx, tu, DocumentsContract.getTreeDocumentId(tu), treeLabel(t), out, 0)
            } catch (e: Exception) {
            }
        }
        for (f in files()) {
            if (out.size >= MAX_ITEMS) break
            try {
                ctx.contentResolver.query(Uri.parse(f), arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0) ?: return@use
                        val size = if (c.isNull(1)) 0L else c.getLong(1)
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in AUDIO || ext in VIDEO) {
                            out.add(LocalItem(idOf(f), name.substringBeforeLast('.'), "File lẻ", size, f, ext in VIDEO))
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
        out.sortBy { it.title.lowercase() }
        byId.clear()
        out.forEach { byId[it.id] = it }
        scanned = true
        return out
    }

    private fun walk(ctx: Context, tree: Uri, docId: String, folder: String, out: ArrayList<LocalItem>, depth: Int) {
        if (depth > 6 || out.size >= MAX_ITEMS) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        ctx.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext() && out.size < MAX_ITEMS) {
                val did = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(ctx, tree, did, name, out, depth + 1)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in AUDIO || ext in VIDEO) {
                        val u = DocumentsContract.buildDocumentUriUsingTree(tree, did).toString()
                        out.add(LocalItem(idOf(u), name.substringBeforeLast('.'), folder, size, u, ext in VIDEO))
                    }
                }
            }
        }
    }
}
