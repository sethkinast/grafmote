package net.cogdev.grafmote

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Samsung Smart TV LAN interface. Ground truth is the Hubitat driver
 * (SamsungTVRemote.groovy by Dave Gutheinz):
 *
 * - REST info:  http://<ip>:8001/api/v2/  -> device.PowerState, device.wifiMac,
 *   device.TokenAuthSupport, device.model, device.duid, device.FrameTVSupport
 * - Remote keys: WebSocket on port 8002 (wss, token TVs) or 8001 (ws, older TVs):
 *   ws(s)://<ip>:<port>/api/v2/channels/samsung.remote.control?name=<b64 name>[&token=..]
 * - Key frame: {"method":"ms.remote.control","params":{"Cmd":"Click",
 *   "DataOfCmd":"KEY_UP","Option":"false","TypeOfRemote":"SendRemoteKey"}}
 * - Pairing: first connect from an unknown client pops an on-TV Allow/Deny
 *   prompt; on Allow the server sends ms.channel.connect with a token, which
 *   is persisted per TV and sent as ?token=.. on later connects.
 * - App launch: HTTP POST http://<ip>:8001/api/v2/applications/<appId>
 * - Installed-app list: WebSocket emit
 *   {"method":"ms.channel.emit","params":{"event":"ed.installedApp.get","to":"host"}}
 *   answered by an "ed.installedApp.get" event carrying [{appId, name,
 *   app_type}] under data.data (also accepted directly under data).
 *   GET http://<ip>:8001/api/v2/applications/list DOES NOT EXIST (404s).
 *   Some firmware never answers; that is "unavailable", not an error.
 * - Power on: Wake-on-LAN magic packet (UDP 255.255.255.255:7, hex
 *   FFFFFFFFFFFF + 16x MAC) then KEY_POWER Press, wait ~3s, Release.
 *   Power off: KEY_POWER Click. Power state: REST device.PowerState.
 *
 * No SmartThings cloud. LAN only.
 */
class TvConnection(private val store: TvStore) {

    enum class State { IDLE, CONNECTING, WAITING_APPROVAL, OPEN, UNREACHABLE }

    interface Listener {
        fun onConnState(tv: Tv?, state: State, detail: String)
        /** power is "on", "off", or "unknown" (REST unreachable). */
        fun onPower(tv: Tv?, power: String)
    }

    var listener: Listener? = null

