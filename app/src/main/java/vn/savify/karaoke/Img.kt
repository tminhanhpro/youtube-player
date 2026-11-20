package vn.savify.karaoke

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Tai anh thumbnail don gian (OkHttp + cache RAM), khong can thu vien ngoai. */
object Img {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(48 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val pool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())

    fun load(v: ImageView, url: String) {
        v.tag = url
        val hit = cache.get(url)
        if (hit != null) {
            v.setImageBitmap(hit)
            return
        }
        v.setImageDrawable(null)
        pool.execute {
            try {
                val rq = Request.Builder().url(url).build()
                client.newCall(rq).execute().use { r ->
                    val body = r.body
                    if (r.isSuccessful && body != null) {
                        val bmp = BitmapFactory.decodeStream(body.byteStream())
                        if (bmp != null) {
                            cache.put(url, bmp)
                            ui.post { if (v.tag == url) v.setImageBitmap(bmp) }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }
}
