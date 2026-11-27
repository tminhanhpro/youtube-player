package vn.savify.karaoke

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DlEntry(val videoId: String, val title: String, val channel: String, val durationSec: Int, val file: File)

class DlJob(val videoId: String, val title: String) {
    @Volatile var pct = 0
    @Volatile var error: String? = null
    @Volatile var done = false
}

/**
 * Tai bai hat ve bo nho may de nghe offline (rieng tu, dung cho muc dich ca nhan).
 * File luu o thu muc rieng cua app: Android/data/<app>/files/Music/YoutubePlayer.
 * Bai da tai se duoc phat thang tu file (xem Resolver.resolveImpl), khong ton mang.
 */
object Downloads {
    private const val CHUNK = 4L * 1024 * 1024
    private val EXTS = setOf("m4a", "mp4", "webm")
    private val CLEN_RE = Regex("[?&]clen=(\\d+)")
    private val RANGE_RE = Regex("[?&]range=[^&]*")

    private var dir: File? = null
    val jobs = ConcurrentHashMap<String, DlJob>()

    /** Tang len moi khi danh sach tai / tien trinh doi (giao dien theo doi de ve lai). */
    val changed = MutableStateFlow(0)

    /** Tang len khi bam "Huy tat ca": moi tac vu tai dang chay/cho se dung. */
    @Volatile var cancelGen = 0
    fun cancelAll() { cancelGen++; changed.value++ }
    fun clearFinished() {
        jobs.entries.removeAll { it.value.done || it.value.error != null }
        changed.value++
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun init(ctx: Context) {
        if (dir != null) return
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
        dir = File(base, "YoutubePlayer").also { it.mkdirs() }
    }

    fun folderPath(): String = dir?.absolutePath ?: ""

    fun localFile(videoId: String): File? {
        val d = dir ?: return null
        return d.listFiles()?.firstOrNull {
            it.nameWithoutExtension == videoId && it.extension in EXTS && it.length() > 0
        }
    }

    fun localUri(videoId: String): String? = localFile(videoId)?.let { Uri.fromFile(it).toString() }

    fun metaTitle(videoId: String): String =
        readMeta(videoId).getOrNull(0)?.takeIf { it.isNotBlank() } ?: ""

    private fun readMeta(videoId: String): List<String> = try {
        File(dir, "$videoId.txt").takeIf { it.exists() }?.readLines() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun list(): List<DlEntry> {
        val d = dir ?: return emptyList()
        val files = d.listFiles()?.filter { it.extension in EXTS && it.length() > 0 } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            val id = f.nameWithoutExtension
            val m = readMeta(id)
            DlEntry(
                id,
                m.getOrNull(0)?.takeIf { it.isNotBlank() } ?: id,
                m.getOrNull(1) ?: "",
                m.getOrNull(2)?.toIntOrNull() ?: 0,
                f
            )
        }
    }

    fun delete(videoId: String) {
        val d = dir ?: return
        d.listFiles()?.filter { it.nameWithoutExtension == videoId || it.name.startsWith("$videoId.") }?.forEach { it.delete() }
        changed.value++
    }

    fun activeCount(): Int = jobs.values.count { !it.done && it.error == null }

    /** Blocking: goi tu luong nen (Dispatchers.IO). Tra ve null neu thanh cong, hoac thong bao loi. */
    fun download(videoId: String, title: String, channel: String, durationSec: Int, audioOnly: Boolean = false): String? {
        val d = dir ?: return "Chưa sẵn sàng"
        if (localFile(videoId) != null) return "Bài này đã được tải"
        val old = jobs[videoId]
        if (old != null && !old.done && old.error == null) return "Đang tải bài này"
        val gen = cancelGen
        val job = DlJob(videoId, title)
        jobs[videoId] = job
        changed.value++
        var tmp: File? = null
        try {
            val r = Resolver.resolve(videoId, 720)
            val muxed = r.sources.firstOrNull { it.audio == null }
            val withAudio = r.sources.firstOrNull { it.audio != null }
            val url: String
            val ext: String
            if (!audioOnly && muxed != null) {
                url = muxed.video; ext = "mp4"          // hinh + tieng trong 1 file
            } else if (withAudio != null) {
                url = withAudio.audio!!; ext = "m4a"    // chi tieng
            } else {
                url = r.sources.first().video; ext = "mp4"
            }
            val total = CLEN_RE.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            val base = url.replace(RANGE_RE, "")
            val t = File(d, "$videoId.$ext.part")
            tmp = t
            var pos = 0L
            var finished = false
            var lastTick = 0
            FileOutputStream(t).use { out ->
                val buf = ByteArray(64 * 1024)
                while (!finished) {
                    val end = pos + CHUNK - 1
                    val u = base + (if (base.contains("?")) "&" else "?") + "range=$pos-$end"
                    val req = Request.Builder().url(u).header("User-Agent", Util.UA).build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        val ins = (resp.body ?: throw Exception("Rỗng")).byteStream()
                        var got = 0L
                        while (true) {
                            if (cancelGen != gen) throw Exception("Đã huỷ")
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            pos += n
                            if (total > 0) {
                                job.pct = (pos * 100 / total).toInt().coerceIn(0, 99)
                                if (job.pct != lastTick) { lastTick = job.pct; changed.value++ }
                            }
                        }
                        if (got == 0L || (total > 0 && pos >= total) || (total <= 0 && got < CHUNK)) finished = true
                    }
                }
            }
            val fin = File(d, "$videoId.$ext")
            if (fin.exists()) fin.delete()
            if (!t.renameTo(fin)) throw Exception("Không lưu được file")
            File(d, "$videoId.txt").writeText(
                listOf(title.ifBlank { r.title }, channel.ifBlank { r.channel }, (if (durationSec > 0) durationSec else r.durationSec).toString())
                    .joinToString("\n")
            )
            job.pct = 100
            job.done = true
            changed.value++
            return null
        } catch (e: Throwable) {
            try { tmp?.delete() } catch (x: Exception) { }
            val msg = (e.message ?: "lỗi mạng").take(80)
            job.error = msg
            changed.value++
            return msg
        }
    }
}
