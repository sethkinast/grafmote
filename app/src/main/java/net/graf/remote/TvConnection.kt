package net.graf.remote

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
    private val pending = ArrayDeque<String>()
    private var sawConnectEvent = false

    fun currentState(): State = connState

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
            pending.clear()
            sawConnectEvent = false
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
            pending.add(frame)
            reconnectNowLocked(tv)
        } else {
            pending.add(frame)
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
        exec.execute {
            if (tv.id != activeTvId) {
                generation++
                wolGen++
                try {
                    socket?.close(1000, "switch")
                } catch (_: Exception) {
                }
                socket = null
                pending.clear()
                sawConnectEvent = false
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
        exec.execute {
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
        exec.execute {
            var ok = false
            var conn: HttpURLConnection? = null
            try {
                val url = URL("http://${tv.host}:8001/api/v2/applications/$appId")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.doOutput = true
                conn.outputStream.use { it.write(ByteArray(0)) }
                ok = conn.responseCode == 200
            } catch (e: Exception) {
                Log.d(TAG, "launchApp failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
            val r = ok
            main.post { cb?.invoke(r) }
        }
    }

    fun close() {
        exec.execute {
            generation++
            wolGen++
            try {
                socket?.close(1000, "close")
            } catch (_: Exception) {
            }
            socket = null
            pending.clear()
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

    /** Silent auto-reconnect after a dropped socket; stale generations no-op. */
    private fun scheduleReconnect(tv: Tv) {
        val gen = generation
        exec.schedule({
            if (gen == generation && tv.id == activeTvId && socket == null) {
                connectLocked(tv)
            }
        }, 2, TimeUnit.SECONDS)
    }

    private fun connectLocked(tv: Tv) {
        setState(tv, State.CONNECTING, "Connecting to ${tv.nick}")
        val name = Base64.encodeToString("GrafRemote".toByteArray(), Base64.NO_WRAP)
        val url = if (tv.tokenSupport) {
            "wss://${tv.host}:8002/api/v2/channels/samsung.remote.control?name=$name&token=${tv.token}"
        } else {
            "ws://${tv.host}:8001/api/v2/channels/samsung.remote.control?name=$name"
        }
        // First-run fallback: tokenSupport is only known after a successful REST
        // fetch (TV must be ON). If the secure socket fails and we never confirmed
        // tokenSupport, retry once over plain ws before giving up.
        val slot = tv.id
        val listener = TvSocket(tv, slot)
        socket = wsClient.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private inner class TvSocket(val tv: Tv, val slot: String) : WebSocketListener() {
        private var retriedPlain = false

        override fun onOpen(ws: WebSocket, response: Response) {
            if (slot != activeTvId) {
                ws.close(1000, "stale")
                if (socket === ws) socket = null
                return
            }
            // Flush anything tapped while connecting (optimistic UI).
            while (pending.isNotEmpty()) {
                if (!ws.send(pending.removeFirst())) break
            }
            if (tv.token.isNotEmpty() || sawConnectEvent) {
                setState(tv, State.OPEN, "Connected to ${tv.nick}")
            } else {
                // No token yet: either the TV is showing the Allow/Deny prompt
                // or it will drop us. Give the connect event a moment.
                main.postDelayed(
                    {
                        if (slot == activeTvId && !sawConnectEvent && tv.token.isEmpty()) {
                            setState(
                                tv, State.WAITING_APPROVAL,
                                "Approve \"GrafRemote\" on the TV, then tap again"
                            )
                        }
                    },
                    3000
                )
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                if (msg.optString("event") == "ms.channel.connect") {
                    val token = msg.optJSONObject("data")?.optString("token", "") ?: ""
                    if (token.isNotEmpty() && token != tv.token) {
                        tv.token = token
                        store.updateToken(tv.id, token)
                    }
                    sawConnectEvent = true
                    connState = State.OPEN
                    setState(tv, State.OPEN, "Connected to ${tv.nick}")
                    // Flush anything queued while waiting for approval.
                    while (pending.isNotEmpty()) {
                        if (!ws.send(pending.removeFirst())) break
                    }
                }
            } catch (_: Exception) {
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            onGone(ws, "closed")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            // Fallback for never-confirmed tokenSupport (see connectLocked).
            if (!retriedPlain && tv.tokenSupport && slot == activeTvId) {
                retriedPlain = true
                try {
                    val name = Base64.encodeToString("GrafRemote".toByteArray(), Base64.NO_WRAP)
                    val plain = "ws://${tv.host}:8001/api/v2/channels/samsung.remote.control?name=$name"
                    socket = wsClient.newWebSocket(Request.Builder().url(plain).build(), this)
                    return
                } catch (_: Exception) {
                }
            }
            onGone(ws, t.message ?: "unreachable")
        }

        private fun onGone(ws: WebSocket, detail: String) {
            if (slot != activeTvId) return
            // Only clear if this callback belongs to the current socket, so a
            // newer connection (e.g. the plain-ws retry above) is never dropped
            // by a stale failure.
            if (socket === ws) socket = null
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
                    "Approve \"GrafRemote\" on the TV, then tap again"
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
    }
}
