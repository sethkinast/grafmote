package net.cogdev.grafmote

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// NOTE: tokens are persisted in plain SharedPreferences deliberately.
// The token is LAN-scoped and low sensitivity (it only works from the local
// network against the user's own TV). Using EncryptedSharedPreferences would
// pull in androidx.security -> androidx.startup, which registers a
// ContentProvider that runs on every cold start — incompatible with the
// <500ms cold-start budget. No custom Application class or ContentProviders
// exist in this app for the same reason.

data class Tv(
    val id: String,
    var nick: String,
    var host: String,
    var token: String = "",
    var tokenSupport: Boolean = false,
    var wifiMac: String = "",
    var haptic: Boolean = true
)

/**
 * App-launch button (round 21): [appId] is the primary launch ID;
 * [appIds] holds every fallback ID in launch order (catalog entries carry
 * several — e.g. regional variants — and launch tries each until one
 * succeeds). Buttons stored before round 21 only have [appId], which loads
 * as a single-element [appIds]. [iconUrl] is a nullable Samsung CDN PNG;
 * null/blank means a text-only tile.
 */
data class AppButton(
    val id: String,
    var label: String,
    var appId: String,
    var appIds: List<String> = listOf(appId),
    var iconUrl: String? = null
) {
    /** All IDs to try, primary first; never empty. */
    fun launchIds(): List<String> {
        val ordered = mutableListOf<String>()
        if (appId.isNotBlank()) ordered.add(appId)
        for (alt in appIds) {
            if (alt.isNotBlank() && !ordered.contains(alt)) ordered.add(alt)
        }
        return ordered.ifEmpty { listOf(appId) }
    }
}

