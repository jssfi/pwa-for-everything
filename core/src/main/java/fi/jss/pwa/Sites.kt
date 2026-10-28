package fi.jss.pwa

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Site(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val fullscreen: Boolean = true,
    val keepScreenOn: Boolean = false,
    val desktopMode: Boolean = false,
    val openExternalInBrowser: Boolean = false,
) {
    val host: String get() = Uri.parse(url).host.orEmpty()

    /** The URI that identifies this site's task in recents and in home screen shortcuts. */
    val launchUri: Uri get() = Uri.parse("pwa://site/$id")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)
        .put("fullscreen", fullscreen)
        .put("keepScreenOn", keepScreenOn)
        .put("desktopMode", desktopMode)
        .put("openExternalInBrowser", openExternalInBrowser)

    companion object {
        fun fromJson(o: JSONObject) = Site(
            id = o.getString("id"),
            name = o.getString("name"),
            url = o.getString("url"),
            fullscreen = o.optBoolean("fullscreen", true),
            keepScreenOn = o.optBoolean("keepScreenOn", false),
            desktopMode = o.optBoolean("desktopMode", false),
            openExternalInBrowser = o.optBoolean("openExternalInBrowser", false),
        )

        /** Adds https:// when the scheme is missing; returns null for anything that is not http(s). */
        fun normalizeUrl(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.contains(' ')) return null
            val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) trimmed else "https://$trimmed"
            val uri = Uri.parse(withScheme)
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
            return withScheme
        }

        fun idFromUri(uri: Uri?): String? =
            uri?.takeIf { it.scheme == "pwa" && it.host == "site" }?.lastPathSegment
    }
}

/**
 * In a generated per-site app, assets/site.json holds the site it was built for (see SiteApkBuilder).
 * It is copied into [SiteStore] on first start and whenever the app is regenerated, so that edits
 * made in the app's own settings survive until the next regeneration.
 */
object StandaloneSite {
    private const val ASSET = "site.json"

    fun load(context: Context): Site? {
        val json = try {
            JSONObject(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            return null
        }
        val site = Site.fromJson(json)
        val stamp = json.optLong("stamp")
        val prefs = context.getSharedPreferences("standalone", Context.MODE_PRIVATE)
        if (prefs.getLong("stamp", -1) != stamp || SiteStore.get(context, site.id) == null) {
            SiteStore.save(context, site)
            prefs.edit().putLong("stamp", stamp).apply()
        }
        return SiteStore.get(context, site.id)
    }
}

object SiteStore {
    private const val PREFS = "sites"
    private const val KEY = "list"

    fun all(context: Context): List<Site> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Site.fromJson(arr.getJSONObject(it)) }
    }

    fun get(context: Context, id: String): Site? = all(context).firstOrNull { it.id == id }

    fun save(context: Context, site: Site) {
        val list = all(context).toMutableList()
        val i = list.indexOfFirst { it.id == site.id }
        if (i >= 0) list[i] = site else list.add(site)
        write(context, list)
    }

    fun delete(context: Context, id: String) {
        write(context, all(context).filterNot { it.id == id })
        SiteIcons.delete(context, id)
    }

    private fun write(context: Context, list: List<Site>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
