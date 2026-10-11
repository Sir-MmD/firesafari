package app.firesafari

import android.app.AlertDialog
import android.net.Uri
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource

/** Site permission requests. Notifications are always refused; this app doesn't deliver them. */
class Permissions(private val activity: WebAppActivity) : PermissionDelegate {

    /** The engine needs an Android runtime permission (camera, microphone, location) first. */
    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: PermissionDelegate.Callback,
    ) {
        if (permissions.isNullOrEmpty()) return callback.grant()
        activity.requestAndroidPermissions(permissions) { granted -> if (granted) callback.grant() else callback.reject() }
    }

    override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int> {
        val host = Uri.parse(perm.uri).host ?: activity.siteHost()
        return when (perm.permission) {
            PermissionDelegate.PERMISSION_GEOLOCATION -> ask("$host wants to know your location")
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> ask("$host wants to play protected (DRM) media")
            PermissionDelegate.PERMISSION_STORAGE_ACCESS ->
                ask("${perm.thirdPartyOrigin ?: "An embedded site"} wants to use its cookies on $host")
            PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS, PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS ->
                ask("$host wants to connect to devices on your local network")
            // Muted autoplay and durable storage are what an installed web app gets on iOS.
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE, PermissionDelegate.PERMISSION_PERSISTENT_STORAGE ->
                GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
            // Notifications, sound autoplay without a tap (iOS blocks it too), VR and anything new.
            else -> GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        }
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out MediaSource>?,
        audio: Array<out MediaSource>?,
        callback: PermissionDelegate.MediaCallback,
    ) {
        val cameras = video.orEmpty().filter { it.source == MediaSource.SOURCE_CAMERA }
        val microphone = audio.orEmpty().firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE }
        if (cameras.isEmpty() && microphone == null) return callback.reject()

        val host = Uri.parse(uri).host ?: activity.siteHost()
        val what = listOfNotNull("camera".takeIf { cameras.isNotEmpty() }, "microphone".takeIf { microphone != null })
        var camera = cameras.firstOrNull()
        var decided = false
        val builder = AlertDialog.Builder(activity)
            .setTitle("$host wants to use your ${what.joinToString(" and ")}")
            .setPositiveButton("Allow") { _, _ ->
                decided = true
                callback.grant(camera, microphone)
            }
            .setNegativeButton("Block", null)
            .setOnDismissListener { if (!decided) callback.reject() }
        if (cameras.size > 1) {
            val names = cameras.mapIndexed { i, c -> c.name?.takeIf { it.isNotBlank() } ?: "Camera ${i + 1}" }
            builder.setSingleChoiceItems(names.toTypedArray(), 0) { _, which -> camera = cameras[which] }
        }
        builder.show()
    }

    private fun ask(question: String): GeckoResult<Int> {
        val result = GeckoResult<Int>()
        var value = ContentPermission.VALUE_DENY
        AlertDialog.Builder(activity)
            .setTitle(question)
            .setPositiveButton("Allow") { _, _ -> value = ContentPermission.VALUE_ALLOW }
            .setNegativeButton("Block", null)
            .setOnDismissListener { result.complete(value) }
            .show()
        return result
    }
}
