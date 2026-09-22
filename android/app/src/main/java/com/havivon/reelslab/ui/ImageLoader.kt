package com.havivon.reelslab.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Thumbnails, cached by reel id rather than by URL: Instagram's CDN links are
 * signed and change on every sync, but the picture behind them does not.
 */
object ImageLoader {

    private val memory = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private const val TARGET_WIDTH = 480

    fun load(view: ImageView, reelPk: String, url: String) {
        view.tag = reelPk
        val cached = memory.get(reelPk)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        if (url.isEmpty()) return

        val context = view.context.applicationContext
        executor.execute {
            val bitmap = fromDisk(context, reelPk) ?: download(context, reelPk, url)
            if (bitmap != null) {
                memory.put(reelPk, bitmap)
                main.post { if (view.tag == reelPk) view.setImageBitmap(bitmap) }
            }
        }
    }

    private fun cacheFile(context: Context, reelPk: String): File {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        return File(dir, "$reelPk.jpg")
    }

    private fun fromDisk(context: Context, reelPk: String): Bitmap? {
        val file = cacheFile(context, reelPk)
        if (!file.exists() || file.length() == 0L) return null
        return decode(file.readBytes())
    }

    private fun download(context: Context, reelPk: String, url: String): Bitmap? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        val bytes = try {
            if (connection.responseCode in 200..299) {
                connection.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
        bytes?.let {
            runCatching { cacheFile(context, reelPk).writeBytes(it) }
            decode(it)
        }
    } catch (error: Exception) {
        null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > TARGET_WIDTH * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
