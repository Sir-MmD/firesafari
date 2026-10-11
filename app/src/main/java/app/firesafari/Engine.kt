package app.firesafari

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import java.io.File

/** Owns the process-wide GeckoRuntime, created on first use with the iOS profile applied. */
object Engine {
    private const val TAG = "Engine"
    private const val EXTENSION_URI = "resource://android/assets/ios-profile/"
    private const val EXTENSION_ID = "ios-profile@firesafari.app"

    private var runtime: GeckoRuntime? = null
    private lateinit var extension: GeckoResult<WebExtension>

    fun runtime(context: Context): GeckoRuntime {
        runtime?.let { return it }
        val app = context.applicationContext
        val settings = GeckoRuntimeSettings.Builder()
            // The only way to set arbitrary Gecko prefs from a release build.
            .configFilePath(writeConfig(app).absolutePath)
            .consoleOutput(BuildConfig.DEBUG)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .aboutConfigEnabled(false)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                    .build()
            )
            .build()
        return GeckoRuntime.create(app, settings).also {
            runtime = it
            extension = it.webExtensionController.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
        }
    }

    /** Runs [block] once the page-side part of the profile is installed, so no page loads without it. */
    fun whenReady(context: Context, block: () -> Unit) {
        runtime(context)
        extension.accept({ block() }) { e ->
            Log.e(TAG, "ios-profile extension failed to install", e)
            Toast.makeText(context, "Page-level iPhone profile failed to load", Toast.LENGTH_LONG).show()
            block()
        }
    }

    private fun writeConfig(context: Context): File {
        val prefs = IosProfile.geckoPrefs(IosProfile.userAgent(context))
        val yaml = buildString {
            appendLine("prefs:")
            for ((key, value) in prefs) {
                appendLine("  ${quote(key)}: ${if (value is String) quote(value) else value}")
            }
        }
        return File(context.filesDir, "gecko-config.yaml").apply { writeText(yaml) }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
