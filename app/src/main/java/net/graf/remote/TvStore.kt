package net.graf.remote

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

data class AppButton(val id: String, var label: String, var appId: String)

class TvStore(ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("graf_remote", Context.MODE_PRIVATE)

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

    fun updateToken(tvId: String, token: String) {
        val tvs = loadTvs()
        if (tvs.any { it.id == tvId && it.token != token }) {
            tvs.first { it.id == tvId }.token = token
            saveTvs(tvs)
        }
    }

    fun updateMeta(tvId: String, tokenSupport: Boolean, wifiMac: String) {
        val tvs = loadTvs()
        val tv = tvs.firstOrNull { it.id == tvId } ?: return
        if (tv.tokenSupport != tokenSupport || tv.wifiMac != wifiMac) {
            tv.tokenSupport = tokenSupport
            tv.wifiMac = wifiMac
            saveTvs(tvs)
        }
    }

    fun lastTvId(): String? = prefs.getString("lastTv", null)

    /** Per-TV haptic toggle (default ON for TVs stored before this field). */
    fun isHapticEnabled(tvId: String): Boolean =
        loadTvs().firstOrNull { it.id == tvId }?.haptic ?: true

    fun setLastTvId(id: String) {
        prefs.edit().putString("lastTv", id).apply()
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
            out.add(
                AppButton(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    label = o.optString("label", ""),
                    appId = o.optString("appId", "")
                )
            )
        }
        return out
    }

    fun saveApps(apps: List<AppButton>) {
        val arr = JSONArray()
        for (a in apps) {
            arr.put(JSONObject().put("id", a.id).put("label", a.label).put("appId", a.appId))
        }
        prefs.edit().putString("apps", arr.toString()).apply()
    }

    private fun defaultApps(): List<AppButton> = listOf(
        // IDs verified: Jellyfin from the official jellyfin-tizen config.xml
        // (tizen:application id); the rest from the Hubitat driver appIdList /
        // translation table. If any stop working, confirm against the live TV:
        // http://<tv-ip>:8001/api/v2/applications/list
        AppButton(UUID.randomUUID().toString(), "Jellyfin", "AprZAARz4r.Jellyfin"),
        AppButton(UUID.randomUUID().toString(), "Disney+", "MCmYXNxgcu.DisneyPlus"),
        AppButton(UUID.randomUUID().toString(), "Netflix", "org.tizen.netflix-app"),
        AppButton(UUID.randomUUID().toString(), "YouTube", "9Ur5IzDKqV.TizenYouTube")
    )
}
