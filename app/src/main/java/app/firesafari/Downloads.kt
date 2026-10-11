package app.firesafari

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.provider.MediaStore
import android.text.format.Formatter
import android.webkit.URLUtil
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import kotlin.concurrent.thread

/** Saves files to the public Downloads folder (no storage permission needed on Android 10+). */
class Downloads(private val activity: Activity) {

    /** A response the engine won't display: attachments, unknown types, blob: downloads. */
    fun offer(response: WebResponse) {
        val disposition = header(response, "Content-Disposition")
        val mime = mimeOf(response)
        val name = URLUtil.guessFileName(response.uri, disposition, mime)
        val size = header(response, "Content-Length")?.toLongOrNull()?.let { Formatter.formatShortFileSize(activity, it) }
        var accepted = false
        AlertDialog.Builder(activity)
            .setTitle("Download file?")
            .setMessage(listOfNotNull(name, size).joinToString("\n"))
            .setPositiveButton("Download") { _, _ ->
                accepted = true
                save(response, name, mime)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!accepted) response.body?.close() }
            .show()
    }

    /** "Save image" from the long-press menu. Fetched without the site's cookies. */
    fun fetch(url: String) {
        GeckoWebExecutor(Engine.runtime(activity)).fetch(WebRequest.Builder(url).build()).accept({ response ->
            if (response == null || response.statusCode !in 200..299) {
                response?.body?.close()
                activity.toast("Couldn't download the image")
            } else {
                save(response, URLUtil.guessFileName(url, header(response, "Content-Disposition"), mimeOf(response)), mimeOf(response))
            }
        }) { activity.toast("Couldn't download the image") }
    }

    private fun save(response: WebResponse, name: String, mime: String) {
        val body = response.body ?: return activity.toast("Download failed")
        val resolver = activity.contentResolver
        val item = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, item)
            ?: return activity.toast("Download failed")
        activity.toast("Downloading $name")
        thread {
            val ok = try {
                body.use { input -> resolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
                true
            } catch (e: IOException) {
                false
            }
            if (ok) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
            activity.runOnUiThread { activity.toast(if (ok) "Saved $name to Downloads" else "Download of $name failed") }
        }
    }

    private fun header(response: WebResponse, name: String) =
        response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun mimeOf(response: WebResponse) =
        header(response, "Content-Type")?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "application/octet-stream"
}
