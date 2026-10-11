package app.chrosafari

import android.content.Context
import androidx.core.content.edit

/**
 * What the engine reports about itself. These become Gecko prefs, so they apply to every
 * request, page, frame, worker and service worker, not only to the visible page.
 *
 * Page-visible details that prefs cannot change live in assets/ios-profile/spoof.js.
 */
object IosProfile {
    /**
     * Safari on iOS 26 and later freezes the OS token at 18_6; only Version/ tracks the release.
     * Update it from a real iPhone when it gets old (Settings in the app's menu).
     */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.3 Mobile/15E148 Safari/604.1"

    private const val PREFS = "settings"
    private const val KEY_USER_AGENT = "user_agent"

    fun userAgent(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER_AGENT, null)
            ?: DEFAULT_USER_AGENT

    /** Pass null to go back to [DEFAULT_USER_AGENT]. Takes effect after the app restarts. */
    fun setUserAgent(context: Context, userAgent: String?) {
        // Written synchronously: the caller restarts the process right after.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            if (userAgent.isNullOrBlank()) remove(KEY_USER_AGENT) else putString(KEY_USER_AGENT, userAgent.trim())
        }
    }

    fun geckoPrefs(userAgent: String): Map<String, Any> = linkedMapOf(
        "general.useragent.override" to userAgent,
        // Safari's appVersion is its user agent minus the leading "Mozilla/".
        "general.appversion.override" to userAgent.removePrefix("Mozilla/"),
        "general.platform.override" to "iPhone",
        // Android-only APIs that Safari on iPhone does not have.
        "dom.vibrator.enabled" to false,
        "dom.netinfo.enabled" to false,
        // Stop background pings to Mozilla's connectivity check servers.
        "network.captive-portal-service.enabled" to false,
        "network.connectivity-service.enabled" to false,
    )
}
