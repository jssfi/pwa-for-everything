package fi.jss.pwa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Site icons: the best icon seen for each site is cached as a PNG in filesDir/icons/<id>.png. */
object SiteIcons {
    private fun file(context: Context, id: String) = File(context.filesDir, "icons/$id.png")

    fun load(context: Context, id: String): Bitmap? =
        file(context, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Stores [bitmap] unless an icon at least as large is already cached. Returns true when stored. */
    @Synchronized
    fun saveIfBetter(context: Context, id: String, bitmap: Bitmap): Boolean {
        val f = file(context, id)
        if (f.exists()) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, opts)
            if (opts.outWidth >= bitmap.width) return false
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(f)
        return true
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
    }

    /** Downloads an image. Call off the main thread. */
    fun download(url: String, userAgent: String?, cookies: String?): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        userAgent?.let { conn.setRequestProperty("User-Agent", it) }
        cookies?.let { conn.setRequestProperty("Cookie", it) }
        conn.inputStream.use { BitmapFactory.decodeStream(it) }.also { conn.disconnect() }
    } catch (e: Exception) {
        null
    }

    /** The icon shown in the app list: the cached site icon, or a letter tile. */
    fun display(context: Context, site: Site, sizePx: Int): Bitmap {
        val icon = load(context, site.id) ?: return letterTile(site, sizePx)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(icon, null, Rect(0, 0, sizePx, sizePx), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** An adaptive icon for home screen shortcuts. */
    fun adaptive(context: Context, site: Site): Icon = Icon.createWithAdaptiveBitmap(adaptiveBitmap(context, site))

    /**
     * A full-bleed 432px (108dp at xxxhdpi) adaptive icon layer: the site icon on white, or a letter tile.
     * Only the inner 66dp of an adaptive icon is always visible, so the icon is kept inside that area.
     */
    fun adaptiveBitmap(context: Context, site: Site): Bitmap {
        val size = 432
        val icon = load(context, site.id)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (icon == null) {
            canvas.drawColor(colorFor(site))
            drawLetter(canvas, site, size / 2f, size / 2f, size * 0.30f)
        } else {
            canvas.drawColor(Color.WHITE)
            val inner = size * 0.58f
            val off = (size - inner) / 2
            canvas.drawBitmap(icon, null, RectF(off, off, off + inner, off + inner), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return bmp
    }

    /** Small plain bitmap for ActivityManager.TaskDescription. */
    fun taskIcon(context: Context, site: Site): Bitmap = display(context, site, 192)

    private fun letterTile(site: Site, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFor(site) }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * 0.22f, size * 0.22f, paint)
        drawLetter(canvas, site, size / 2f, size / 2f, size * 0.5f)
        return bmp
    }

    private fun drawLetter(canvas: Canvas, site: Site, cx: Float, cy: Float, textSize: Float) {
        val letter = (site.name.firstOrNull { it.isLetterOrDigit() } ?: site.host.firstOrNull() ?: '?').uppercase()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect().also { paint.getTextBounds(letter, 0, letter.length, it) }
        canvas.drawText(letter, cx, cy + bounds.height() / 2f, paint)
    }

    private val palette = intArrayOf(
        0xFF059669.toInt(), 0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(),
        0xFFEA580C.toInt(), 0xFF0891B2.toInt(), 0xFF4F46E5.toInt(), 0xFFB45309.toInt(),
    )

    private fun colorFor(site: Site) = palette[Math.floorMod(site.host.ifEmpty { site.name }.hashCode(), palette.size)]
}
