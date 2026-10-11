package net.graf.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Minimal logo loader (round 21): deliberately hand-rolled instead of Coil
 * or another image framework — the icons are ~198px PNGs and this is ~100
 * lines with zero new dependencies, no ContentProviders, and no cold-start
 * cost (disk + executor are created lazily on first use, off the UI thread).
 *
 * Layers: small in-memory LRU -> disk cache under cacheDir/app_icons ->
 * network (HttpURLConnection, short timeouts). The Samsung CDN icon URL is
 * the ONLY non-LAN traffic the app ever makes, and it is purely best-effort:
 * offline, slow, or missing logos leave the ImageView GONE, so the tile
 * falls back to today's text-only look, unchanged and fully tappable.
 */
object IconLoader {
    private val main = Handler(Looper.getMainLooper())

    private val mem = object : LruCache<String, Bitmap>(32) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val daemonThreads = ThreadFactory { r ->
        Thread(r, "icon-loader").apply { isDaemon = true }
    }
    private val exec by lazy { Executors.newFixedThreadPool(2, daemonThreads) }

    /**
     * Load [url] into [view]. Empty/blank URLs hide the view immediately.
     * The view's tag is set to the URL; a recycled view whose tag changed
     * before the fetch finished never gets a stale bitmap.
     */
    fun loadInto(view: ImageView, url: String) {
        if (url.isBlank()) {
            view.tag = null
            view.setImageDrawable(null)
            view.visibility = View.GONE
            return
        }
        view.tag = url
        try {
            mem.get(url)?.let { bmp ->
                view.setImageBitmap(bmp)
                view.visibility = View.VISIBLE
                return
            }
        } catch (_: Exception) {
        }
        // Text-only until the logo arrives (or forever, when offline).
        view.setImageDrawable(null)
        view.visibility = View.GONE
        exec.execute { fetchInBackground(view, url) }
    }

    private fun fetchInBackground(view: ImageView, url: String) {
        try {
            val ctx = view.context.applicationContext
            val file = cacheFile(ctx, url)
            var bmp: Bitmap? = decodeFile(file)
            if (bmp == null) {
                if (downloadTo(url, file)) bmp = decodeFile(file)
            }
            if (bmp == null) {
                file.delete()
                return
            }
            try {
                mem.put(url, bmp)
            } catch (_: Exception) {
            }
            val ready = bmp
            main.post {
                if (view.tag == url) {
                    view.setImageBitmap(ready)
                    view.visibility = View.VISIBLE
                }
            }
        } catch (_: Exception) {
            // Best-effort: the text tile underneath is the fallback.
        }
    }

    private fun cacheDir(ctx: Context): File =
        File(ctx.cacheDir, "app_icons").apply { mkdirs() }

    private fun cacheFile(ctx: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
        val hex = digest.digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir(ctx), "$hex.png")
    }

    private fun decodeFile(file: File): Bitmap? {
        try {
            if (!file.exists() || file.length() == 0L) return null
            // Icons are ~198px; downsample anything unexpectedly large so a
            // rogue CDN asset can never blow the memory budget.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            return null
        }
    }

    private fun downloadTo(url: String, dest: File): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "image/*")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parent, dest.name + ".tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                return false
            }
            return tmp.renameTo(dest)
        } catch (_: Exception) {
            return false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
