package app.firesafari

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Site(
    val id: String,
    val name: String,
    val url: String,
    /** Best icon from the site's web app manifest, remembered for home-screen shortcuts. */
    val iconUrl: String? = null,
) {
    /** Storage partition: cookies and site data are never shared between sites. */
    val contextId get() = "site-$id"

    /** Unique per site, so each site gets its own task in recents (documentLaunchMode). */
    val launchUri: Uri get() = Uri.parse("firesafari://site/$id")

    companion object {
        fun create(name: String, url: String) = Site(UUID.randomUUID().toString(), name, url)
    }
}

object Sites {
    private const val PREFS = "sites"
    private const val KEY_LIST = "list"

    fun all(context: Context): List<Site> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Site(o.getString("id"), o.getString("name"), o.getString("url"), o.optString("iconUrl").ifEmpty { null })
        }
    }

    fun get(context: Context, id: String) = all(context).firstOrNull { it.id == id }

    fun fromLaunchUri(context: Context, uri: Uri?): Site? =
        if (uri?.scheme == "firesafari" && uri.host == "site") uri.lastPathSegment?.let { get(context, it) } else null

    /** Adds the site, or replaces the one with the same id. */
    fun put(context: Context, site: Site) {
        val list = all(context).toMutableList()
        val index = list.indexOfFirst { it.id == site.id }
        if (index >= 0) list[index] = site else list += site
        save(context, list)
    }

    fun remove(context: Context, id: String) = save(context, all(context).filterNot { it.id == id })

    /** Accepts "example.com" or a full URL; returns null if it isn't a usable web address. */
    fun normalizeUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) text else "https://$text"
        val uri = Uri.parse(withScheme)
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
        return withScheme
    }

    private fun save(context: Context, list: List<Site>) {
        val array = JSONArray()
        for (site in list) {
            array.put(
                JSONObject()
                    .put("id", site.id)
                    .put("name", site.name)
                    .put("url", site.url)
                    .put("iconUrl", site.iconUrl ?: "")
            )
        }
        prefs(context).edit { putString(KEY_LIST, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