    private val exec = Executors.newSingleThreadScheduledExecutor()
    /**
     * Round 41: REST/HTTP work (refreshInfo's GETs, launchApp's POSTs, the
     * installed-app query setup) runs here — NEVER on [exec]. refreshInfo
     * costs up to ~16s (4s+4s timeouts x2 attempts) while the TV is off and
     * would otherwise stall the single command thread behind the 15s poller,
     * delaying Power/WoL/key taps queued behind it. [exec] stays for
     * commands/socket state only; socket-touching halves hop back to [exec].
     */
    private val netExec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // NOTE (TLS): token-supporting TVs serve a SELF-SIGNED cert on :8002 with no
    // public CA, so there is nothing to validate or pin against — the Hubitat
    // driver likewise connects with ignoreSSLIssues:true. The trust-all manager
    // below is scoped to this ONE OkHttpClient, which is used ONLY for Samsung
    // TV WebSocket connections on the local network. Nothing else in the app
    // uses this client, and the app makes no non-TV network requests at all.
    private class TrustAllTvCerts : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val trustAll = TrustAllTvCerts()
    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAll), SecureRandom())
    }

    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAll)
        .hostnameVerifier(HostnameVerifier { _, _ -> true })
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeTvId: String? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connState: State = State.IDLE
    /** Bumped on every (re)connect so a stale scheduled reconnect never fires. */
    private var generation = 0
    /** Bumped to cancel the WoL aggressive-reconnect loop (TV switch/close). */
    private var wolGen = 0
    private data class QueuedFrame(val frame: String, val enqueuedAtMs: Long)
    /**
     * Round 41: touched on [exec] (enqueue/clear) AND on OkHttp's reader
     * thread (onMessage -> flush) — every access holds synchronized(pending).
     */
    private val pending = ArrayDeque<QueuedFrame>()
    /** Written on OkHttp's thread, read on exec — must be volatile. */
    @Volatile private var sawConnectEvent = false
    /**
     * Round 42: consecutive ms.channel.unauthorized rejections with no
     * successful ms.channel.connect in between. Strike 1 KEEPS the stored
     * token (the rejection may be transient — retry with it); strike 2
     * clears it as genuinely revoked so the next connect re-prompts for a
     * fresh one. Incremented on OkHttp's reader thread, reset there on
     * connect and on exec across TV switches — volatile like
     * sawConnectEvent.
     */
    @Volatile private var authStrikes = 0
    /**
     * Round 42: false while the app is backgrounded (MainActivity forwards
     * onResume/onPause via [setForeground]). scheduleReconnect never
     * connects while backgrounded; user-initiated paths ignore the flag.
     */
    @Volatile private var foreground = true

    /** Installed-app entry from the ed.installedApp.get WebSocket answer. */
    data class AppInfo(val appId: String, val name: String)

    private var installedAppsCb: ((List<AppInfo>?) -> Unit)? = null
    private var installedAppsTimeout: Runnable? = null

    fun currentState(): State = connState

    /** Round 42: foreground flag — see [foreground]. Default true. */
    fun setForeground(fg: Boolean) {
        foreground = fg
    }

    /** Switch the active TV: drop the old socket, connect to the new one. */
    fun selectTv(tv: Tv?) {
        exec.execute {
            generation++
            wolGen++
            try {
                socket?.close(1000, "switch")
            } catch (_: Exception) {
            }
            socket = null
            synchronized(pending) { pending.clear() }
            sawConnectEvent = false
            authStrikes = 0
            activeTvId = tv?.id
            if (tv == null) {
                setState(null, State.IDLE, "No TV configured")
            } else {
                connectLocked(tv)
            }
        }
    }

    /**
     * Send a remote key. NEVER gated on connection state (round 19): every
     * tap is queued and fires when the socket opens, and a down socket kicks
     * an IMMEDIATE reconnect — never waits for the next poll tick.
     */
    fun sendKey(tv: Tv, key: String, cmd: String = "Click") {
        val frame = buildKeyFrame(key, cmd)
        exec.execute { sendKeyLocked(tv, frame) }
    }

    private fun buildKeyFrame(key: String, cmd: String): String =
        JSONObject()
            .put("method", "ms.remote.control")
            .put(
                "params",
                JSONObject()
                    .put("Cmd", cmd)
                    .put("DataOfCmd", "KEY_$key")
                    .put("Option", "false")
                    .put("TypeOfRemote", "SendRemoteKey")
            )
            .toString()

    /** sendKey body; runs on exec. Always queues — never drops on state. */
    private fun sendKeyLocked(tv: Tv, frame: String) {
        if (activeTvId != tv.id) {
            activeTvId = tv.id
            generation++
            sawConnectEvent = false
            authStrikes = 0
            try {
                socket?.close(1000, "switch")
            } catch (_: Exception) {
            }
            socket = null
            connectLocked(tv)
        }
        val ws = socket
        if (ws != null && connState == State.OPEN) {
            var sent = false
            try {
                sent = ws.send(frame)
            } catch (_: Exception) {
                sent = false
            }
            if (sent) return
            // Direct send failed (half-open socket): queue + reconnect now.
            enqueuePendingLocked(frame)
            reconnectNowLocked(tv)
        } else {
            enqueuePendingLocked(frame)
            // A live socket awaiting the on-TV Allow/Deny prompt must not be
            // torn down — the queue flushes on ms.channel.connect.
            if (connState == State.WAITING_APPROVAL && socket != null) return
            reconnectNowLocked(tv)
        }
    }

    /**
     * Immediate reconnect kick (round 19). No-op while OPEN or while a
     * connect is already in flight. Runs on exec.
     */
    private fun reconnectNowLocked(tv: Tv) {
        if (tv.id != activeTvId) return
        if (connState == State.OPEN || connState == State.CONNECTING) return
        if (connState == State.WAITING_APPROVAL && socket != null) return
        try {
            socket?.close(1000, "retry")
        } catch (_: Exception) {
        }
        socket = null
        connectLocked(tv)
    }

    /**
     * Foreground kick (round 19): immediate check/reconnect for onResume
     * without flashing a stale "disconnected" state. Same-TV foregrounding
     * keeps the last-known UI state — only CONNECTING is emitted, and
     * OPEN/UNREACHABLE arrive when the check actually resolves. Silent: no
     * error ever surfaces (see onGone/setState).
     */
    fun foregroundCheck(tv: Tv) {
        Log.d(TAG, "token: foregroundCheck tokenPresent=${tv.token.isNotEmpty()} tokenLen=${tv.token.length}")
        exec.execute {
            if (tv.id != activeTvId) {
                generation++
                wolGen++
                try {
                    socket?.close(1000, "switch")
                } catch (_: Exception) {
                }
                socket = null
                synchronized(pending) { pending.clear() }
                sawConnectEvent = false
                authStrikes = 0
                activeTvId = tv.id
                connectLocked(tv)
                return@execute
            }
            reconnectNowLocked(tv)
        }
    }

    /**
     * Power toggle. lastPower comes from the REST poll ("on"/"off"/"unknown").
     * TV on  -> KEY_POWER Click (off). Anything else -> WoL + Press/Release (on).
     * Buttons are never gated: Press/Release queue and fire when the socket
     * opens. Never blocks the exec thread (the old Thread.sleep stalled all
     * queued keys and scheduled reconnects); Release is scheduled instead.
     */
    fun togglePower(tv: Tv, lastPower: String) {
        exec.execute {
            if (activeTvId != tv.id) {
                activeTvId = tv.id
                generation++
                wolGen++
                sawConnectEvent = false
                authStrikes = 0
                try {
                    socket?.close(1000, "switch")
                } catch (_: Exception) {
                }
                socket = null
                connectLocked(tv)
            }
            if (lastPower == "on") {
                sendKeyLocked(tv, buildKeyFrame("POWER", "Click"))
            } else {
                sendWol(tv.wifiMac)
                sendKeyLocked(tv, buildKeyFrame("POWER", "Press"))
                // Aggressive reconnect loop until the TV accepts the socket —
                // never relies on the passive poll timer to notice boot.
                startWolLoopLocked(tv)
                // Mirror the driver: hold POWER Press ~3s then Release.
                exec.schedule({
                    if (tv.id == activeTvId) sendKeyLocked(tv, buildKeyFrame("POWER", "Release"))
                }, 3, TimeUnit.SECONDS)
            }
        }
    }

    /**
     * WoL flow (round 19): after the magic packet, retry the control socket
     * on a short ~2s interval until the TV accepts it. Cancelled when OPEN,
     * on TV switch/close (wolGen/activeTvId), or after ~2 min (60 tries) as a
     * backstop so a TV that never boots stops retrying. Coexists with
     * scheduleReconnect: both guard on socket==null/CONNECTING, so whichever
     * fires first wins and the other skips. Runs on exec.
     */
    private fun startWolLoopLocked(tv: Tv, maxAttempts: Int = 60) {
        wolGen++
        val my = wolGen
        fun tick(attempt: Int) {
            exec.schedule({
                if (my != wolGen) return@schedule
                if (tv.id != activeTvId) return@schedule
                if (connState == State.OPEN) return@schedule
                if (attempt >= maxAttempts) return@schedule
                if (socket == null && connState != State.CONNECTING) connectLocked(tv)
                tick(attempt + 1)
            }, 2, TimeUnit.SECONDS)
        }
        tick(0)
    }

    /** Fetch REST device info; updates stored tokenSupport/wifiMac; reports power. */
    fun refreshInfo(tv: Tv, cb: ((power: String) -> Unit)? = null) {
        // Round 41: REST on netExec — never the single command thread.
        netExec.execute {
            var power = "unknown"
            var raw = ""
            var fetched = false
            // One immediate retry: a single transient failure must never flip
            // the UI to "off" — only a positive off/standby reading does that.
            repeat(2) {
                if (fetched) return@repeat
                try {
                    val root = restGet("http://${tv.host}:8001/api/v2/")
                    val dev = root?.optJSONObject("device")
                    if (dev != null) {
                        fetched = true
                        raw = dev.optString("PowerState", "")
                        power = parsePower(raw)
                        val mac = dev.optString("wifiMac", "")
                        val tokenSupport = dev.optString("TokenAuthSupport", "false") == "true"
                        if (mac.isNotEmpty() || tokenSupport != tv.tokenSupport) {
                            tv.wifiMac = mac
                            tv.tokenSupport = tokenSupport
                            store.updateMeta(tv.id, tokenSupport, mac)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "refreshInfo attempt failed: ${e.message}")
                }
            }
            Log.d(TAG, "refreshInfo ${tv.host} PowerState raw='$raw' -> '$power'")
            val p = power
            main.post {
                listener?.onPower(tv, p)
                cb?.invoke(p)
            }
        }
    }

    /** Launch an app via HTTP POST (driver's appOpenByName transport). */
    fun launchApp(tv: Tv, appId: String, cb: ((ok: Boolean) -> Unit)? = null) {
        launchApp(tv, listOf(appId), cb)
    }

    /**
     * Launch trying each ID in order until one answers HTTP 200 (round 21:
     * catalog entries carry fallback IDs — regional/firmware variants of the
     * same app). Single-ID buttons behave exactly as before.
     */
    fun launchApp(tv: Tv, appIds: List<String>, cb: ((ok: Boolean) -> Unit)? = null) {
        val ordered = appIds.filter { it.isNotBlank() }.distinct()
        // Round 41: blocking POSTs on netExec — never the command thread.
        netExec.execute {
            var ok = false
            for (id in ordered) {
                if (postLaunch(tv, id)) {
                    ok = true
                    break
                }
            }
            val r = ok
            main.post { cb?.invoke(r) }
        }
    }

    /**
     * Per-TV winning-ID launch (round 22): button B on TV T tries its
     * recorded winner for (T, B) FIRST, then B's remaining IDs in catalog
     * order (skipping the already-tried winner). Whatever answers HTTP 200
     * is recorded as the new winner for (T, B), overwriting any previous
     * one — so a stale winner falls through to the fallbacks and the
     * replacement sticks. No-winner buttons behave exactly like the
     * winner-less overload above.
     */
    fun launchApp(tv: Tv, buttonId: String, appIds: List<String>, cb: ((ok: Boolean) -> Unit)? = null) {
        val base = appIds.filter { it.isNotBlank() }.distinct()
        val winner = try {
            store.getWinningAppId(tv.id, buttonId)?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val ordered = if (winner.isNotEmpty()) {
            listOf(winner) + base.filter { it != winner }
        } else {
            base
        }
        // Round 41: blocking POSTs on netExec — never the command thread.
        netExec.execute {
            var ok = false
            for (id in ordered) {
                if (postLaunch(tv, id)) {
                    ok = true
                    try {
                        store.setWinningAppId(tv.id, buttonId, id)
                    } catch (_: Exception) {
                    }
                    break
                }
            }
            val r = ok
            main.post { cb?.invoke(r) }
        }
    }

    private fun postLaunch(tv: Tv, appId: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://${tv.host}:8001/api/v2/applications/$appId")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.doOutput = true
            conn.outputStream.use { it.write(ByteArray(0)) }
            conn.responseCode == 200
        } catch (e: Exception) {
            Log.d(TAG, "launchApp failed: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Query the installed-app list over the remote-control WebSocket
     * (reference samsungtvws library + Samsung Tizen docs):
     * send {"method":"ms.channel.emit","params":{"event":"ed.installedApp.get","to":"host"}}
     * and the TV answers with an event also named "ed.installedApp.get" whose
     * payload carries [{appId, name, app_type}] nested under data.data
     * (tolerantly also accepted directly under data).
     *
     * GET http://<tv>:8001/api/v2/applications/list DOES NOT EXIST (404s).
     * App launch stays REST: POST http://<tv>:8001/api/v2/applications/<appId>.
     *
     * Not all firmware answers; no answer within timeoutMs calls back null
     * ("unavailable", not an error). Callback runs on the main thread.
     * Reuses the existing connect path: ensures the socket is open, queues
     * the emit when down, and flushes it on open/connect like key frames.
     */
    fun queryInstalledApps(tv: Tv, timeoutMs: Long = 10000, cb: (List<AppInfo>?) -> Unit) {
        // Round 41: setup on netExec — the socket-touching half hops to exec.
        netExec.execute { queryInstalledAppsLocked(tv, timeoutMs, cb) }
    }

    /**
     * Setup runs on netExec (round 41); the socket send half hops to exec
     * via [sendEmitLocked] so all socket/generation state stays single-threaded.
     */
    private fun queryInstalledAppsLocked(tv: Tv, timeoutMs: Long, cb: (List<AppInfo>?) -> Unit) {
        synchronized(this) {
            installedAppsCb?.let { prev ->
                main.post { prev(null) }
            }
            installedAppsCb = null
            installedAppsTimeout?.let { main.removeCallbacks(it) }
            installedAppsTimeout = null
            installedAppsCb = cb
            val timeout = Runnable {
                val taken: ((List<AppInfo>?) -> Unit)?
                synchronized(this) {
                    taken = installedAppsCb
                    installedAppsCb = null
                    installedAppsTimeout = null
                }
                taken?.invoke(null)
            }
            installedAppsTimeout = timeout
            main.postDelayed(timeout, timeoutMs)
        }
        val frame = JSONObject()
            .put("method", "ms.channel.emit")
            .put(
                "params",
                JSONObject()
                    .put("event", "ed.installedApp.get")
                    .put("to", "host")
            )
            .toString()
        exec.execute { sendEmitLocked(tv, frame) }
    }

    /** Socket half of the installed-app query; runs on exec. */
    private fun sendEmitLocked(tv: Tv, frame: String) {
        if (activeTvId != tv.id) {
            activeTvId = tv.id
            generation++
            sawConnectEvent = false
            authStrikes = 0
            try {
                socket?.close(1000, "switch")
            } catch (_: Exception) {
            }
            socket = null
            connectLocked(tv)
        }
        val ws = socket
        if (ws != null && connState == State.OPEN) {
            var sent = false
            try {
                sent = ws.send(frame)
            } catch (_: Exception) {
                sent = false
            }
            if (!sent) {
                enqueuePendingLocked(frame)
                reconnectNowLocked(tv)
            }
        } else {
            enqueuePendingLocked(frame)
            // A live socket awaiting the on-TV Allow/Deny prompt must not be
            // torn down — the queue flushes on ms.channel.connect.
            if (connState == State.WAITING_APPROVAL && socket != null) return
            reconnectNowLocked(tv)
        }
    }

    private fun completeInstalledApps(apps: List<AppInfo>?) {
        val taken: ((List<AppInfo>?) -> Unit)?
        synchronized(this) {
            taken = installedAppsCb
            installedAppsCb = null
            installedAppsTimeout?.let { main.removeCallbacks(it) }
            installedAppsTimeout = null
        }
        if (taken != null) main.post { taken.invoke(apps) }
    }

    private fun parseInstalledAppsPayload(msg: JSONObject): List<AppInfo>? {
        val raw = msg.opt("data") ?: return null
        val arr: JSONArray = when (raw) {
            is JSONArray -> raw
            is JSONObject -> {
                val inner = raw.opt("data") ?: return null
                when (inner) {
                    is JSONArray -> inner
                    else -> return null
                }
            }
            else -> return null
        }
        val out = mutableListOf<AppInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val appId = o.optString("appId").trim()
            val name = o.optString("name").trim()
            if (appId.isNotEmpty() && name.isNotEmpty()) out.add(AppInfo(appId, name))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun close() {
        completeInstalledApps(null)
        exec.execute {
            generation++
            wolGen++
            try {
                socket?.close(1000, "close")
            } catch (_: Exception) {
            }
            socket = null
            synchronized(pending) { pending.clear() }
        }
    }

    // ---- internals (run on exec) ----

    /**
     * Normalize the REST device.PowerState value. Real TVs return values other
     * than exactly "on" (e.g. "standby", case variants). Anything unrecognized
     * or missing (failed poll) is "unknown" — never "off", so a failed poll is
     * never mistaken for a powered-off TV.
     */
    private fun parsePower(raw: String): String = when (raw.trim().lowercase()) {
        "on" -> "on"
        "off", "standby", "screenoff", "screen_off", "deepstandby",
        "suspended", "passive", "poweroff", "power_off" -> "off"
        else -> "unknown"
    }

    /**
     * Bounded command queue (round 31): at most MAX_PENDING frames are kept;
     * on overflow the OLDEST is dropped so the newest taps survive. Each
     * entry carries its enqueue time so the flush can discard stale taps.
     * Runs on exec (send paths) — matches existing pending usage.
     */
    private fun enqueuePendingLocked(frame: String) {
        synchronized(pending) {
            pending.add(QueuedFrame(frame, System.currentTimeMillis()))
            while (pending.size > MAX_PENDING) pending.removeFirst()
        }
    }

    /**
     * Drain the queue — ONLY called after the ms.channel.connect handshake
     * completes (round 31). Entries older than PENDING_TTL_MS are discarded
     * without sending: the TTL covers the foreground-reconnect window and
     * WoL boot without letting ancient taps fire after a long outage.
     * Called on OkHttp's thread (from onMessage) as well as exec — holds
     * the pending lock across the drain.
     */
    private fun flushPendingLocked(ws: WebSocket) {
        synchronized(pending) {
            val now = System.currentTimeMillis()
            while (pending.isNotEmpty()) {
                val entry = pending.removeFirst()
                if (now - entry.enqueuedAtMs > PENDING_TTL_MS) continue
                if (!ws.send(entry.frame)) break
            }
        }
    }

    /** Silent auto-reconnect after a dropped socket; stale generations no-op. */
    private fun scheduleReconnect(tv: Tv) {
        // Round 42: never auto-reconnect while backgrounded — checked at
        // schedule time AND at fire time. State stays IDLE; the next
        // onResume's foregroundCheck performs the reconnect.
        if (!foreground) return
        val gen = generation
        exec.schedule({
            if (!foreground) return@schedule
            if (gen == generation && tv.id == activeTvId && socket == null) {
                connectLocked(tv)
            }
        }, 2, TimeUnit.SECONDS)
    }

    private fun connectLocked(tv: Tv) {
        // Round 41: every attempt starts with no handshake seen — direct sends
        // stay queued until ms.channel.connect (see onOpen/onMessage).
        sawConnectEvent = false
        setState(tv, State.CONNECTING, "Connecting to ${tv.nick}")
        val name = Base64.encodeToString("Grafmote".toByteArray(), Base64.NO_WRAP)
        val url = if (tv.tokenSupport) {
            "wss://${tv.host}:8002/api/v2/channels/samsung.remote.control?name=$name&token=${tv.token}"
        } else {
            "ws://${tv.host}:8001/api/v2/channels/samsung.remote.control?name=$name"
        }
        // Round 42: token-lifecycle logging — presence/length only, NEVER
        // the token value.
        Log.d(TAG, "token: connect scheme=${if (tv.tokenSupport) "wss:8002" else "ws:8001"} tokenSupport=${tv.tokenSupport} tokenPresent=${tv.token.isNotEmpty()} tokenLen=${tv.token.length}")
        // TLS fallback (round 41): tokenSupport is only known after a
        // successful REST fetch (TV must be ON). A confirmed-tokenSupport TV
        // tries wss/:8002 first and falls back to plain ws/:8001 on failure;
        // an unconfirmed TV (tokenSupport=false default) tries plain ws/:8001
        // first and falls back to wss/:8002 on failure (see onFailure).
        // Either direction retries at most once.
        val slot = tv.id
        val listener = TvSocket(tv, slot)
        socket = wsClient.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private inner class TvSocket(val tv: Tv, val slot: String) : WebSocketListener() {
        private var retriedFallback = false

        override fun onOpen(ws: WebSocket, response: Response) {
            if (slot != activeTvId || ws !== socket) {
                try {
                    ws.close(1000, "stale")
                } catch (_: Exception) {
                }
                return
            }
            // Round 31/41: do NOT enter OPEN and do NOT flush here. The TV
            // requires the app-level ms.channel.connect handshake before
            // accepting ms.remote.control frames; entering OPEN at TCP open
            // lets direct sends fire into the pre-handshake window where the
            // TV ignores them. OPEN happens only in the ms.channel.connect
            // handler below; until then frames stay queued.
            if (tv.token.isEmpty() && !sawConnectEvent) {
                // No token yet: either the TV is showing the Allow/Deny prompt
                // or it will drop us. Give the connect event a moment.
                main.postDelayed(
                    {
                        if (slot == activeTvId && ws === socket && !sawConnectEvent && tv.token.isEmpty()) {
                            setState(
                                tv, State.WAITING_APPROVAL,
                                "Approve \"Grafmote\" on the TV, then tap again"
                            )
                        }
                    },
                    3000
                )
            }
            // Token path: stay CONNECTING until ms.channel.connect arrives.
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (slot != activeTvId || ws !== socket) return
            try {
                val msg = JSONObject(text)
                if (msg.optString("event") == "ed.installedApp.get") {
                    completeInstalledApps(parseInstalledAppsPayload(msg))
                    return
                }
                val ev = msg.optString("event", "")
                // Round 42: TWO-STRIKE rule — a single rejection must not
                // destroy a stored token (post-backgrounding rejections can
                // be transient while the token is still valid). Strike 1
                // keeps tv.token and the persisted token and parks in
                // WAITING_APPROVAL; the next reconnect (foreground return or
                // key tap, via the existing reconnect paths) retries WITH
                // it. Strike 2 with no ms.channel.connect in between means
                // genuinely revoked: clear it so the next connect goes out
                // with no token and the TV's prompt flow issues a fresh one.
                // No automatic reconnect here — retries happen on
                // foreground/key-tap (see onGone's strike guard).
                if (ev.equals("ms.channel.unauthorized", ignoreCase = true)) {
                    authStrikes++
                    Log.d(TAG, "token: ms.channel.unauthorized tokenPresent=${tv.token.isNotEmpty()} tokenLen=${tv.token.length} strikes=$authStrikes")
                    if (authStrikes >= 2) {
                        tv.token = ""
                        try {
                            store.updateToken(tv.id, "")
                        } catch (_: Exception) {
                        }
                        Log.d(TAG, "token: token cleared tokenPresent=false tokenLen=0")
                    }
                    sawConnectEvent = false
                    setState(
                        tv, State.WAITING_APPROVAL,
                        "Approve \"Grafmote\" on the TV, then tap again"
                    )
                    return
                }
                if (ev.equals("ms.channel.timeout", ignoreCase = true)) {
                    // Round 42: a timeout is NOT an auth rejection — never a
                    // strike, never clears the token.
                    Log.d(TAG, "token: ms.channel.timeout tokenPresent=${tv.token.isNotEmpty()} tokenLen=${tv.token.length}")
                    sawConnectEvent = false
                    setState(
                        tv, State.WAITING_APPROVAL,
                        "Approve \"Grafmote\" on the TV, then tap again"
                    )
                    return
                }
                if (msg.optString("event") == "ms.channel.connect") {
                    val token = msg.optJSONObject("data")?.optString("token", "") ?: ""
                    Log.d(TAG, "token: ms.channel.connect receivedPresent=${token.isNotEmpty()} receivedLen=${token.length} differsFromStored=${token.isNotEmpty() && token != tv.token}")
                    // Round 42: any successful connect resets the strike count.
                    authStrikes = 0
                    if (token.isNotEmpty() && token != tv.token) {
                        tv.token = token
                        store.updateToken(tv.id, token)
                        Log.d(TAG, "token: token saved tokenPresent=true tokenLen=${token.length}")
                    }
                    sawConnectEvent = true
                    connState = State.OPEN
                    setState(tv, State.OPEN, "Connected to ${tv.nick}")
                    // Flush anything queued while connecting / waiting for
                    // approval — now that ms.channel.connect completed the TV
                    // accepts remote frames. Stale entries (>10s) are dropped.
                    flushPendingLocked(ws)
                }
            } catch (_: Exception) {
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            onGone(ws, "closed")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            // Bidirectional TLS fallback (round 41, see connectLocked):
            // confirmed-tokenSupport TVs fall back wss -> plain ws, while
            // unconfirmed TVs (tokenSupport=false default, first connect went
            // plain ws/:8001) fall back plain ws -> wss/:8002. At most once,
            // and only for the current socket.
            if (!retriedFallback && slot == activeTvId && ws === socket) {
                retriedFallback = true
                try {
                    val name = Base64.encodeToString("Grafmote".toByteArray(), Base64.NO_WRAP)
                    val fallback = if (tv.tokenSupport) {
                        "ws://${tv.host}:8001/api/v2/channels/samsung.remote.control?name=$name"
                    } else {
                        "wss://${tv.host}:8002/api/v2/channels/samsung.remote.control?name=$name&token=${tv.token}"
                    }
                    sawConnectEvent = false
                    socket = wsClient.newWebSocket(Request.Builder().url(fallback).build(), this)
                    return
                } catch (_: Exception) {
                }
            }
            onGone(ws, t.message ?: "unreachable")
        }

        private fun onGone(ws: WebSocket, detail: String) {
            if (slot != activeTvId) return
            // Round 41: a stale callback (closed/failure from a superseded
            // socket) must never clobber a healthy new connection — no-op
            // unless this is still the current socket.
            if (ws !== socket) return
            socket = null
            // Round 42: a socket death while an unauthorized strike is
            // outstanding must NOT schedule an immediate automatic
            // reconnect — park in WAITING_APPROVAL (socket already nulled,
            // so the next foreground return or key tap retries via the
            // existing reconnect paths). Bounded, not a tight loop: strike
            // 2 clears the token, ending the cycle.
            if (authStrikes > 0) {
                setState(
                    tv, State.WAITING_APPROVAL,
                    "Approve \"Grafmote\" on the TV, then tap again"
                )
                return
            }
            // Log the raw reason for debugging, but NEVER surface it: the UI
            // only ever sees friendly transient strings (see setState calls).
            Log.d(TAG, "socket gone for ${tv.host}: $detail")
            if (sawConnectEvent || tv.token.isNotEmpty()) {
                // Previously-connected socket died (e.g. the app was
                // backgrounded and the TV/OS killed it). Silent auto-reconnect
                // with a transient friendly status — no error shown.
                setState(tv, State.IDLE, "Reconnecting…")
                scheduleReconnect(tv)
            } else if (detail.contains("refused", true) ||
                detail.contains("unreachable", true) ||
                detail.contains("timed out", true) ||
                detail.contains("failed to connect", true)
            ) {
                setState(tv, State.UNREACHABLE, "TV may be off (${tv.host}) — Power still sends WoL")
            } else {
                setState(
                    tv, State.WAITING_APPROVAL,
                    "Approve \"Grafmote\" on the TV, then tap again"
                )
            }
        }
    }

    private fun setState(tv: Tv?, state: State, detail: String) {
        connState = state
        main.post { listener?.onConnState(tv, state, detail) }
    }

    private fun restGet(url: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            conn?.disconnect()
        }
    }

    /** Wake-on-LAN: FFFFFFFFFFFF + 16x MAC, UDP broadcast to port 7 (per driver). */
    private fun sendWol(mac: String) {
        try {
            val clean = mac.replace(":", "").replace("-", "").uppercase()
            if (clean.length != 12) {
                Log.d(TAG, "sendWol: no valid MAC stored yet ($mac)")
                return
            }
            val macBytes = ByteArray(6) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val packet = ByteArray(6 + 16 * 6)
            packet.fill(0xFF.toByte(), 0, 6)
            for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
            val sock = DatagramSocket()
            sock.broadcast = true
            sock.send(
                DatagramPacket(
                    packet, packet.size,
                    InetAddress.getByName("255.255.255.255"), 7
                )
            )
            sock.close()
        } catch (e: Exception) {
            Log.d(TAG, "sendWol failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TvConnection"
        /** Bound on the queued-command queue (round 31, Seth's choice — tight). */
        private const val MAX_PENDING = 4
        /** Stale queued taps older than this never fire (round 31). */
        private const val PENDING_TTL_MS = 10_000L
    }
}