class TvStore(ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("graf_remote", Context.MODE_PRIVATE)

    /**
     * Serializes every whole-list TV write (round 41): updateToken/updateMeta
     * run load-modify-save from background threads while Settings' saveTvs
     * runs on the main thread — without a common lock a background save can
     * land between Settings' load and save and be clobbered (or vice versa).
     * All three hold [tvsLock] across their load-modify-save/save so the
     * writes are atomic with respect to each other.
     */
    private val tvsLock = Any()

    // ---- TVs ----

    fun loadTvs(): MutableList<Tv> {
        val out = mutableListOf<Tv>()
        val arr = JSONArray(prefs.getString("tvs", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Tv(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    nick = o.optString("nick", ""),
                    host = o.optString("host", ""),
                    token = o.optString("token", ""),
                    tokenSupport = o.optBoolean("tokenSupport", false),
                    wifiMac = o.optString("wifiMac", ""),
                    haptic = o.optBoolean("haptic", true)
                )
            )
        }
        return out
    }

    fun saveTvs(tvs: List<Tv>) {
        synchronized(tvsLock) {
            val arr = JSONArray()
            for (t in tvs) {
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("nick", t.nick)
                        .put("host", t.host)
                        .put("token", t.token)
                        .put("tokenSupport", t.tokenSupport)
                        .put("wifiMac", t.wifiMac)
                        .put("haptic", t.haptic)
                )
            }
            prefs.edit().putString("tvs", arr.toString()).apply()
        }
    }

    fun updateToken(tvId: String, token: String) {
        synchronized(tvsLock) {
            val tvs = loadTvs()
            if (tvs.any { it.id == tvId && it.token != token }) {
                tvs.first { it.id == tvId }.token = token
                val arr = JSONArray()
                for (t in tvs) {
                    arr.put(
                        JSONObject()
                            .put("id", t.id)
                            .put("nick", t.nick)
                            .put("host", t.host)
                            .put("token", t.token)
                            .put("tokenSupport", t.tokenSupport)
                            .put("wifiMac", t.wifiMac)
                            .put("haptic", t.haptic)
                    )
                }
                prefs.edit().putString("tvs", arr.toString()).apply()
            }
        }
    }

    fun updateMeta(tvId: String, tokenSupport: Boolean, wifiMac: String) {
        synchronized(tvsLock) {
            val tvs = loadTvs()
            val tv = tvs.firstOrNull { it.id == tvId } ?: return
            if (tv.tokenSupport != tokenSupport || tv.wifiMac != wifiMac) {
                tv.tokenSupport = tokenSupport
                tv.wifiMac = wifiMac
                val arr = JSONArray()
                for (t in tvs) {
                    arr.put(
                        JSONObject()
                            .put("id", t.id)
                            .put("nick", t.nick)
                            .put("host", t.host)
                            .put("token", t.token)
                            .put("tokenSupport", t.tokenSupport)
                            .put("wifiMac", t.wifiMac)
                            .put("haptic", t.haptic)
                    )
                }
                prefs.edit().putString("tvs", arr.toString()).apply()
            }
        }
    }

    fun lastTvId(): String? = prefs.getString("lastTv", null)

    /** Per-TV haptic toggle (default ON for TVs stored before this field). */
    fun isHapticEnabled(tvId: String): Boolean =
        loadTvs().firstOrNull { it.id == tvId }?.haptic ?: true

    fun setLastTvId(id: String) {
        prefs.edit().putString("lastTv", id).apply()
    }

    // ---- Per-TV winning app-ID memory (round 22) ----
    //
    // Round 21 launches try every fallback ID until one answers HTTP 200,
    // but a 2021 TV and a 2024 TV can need different IDs for the same app.
    // So the ID that last worked is remembered PER (TV, button): tvId ->
    // (appButtonId -> winning Samsung appId), persisted as one JSON string
    // alongside the other prefs. Winners are overwritten on every success,
    // so a stale winner simply falls through to the fallbacks and the new
    // winner replaces it.

    fun getWinningAppId(tvId: String, buttonId: String): String? {
        if (tvId.isBlank() || buttonId.isBlank()) return null
        return try {
            val root = JSONObject(prefs.getString("app_winners", "{}"))
            root.optJSONObject(tvId)?.optString(buttonId, "").orEmpty()
                .trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    fun setWinningAppId(tvId: String, buttonId: String, samsungId: String) {
        if (tvId.isBlank() || buttonId.isBlank() || samsungId.isBlank()) return
        try {
            val root = try {
                JSONObject(prefs.getString("app_winners", "{}"))
            } catch (_: Exception) {
                JSONObject()
            }
            var perTv = root.optJSONObject(tvId)
            if (perTv == null) {
                perTv = JSONObject()
                root.put(tvId, perTv)
            }
            perTv.put(buttonId, samsungId)
            prefs.edit().putString("app_winners", root.toString()).apply()
        } catch (_: Exception) {
        }
    }

    // ---- App-launch buttons (global list, v1) ----

    fun loadApps(): MutableList<AppButton> {
        if (!prefs.contains("apps")) {
            val defaults = defaultApps()
            saveApps(defaults)
            return defaults.toMutableList()
        }
        val out = mutableListOf<AppButton>()
        val arr = JSONArray(prefs.getString("apps", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val appId = o.optString("appId", "")
            val ids = mutableListOf<String>()
            val rawIds = o.optJSONArray("appIds")
            if (rawIds != null) {
                for (j in 0 until rawIds.length()) {
                    val alt = rawIds.optString(j, "").trim()
                    if (alt.isNotEmpty() && !ids.contains(alt)) ids.add(alt)
                }
            }
            if (ids.isEmpty() && appId.isNotEmpty()) ids.add(appId)
            val icon = o.optString("iconUrl", "").trim().ifEmpty { null }
            out.add(
                AppButton(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    label = o.optString("label", ""),
                    appId = appId,
                    appIds = ids.toList(),
                    iconUrl = icon
                )
            )
        }
        return out
    }

    fun saveApps(apps: List<AppButton>) {
        val arr = JSONArray()
        for (a in apps) {
            val o = JSONObject().put("id", a.id).put("label", a.label).put("appId", a.appId)
            o.put("appIds", JSONArray(a.launchIds()))
            if (!a.iconUrl.isNullOrBlank()) o.put("iconUrl", a.iconUrl)
            arr.put(o)
        }
        prefs.edit().putString("apps", arr.toString()).apply()
    }

    private fun defaultApps(): List<AppButton> = listOf(
        // Round 40: Seth's four verified defaults, in order. Jellyfin ID
        // from the official jellyfin-tizen config.xml; the rest verified
        // working on the target TV. Fresh installs only (loadApps seeds
        // when no "apps" pref exists — never clobbers user config).
        // Logos resolve via the round-30 catalog enrich on first sight;
        // an ID with no catalog match keeps the letter-tile fallback.
        AppButton(UUID.randomUUID().toString(), "Jellyfin", "AprZAARz4r.Jellyfin"),
        AppButton(UUID.randomUUID().toString(), "Disney+", "3201901017640"),
        AppButton(UUID.randomUUID().toString(), "PBS Kids", "Rk72oKFsRS.OtterKidsProd"),
        AppButton(UUID.randomUUID().toString(), "Hulu", "3201601007625")
    )
}
