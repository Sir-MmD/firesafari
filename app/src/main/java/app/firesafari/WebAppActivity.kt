package app.firesafari

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Html
import android.view.View
import android.widget.PopupMenu
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.net.URI

/**
 * One site, shown the way iOS shows a home-screen web app: no browser UI, standalone display mode.
 * Each site runs in its own task and its own storage partition.
 */
class WebAppActivity : ComponentActivity() {
    private lateinit var site: Site
    private lateinit var runtime: GeckoRuntime
    private lateinit var root: View
    private lateinit var geckoView: GeckoView
    private lateinit var progress: ProgressBar
    private lateinit var menuHandle: View

    /** The site's own session first, then popups it opened (window.open, target=_blank), newest last. */
    private val sessions = ArrayList<GeckoSession>()
    private val sessionsWithHistory = HashSet<GeckoSession>()
    private val locations = HashMap<GeckoSession, String>()
    private var fullScreen = false
    private var lastCrash = 0L

    private val prompts = Prompts(this)
    private val permissions = Permissions(this)
    private val downloads = Downloads(this)

    private var onFilesPicked: ((ActivityResult) -> Unit)? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onFilesPicked?.also { onFilesPicked = null }?.invoke(result)
    }

    private var onPermissionsResult: ((Boolean) -> Unit)? = null
    private val permissionRequester = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onPermissionsResult?.also { onPermissionsResult = null }?.invoke(result.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = Sites.fromLaunchUri(this, intent.data) ?: run {
            toast("This site was removed from FireSafari")
            finishAndRemoveTask()
            return
        }
        @Suppress("DEPRECATION")
        setTaskDescription(
            if (Build.VERSION.SDK_INT >= 33) ActivityManager.TaskDescription.Builder().setLabel(site.name).build()
            else ActivityManager.TaskDescription(site.name)
        )

        uploadsDir.deleteRecursively()

        setContentView(R.layout.activity_webapp)
        root = findViewById(R.id.root)
        geckoView = findViewById(R.id.gecko)
        progress = findViewById(R.id.progress)
        menuHandle = findViewById(R.id.menu_handle)
        menuHandle.setOnClickListener { showMenu() }
        setUpWindow()
        onBackPressedDispatcher.addCallback(this) { handleBack() }

        runtime = Engine.runtime(this)
        val session = newSession()
        session.open(runtime)
        sessions += session
        show(session)
        Engine.whenReady(this) { if (session.isOpen) session.loadUri(site.url) }
    }

    override fun onStart() {
        super.onStart()
        sessions.lastOrNull()?.takeIf { it.isOpen }?.setActive(true)
    }

    override fun onStop() {
        super.onStop()
        sessions.lastOrNull()?.takeIf { it.isOpen }?.setActive(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!::geckoView.isInitialized) return
        geckoView.releaseSession()
        sessions.forEach { it.close() }
        sessions.clear()
    }

    // --- Window -------------------------------------------------------------------------------

    private fun setUpWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Keep the page clear of the status bar, cutouts, navigation bar and keyboard, like iOS does.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val types = if (fullScreen) {
                WindowInsetsCompat.Type.ime()
            } else {
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            }
            val bars = insets.getInsets(types)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setFullScreen(on: Boolean) {
        fullScreen = on
        WindowCompat.getInsetsController(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (on) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
        menuHandle.isVisible = !on
        ViewCompat.requestApplyInsets(root)
    }

    // --- Sessions ------------------------------------------------------------------------------

    private fun newSession(): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .contextId(site.contextId)
            // Same value as the engine-wide pref; set here too in case the pref ever fails to apply.
            .userAgentOverride(IosProfile.userAgent(this))
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            // Makes `@media (display-mode: standalone)` match, as for a home-screen web app on iOS.
            .displayMode(GeckoSessionSettings.DISPLAY_MODE_STANDALONE)
            .useTrackingProtection(true)
            .build()
        return GeckoSession(settings).apply {
            contentDelegate = content
            navigationDelegate = navigation
            progressDelegate = progressTracker
            permissionDelegate = permissions
            promptDelegate = prompts
        }
    }

    private fun isTop(session: GeckoSession) = sessions.lastOrNull() === session

    private fun show(session: GeckoSession) {
        geckoView.releaseSession()?.takeIf { it.isOpen }?.setActive(false)
        geckoView.setSession(session)
        if (session.isOpen) session.setActive(true)
        progress.isVisible = false
    }

    private fun closePopup(session: GeckoSession) {
        if (session === sessions.firstOrNull() || !sessions.remove(session)) return
        sessionsWithHistory.remove(session)
        locations.remove(session)
        if (geckoView.session === session) show(sessions.last())
        session.close()
    }

    private fun handleBack() {
        val session = sessions.lastOrNull() ?: return finish()
        when {
            fullScreen -> session.exitFullScreen()
            session in sessionsWithHistory -> session.goBack()
            sessions.size > 1 -> closePopup(session)
            // Like leaving an app: keep the page alive instead of closing it.
            else -> moveTaskToBack(true)
        }
    }

    private fun currentUrl() = sessions.lastOrNull()?.let { locations[it] } ?: site.url

    /** A crashed or killed content process takes the page with it; reload once, but don't loop. */
    private fun recover(session: GeckoSession) {
        if (session !== sessions.firstOrNull()) return closePopup(session)
        val now = SystemClock.elapsedRealtime()
        val crashedRecently = now - lastCrash < 10_000
        lastCrash = now
        geckoView.releaseSession()
        if (!session.isOpen) session.open(runtime)
        geckoView.setSession(session)
        if (crashedRecently) {
            session.loadUri(errorPage(locations[session], "This page keeps crashing."))
        } else {
            session.loadUri(locations[session] ?: site.url)
        }
    }

    // --- Menu ----------------------------------------------------------------------------------

    private fun showMenu() {
        val actions = listOf<Pair<String, () -> Unit>>(
            "Reload" to { sessions.lastOrNull()?.reload() },
            "Go to start page" to {
                while (sessions.size > 1) closePopup(sessions.last())
                sessions.first().loadUri(site.url)
            },
            "Copy link" to { copyToClipboard(currentUrl()) },
            "Share link" to { shareText(currentUrl()) },
            "Add to home screen" to { Shortcuts.pin(this, site) },
            "All sites" to { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            "Close" to { finishAndRemoveTask() },
        )
        PopupMenu(this, menuHandle).apply {
            actions.forEachIndexed { index, (title, _) -> menu.add(0, index, index, title) }
            setOnMenuItemClickListener { item ->
                actions[item.itemId].second()
                true
            }
        }.show()
    }

    private fun showContextMenu(element: ContextElement) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        element.linkUri?.let { link ->
            actions += "Copy link" to { copyToClipboard(link) }
            actions += "Share link" to { shareText(link) }
            actions += "Open in another app" to { openInAnotherApp(Uri.parse(link)) }
        }
        element.srcUri?.takeIf { element.type == ContextElement.TYPE_IMAGE && it.startsWith("http") }?.let { src ->
            actions += "Save image" to { downloads.fetch(src) }
            actions += "Copy image address" to { copyToClipboard(src) }
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(element.linkUri ?: element.title ?: element.altText)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    // --- Links that leave the web ----------------------------------------------------------------

    private fun openInAnotherApp(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(Intent.createChooser(view, null))
        } catch (e: ActivityNotFoundException) {
            toast("No app can open this link")
        }
    }

    /** tel:, mailto:, sms: and the like. Only when the user tapped something, never on page load. */
    private fun openExternalScheme(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            toast("No app can open this link")
        }
    }

    /** Android intent: links exist to launch native apps. Use their web fallback, or ignore them. */
    private fun intentFallback(uri: String): String? = runCatching {
        Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
    }.getOrNull()?.takeIf { Uri.parse(it).scheme in setOf("http", "https") }

    // --- Engine callbacks --------------------------------------------------------------------------

    private val progressTracker = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (!isTop(session)) return
            progress.progress = 0
            progress.isVisible = true
        }

        override fun onProgressChange(session: GeckoSession, value: Int) {
            if (!isTop(session)) return
            progress.progress = value
            progress.isVisible = value in 1..99
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (isTop(session)) progress.isVisible = false
        }
    }

    private val navigation = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            if (url != null) locations[session] = url
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            if (canGoBack) sessionsWithHistory += session else sessionsWithHistory -= session
        }

        override fun onLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny>? {
            val uri = Uri.parse(request.uri)
            return when (uri.scheme?.lowercase()) {
                "http", "https", "about", "data", "blob", "javascript" -> null
                "file", "content", "resource", "chrome", "moz-extension" -> GeckoResult.deny()
                "intent" -> {
                    intentFallback(request.uri)?.let { fallback -> geckoView.post { session.loadUri(fallback) } }
                    GeckoResult.deny()
                }
                else -> {
                    if (request.hasUserGesture) openExternalScheme(uri)
                    GeckoResult.deny()
                }
            }
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
            // Shown on top like iOS's in-app browser sheet; back closes it. GeckoView opens it itself.
            val popup = newSession()
            sessions += popup
            show(popup)
            return GeckoResult.fromValue(popup)
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
            // A working page is on screen: keep it, like a native app would. (An error page would also
            // break back navigation: GeckoView doesn't give error pages a history entry.)
            if (locations[session]?.startsWith("http") == true) {
                toast(describe(error))
                return null
            }
            return GeckoResult.fromValue(errorPage(uri, describe(error)))
        }
    }

    private val content = object : GeckoSession.ContentDelegate {
        override fun onCloseRequest(session: GeckoSession) = closePopup(session)

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) = setFullScreen(fullScreen)

        override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContextElement) =
            showContextMenu(element)

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) = downloads.offer(response)

        override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) {
            val icon = bestIcon(manifest, locations[session] ?: site.url) ?: return
            if (icon == site.iconUrl) return
            site = site.copy(iconUrl = icon)
            Sites.get(this@WebAppActivity, site.id)?.let { Sites.put(this@WebAppActivity, it.copy(iconUrl = icon)) }
        }

        override fun onCrash(session: GeckoSession) = recover(session)

        override fun onKill(session: GeckoSession) = recover(session)
    }

    // --- Used by Prompts and Permissions --------------------------------------------------------

    fun siteHost(): String = Uri.parse(currentUrl()).host ?: site.name

    fun pickFiles(intent: Intent, onResult: (ActivityResult) -> Unit) {
        // A page can only have one picker open; settle any earlier one as cancelled.
        onFilesPicked?.invoke(ActivityResult(RESULT_CANCELED, null))
        onFilesPicked = onResult
        try {
            filePicker.launch(intent)
        } catch (e: ActivityNotFoundException) {
            onFilesPicked = null
            onResult(ActivityResult(RESULT_CANCELED, null))
        }
    }

    /**
     * Where files picked for upload are copied: the engine can only read files it can open by path.
     * One folder per site, emptied when the site opens, so sites never see each other's uploads.
     */
    val uploadsDir: File get() = File(cacheDir, "uploads/${site.id}")

    /** A file the camera app can write a photo into, for image upload fields. */
    fun newCaptureFile(): Pair<File, Uri>? {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return null
        val file = File(uploadsDir.apply { mkdirs() }, "photo-${System.currentTimeMillis()}.jpg")
        return file to FileProvider.getUriForFile(this, "$packageName.files", file)
    }

    fun requestAndroidPermissions(permissions: Array<out String>, onResult: (Boolean) -> Unit) {
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return onResult(true)
        onPermissionsResult?.invoke(false)
        onPermissionsResult = onResult
        permissionRequester.launch(missing.toTypedArray())
    }

    private companion object {
        fun describe(error: WebRequestError): String = when (error.code) {
            WebRequestError.ERROR_UNKNOWN_HOST -> "The server could not be found."
            WebRequestError.ERROR_CONNECTION_REFUSED -> "The server refused the connection."
            WebRequestError.ERROR_NET_TIMEOUT -> "The server took too long to respond."
            WebRequestError.ERROR_OFFLINE, WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT ->
                "The connection was lost. Check your internet connection."
            WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_SECURITY_SSL, WebRequestError.ERROR_BAD_HSTS_CERT ->
                "The site's security certificate is not valid, so the connection was stopped."
            WebRequestError.ERROR_REDIRECT_LOOP -> "The site keeps redirecting to itself. Clearing its data may help."
            WebRequestError.ERROR_PORT_BLOCKED -> "This address uses a network port the engine blocks for safety."
            WebRequestError.ERROR_MALFORMED_URI -> "This web address is not valid."
            WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI, WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI,
            WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI, WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI ->
                "This site is reported as deceptive or harmful."
            else -> "The page could not be loaded (error 0x${error.code.toString(16)})."
        }

        fun errorPage(uri: String?, message: String): String {
            val retry = uri?.takeIf { it.startsWith("http") }
                ?.let { """<p><a href="${Html.escapeHtml(it)}">Try again</a></p>""" }
                .orEmpty()
            val html = """<!doctype html><meta name="viewport" content="width=device-width">
                <style>body{font:17px system-ui,sans-serif;margin:40px 24px;color:#333}
                @media (prefers-color-scheme:dark){body{background:#000;color:#ddd}a{color:#6af}}</style>
                <h2>Can't open this page</h2><p>${Html.escapeHtml(message)}</p>$retry"""
            return "data:text/html;charset=utf-8," + Uri.encode(html)
        }

        /** Largest bitmap icon in a web app manifest (SVG can't be used for launcher icons). */
        fun bestIcon(manifest: JSONObject, pageUrl: String): String? {
            val icons = manifest.optJSONArray("icons") ?: return null
            var best: String? = null
            var bestSize = -1
            for (i in 0 until icons.length()) {
                val icon = icons.optJSONObject(i) ?: continue
                val src = icon.optString("src").ifEmpty { null } ?: continue
                if (icon.optString("type").contains("svg") || src.substringBefore('?').endsWith(".svg")) continue
                // GeckoView passes the processed manifest, where sizes is a list ("192x192" entries).
                val sizes = icon.optJSONArray("sizes")?.let { list -> (0 until list.length()).map { list.optString(it) } }
                    ?: icon.optString("sizes").split(' ')
                val size = sizes.mapNotNull { it.substringBefore('x').toIntOrNull() }.maxOrNull() ?: 0
                if (size > bestSize) {
                    best = src
                    bestSize = size
                }
            }
            return best?.let { runCatching { URI(pageUrl).resolve(it).toString() }.getOrNull() }
        }
    }
}
