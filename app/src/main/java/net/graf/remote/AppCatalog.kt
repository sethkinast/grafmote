package net.graf.remote

import android.content.Context
import org.json.JSONArray

/**
 * Curated app catalog (round 21): fallback for TVs whose firmware never
 * answers the ed.installedApp.get WebSocket query (round 20).
 *
 * The JSON lives vendored at res/raw/app_catalog.json (~7KB) — no runtime
 * dependency on any website. Each entry carries fallback IDs in launch
 * order plus a Samsung CDN PNG icon URL. Logo fetching is a graceful,
 * best-effort enhancement (see IconLoader); tiles work fully offline.
 */
data class CatalogApp(val name: String, val ids: List<String>, val icon: String) {
    /** Primary ID: the first fallback ID, used for dedup and display. */
    val primaryId: String get() = ids.firstOrNull() ?: ""
}

object AppCatalog {
    @Volatile private var cached: List<CatalogApp>? = null

    /** Parsed catalog, cached in memory. Cheap (~7KB); never throws. */
    fun load(ctx: Context): List<CatalogApp> {
        cached?.let { return it }
        val parsed = try {
            val text = ctx.resources.openRawResource(R.raw.app_catalog)
                .bufferedReader().use { it.readText() }
            parse(text)
        } catch (_: Exception) {
            emptyList()
        }
        cached = parsed
        return parsed
    }

    /** Parse + validate raw catalog JSON. Invalid entries are skipped. */
    fun parse(text: String): List<CatalogApp> {
        val out = mutableListOf<CatalogApp>()
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            return emptyList()
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            val icon = o.optString("icon", "").trim()
            val ids = mutableListOf<String>()
            val rawIds = o.optJSONArray("ids")
            if (rawIds != null) {
                for (j in 0 until rawIds.length()) {
                    val id = rawIds.optString(j, "").trim()
                    if (id.isNotEmpty() && !ids.contains(id)) ids.add(id)
                }
            }
            if (name.isEmpty() || ids.isEmpty()) continue
            out.add(CatalogApp(name, ids.toList(), icon))
        }
        return out.sortedBy { it.name.lowercase() }
    }
}
