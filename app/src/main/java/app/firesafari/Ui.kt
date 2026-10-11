package app.firesafari

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.roundToInt

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

/** Stacks [views] vertically with dialog-style side padding, for AlertDialog.setView. */
fun Context.padded(vararg views: View): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(22), dp(8), dp(22), 0)
    views.forEach { addView(it) }
}

fun Context.toast(text: CharSequence) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text))
    // Android 13+ shows its own confirmation.
    if (Build.VERSION.SDK_INT < 33) toast("Copied")
}

fun Context.shareText(text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    subject?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
    startActivity(Intent.createChooser(send, null))
}
