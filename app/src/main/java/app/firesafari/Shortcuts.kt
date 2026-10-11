package app.firesafari

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import kotlin.concurrent.thread

/** Home-screen shortcuts that open a site straight into its own full-screen task. */
object Shortcuts {
    private const val ICON_SIZE = 432 // 108dp adaptive icon canvas at 4x
    private const val MIN_ICON_SIZE = 96 // anything smaller looks worse than a letter icon

    private val main = Handler(Looper.getMainLooper())

    fun launchIntent(context: Context, site: Site): Intent =
        Intent(context, WebAppActivity::class.java).setAction(Intent.ACTION_VIEW).setData(site.launchUri)

    /** Fetches the site's icon in the background, then asks the launcher to pin a shortcut. */
    fun pin(context: Context, site: Site) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            context.toast("Your launcher doesn't support adding shortcuts")
            return
        }
        val origin = Uri.parse(site.url).let { "${it.scheme}://${it.authority}" }
        // apple-touch-icon is what iOS itself uses for home-screen icons.
        val candidates = listOfNotNull(site.iconUrl, "$origin/apple-touch-icon.png", "$origin/apple-touch-icon-precomposed.png")
        fetchIcon(GeckoWebExecutor(Engine.runtime(context)), candidates) { bitmap ->
            val info = ShortcutInfoCompat.Builder(context, site.id)
                .setShortLabel(site.name)
                .setLongLabel(site.name)
                .setIcon(IconCompat.createWithAdaptiveBitmap(adaptiveIcon(bitmap, site.name)))
                .setIntent(launchIntent(context, site))
                .build()
            ShortcutManagerCompat.requestPinShortcut(context, info, null)
        }
    }

    /** Keeps an already pinned shortcut's label in sync after a rename. */
    fun rename(context: Context, site: Site) {
        val info = ShortcutInfoCompat.Builder(context, site.id)
            .setShortLabel(site.name)
            .setLongLabel(site.name)
            .setIntent(launchIntent(context, site))
            .build()
        runCatching { ShortcutManagerCompat.updateShortcuts(context, listOf(info)) }
    }

    fun disable(context: Context, site: Site) {
        runCatching { ShortcutManagerCompat.disableShortcuts(context, listOf(site.id), "This site was removed") }
    }

    /** Tries each URL in turn; calls [done] on the main thread with the first usable bitmap, or null. */
    private fun fetchIcon(executor: GeckoWebExecutor, urls: List<String>, done: (Bitmap?) -> Unit) {
        if (urls.isEmpty()) return done(null)
        val next = { main.post { fetchIcon(executor, urls.drop(1), done) } }
        executor.fetch(WebRequest.Builder(urls.first()).build()).accept({ response ->
            thread {
                val bitmap = runCatching {
                    response?.takeIf { it.statusCode == 200 }?.body?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                if (bitmap != null && bitmap.width >= MIN_ICON_SIZE) main.post { done(bitmap) } else next()
            }
        }) { next() }
    }

    private fun adaptiveIcon(source: Bitmap?, name: String): Bitmap {
        val out = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (source == null) {
            canvas.drawColor(colorFor(name))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = ICON_SIZE * 0.3f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            val letter = name.trim().firstOrNull()?.uppercase() ?: "?"
            canvas.drawText(letter, ICON_SIZE / 2f, ICON_SIZE / 2f - (paint.descent() + paint.ascent()) / 2, paint)
            return out
        }
        // Opaque icons (like apple-touch-icon) are full-bleed designs: extend their edge color and
        // fill the visible area. Transparent logos go on white inside the safe zone.
        val corner = source.getPixel(0, 0)
        val opaque = Color.alpha(corner) == 255
        canvas.drawColor(if (opaque) corner else Color.WHITE)
        val box = ICON_SIZE * (if (opaque) 76f else 60f) / 108f
        val scale = box / maxOf(source.width, source.height)
        val w = source.width * scale
        val h = source.height * scale
        val left = (ICON_SIZE - w) / 2
        val top = (ICON_SIZE - h) / 2
        canvas.drawBitmap(source, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun colorFor(name: String): Int {
        val palette = longArrayOf(0xFF1F6FEB, 0xFF8250DF, 0xFFBF3989, 0xFFCF222E, 0xFFBC4C00, 0xFF1A7F37, 0xFF0969DA)
            .map { it.toInt() }
        return palette[Math.floorMod(name.hashCode(), palette.size)]
    }
}
