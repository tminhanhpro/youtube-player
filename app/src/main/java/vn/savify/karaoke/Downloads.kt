package vn.savify.karaoke

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
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

    /** Tai 1 URL (chia khuc 4MB) vao file t. onBytes(n) bao so byte vua nhan, de tinh tien do. */
    private fun fetchTo(url: String, t: File, gen: Int, onBytes: (Int) -> Unit) {
        val total = CLEN_RE.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val base = url.replace(RANGE_RE, "")
        var pos = 0L
        var finished = false
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
                        onBytes(n)
                    }
                    if (got == 0L || (total > 0 && pos >= total) || (total <= 0 && got < CHUNK)) finished = true
                }
            }
        }
    }

    private fun clen(url: String): Long = CLEN_RE.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun pickTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        throw Exception("Thiếu luồng $prefix")
    }

    private fun copyTrack(ex: MediaExtractor, mx: MediaMuxer, outTrack: Int, gen: Int) {
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (cancelGen != gen) throw Exception("Đã huỷ")
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            info.set(0, n, ex.sampleTime, ex.sampleFlags)
            mx.writeSampleData(outTrack, buf, info)
            ex.advance()
        }
    }

    /** Ghep file hinh (mp4, khong tieng) + file tieng (m4a) thanh 1 file mp4 (khong ma hoa lai, rat nhanh). */
    private fun mux(videoF: File, audioF: File, out: File, gen: Int) {
        val vx = MediaExtractor()
        val ax = MediaExtractor()
        var mx: MediaMuxer? = null
        var started = false
        try {
            vx.setDataSource(videoF.absolutePath)
            ax.setDataSource(audioF.absolutePath)
            val vt = pickTrack(vx, "video/")
            val at = pickTrack(ax, "audio/")
            vx.selectTrack(vt)
            ax.selectTrack(at)
            mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vi = mx.addTrack(vx.getTrackFormat(vt))
            val ai = mx.addTrack(ax.getTrackFormat(at))
            mx.start()
            started = true
            copyTrack(vx, mx, vi, gen)
            copyTrack(ax, mx, ai, gen)
        } finally {
            try { if (started) mx?.stop() } catch (e: Exception) { }
            try { mx?.release() } catch (e: Exception) { }
            try { vx.release() } catch (e: Exception) { }
            try { ax.release() } catch (e: Exception) { }
        }
    }

    /**
     * Blocking: goi tu luong nen (Dispatchers.IO). Tra ve null neu thanh cong, hoac thong bao loi.
     * maxH = chat luong video toi da (360/720/1080; 9999 = cao nhat co the). Tren 720p can ghep hinh + tieng.
     */
    fun download(videoId: String, title: String, channel: String, durationSec: Int, audioOnly: Boolean = false, maxH: Int = 720): String? {
        val d = dir ?: return "Chưa sẵn sàng"
        if (localFile(videoId) != null) return "Bài này đã được tải"
        val old = jobs[videoId]
        if (old != null && !old.done && old.error == null) return "Đang tải bài này"
        val gen = cancelGen
        val job = DlJob(videoId, title)
        jobs[videoId] = job
        changed.value++
        val temps = ArrayList<File>()
        try {
            val r = Resolver.resolve(videoId, maxH)
            val muxed = r.sources.firstOrNull { it.audio == null }
            val split = r.sources.firstOrNull { it.audio != null }   // hinh rieng + tieng rieng (do phan giai cao)
            if (!audioOnly && split != null) {
                // Chat luong cao: tai hinh va tieng rieng roi ghep
                val vF = File(d, "$videoId.v.part"); temps.add(vF)
                val aF = File(d, "$videoId.a.part"); temps.add(aF)
                val total = (clen(split.video) + clen(split.audio!!)).coerceAtLeast(1L)
                var done = 0L
                var lastTick = -1
                val tick = { n: Int ->
                    done += n
                    job.pct = (done * 95 / total).toInt().coerceIn(0, 95)
                    if (job.pct != lastTick) { lastTick = job.pct; changed.value++ }
                }
                try {
                    fetchTo(split.video, vF, gen, tick)
                    fetchTo(split.audio!!, aF, gen, tick)
                    val mF = File(d, "$videoId.mp4.part"); temps.add(mF)
                    mux(vF, aF, mF, gen)
                    val f = File(d, "$videoId.mp4")
                    if (f.exists()) f.delete()
                    if (!mF.renameTo(f)) throw Exception("Không lưu được file")
                } catch (e: Exception) {
                    if (cancelGen != gen || muxed == null) throw e
                    // Ghep loi: ha xuong ban hinh+tieng san co (360p/720p)
                    temps.forEach { try { it.delete() } catch (x: Exception) { } }
                    val t = File(d, "$videoId.mp4.part"); temps.add(t)
                    val tot2 = clen(muxed.video).coerceAtLeast(1L)
                    var dn = 0L
                    var lt = -1
                    fetchTo(muxed.video, t, gen) { n ->
                        dn += n
                        job.pct = (dn * 99 / tot2).toInt().coerceIn(0, 99)
                        if (job.pct != lt) { lt = job.pct; changed.value++ }
                    }
                    val f = File(d, "$videoId.mp4")
                    if (f.exists()) f.delete()
                    if (!t.renameTo(f)) throw Exception("Không lưu được file")
                }
                try { vF.delete(); aF.delete() } catch (x: Exception) { }
            } else {
                val url: String
                val ext: String
                if (!audioOnly && muxed != null) {
                    url = muxed.video; ext = "mp4"          // hinh + tieng trong 1 file
                } else if (r.sources.firstOrNull { it.audio != null } != null) {
                    url = r.sources.first { it.audio != null }.audio!!; ext = "m4a"    // chi tieng
                } else {
                    url = r.sources.first().video; ext = "mp4"
                }
                val total = CLEN_RE.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                val t = File(d, "$videoId.$ext.part"); temps.add(t)
                var got = 0L
                var lastTick = 0
                fetchTo(url, t, gen) { n ->
                    got += n
                    if (total > 0) {
                        job.pct = (got * 100 / total).toInt().coerceIn(0, 99)
                        if (job.pct != lastTick) { lastTick = job.pct; changed.value++ }
                    }
                }
                val f = File(d, "$videoId.$ext")
                if (f.exists()) f.delete()
                if (!t.renameTo(f)) throw Exception("Không lưu được file")
            }
            File(d, "$videoId.txt").writeText(
                listOf(title.ifBlank { r.title }, channel.ifBlank { r.channel }, (if (durationSec > 0) durationSec else r.durationSec).toString())
                    .joinToString("\n")
            )
            job.pct = 100
            job.done = true
            changed.value++
            return null
        } catch (e: Throwable) {
            temps.forEach { try { it.delete() } catch (x: Exception) { } }
            val msg = (e.message ?: "lỗi mạng").take(80)
            job.error = msg
            changed.value++
            return msg
        }
    }
}
