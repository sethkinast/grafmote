package net.graf.remote

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * LAN scan for Samsung TVs.
 *
 * SSDP M-SEARCH alone finds zero TVs on some networks (multicast filtered),
 * so the primary mechanism is an active subnet sweep: GET
 * http://<ip>:8001/api/v2/ on every host of the phone's /24 with a short
 * timeout and high concurrency. A fast SSDP pass runs first only because it
 * is cheap (~1.5s) and can catch TVs that answer multicast but not the sweep.
 *
 * A WifiManager multicast lock is held for the SSDP phase only: without it
 * Android's WiFi stack silently drops incoming multicast packets. The sweep
 * itself is plain unicast HTTP and needs no lock. The scan runs only on
 * demand (Settings "Scan" button) so it has no effect on cold start.
 */
object Ssdp {
    data class Found(val ip: String, val name: String)

    /**
     * @param noWifi true when the phone is not on WiFi (mobile data / WiFi
     * off) — nothing was scanned; the UI should say so instead of reporting
     * "no TVs found".
     */
    data class ScanResult(val found: List<Found>, val noWifi: Boolean)

    /** Hosts probed in parallel. 253 hosts / 50 ≈ 6 batches × ~400ms. */
    const val PROBE_CONCURRENCY = 50

    /** Per-connection connect+read timeout for sweep probes. */
    const val PROBE_TIMEOUT_MS = 400

    /** SSDP first pass is capped short — the sweep is the real mechanism. */
    private const val SSDP_FIRST_PASS_MS = 1500

    /** Hard cap on the whole sweep so wall time stays under ~10s for a /24. */
    private const val SWEEP_WALL_CAP_MS = 9000L

    /**
     * Keys always present in a genuine Samsung api/v2 "device" object
     * (ground truth: the Hubitat driver reads PowerState / wifiMac /
     * TokenAuthSupport / model / duid). Random port-8001 responders
     * (printers, cameras, routers) return non-JSON or JSON without a Samsung
     * "device" object and are never listed.
     */
    private val SAMSUNG_KEYS = setOf(
        "duid", "id", "model", "modelName",
        "TokenAuthSupport", "PowerState", "wifiMac", "FrameTVSupport"
    )

    fun discover(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val selfIp = wifiIpInt(wifi)
        if (selfIp == null) return ScanResult(emptyList(), noWifi = true)

        // Fast SSDP first pass (multicast lock held only for this phase).
        val ssdpFound = withMulticastLock(wifi) { ssdpScan(SSDP_FIRST_PASS_MS) }
        // Primary mechanism: active sweep of the phone's /24.
        val swept = sweep(selfIp, onProgress)

        // Merge, one entry per IP. Prefer a real friendly name over a bare IP.
        val merged = LinkedHashMap<String, Found>()
        for (f in ssdpFound) merged[f.ip] = f
        for (f in swept) {
            val prev = merged[f.ip]
            merged[f.ip] = if (prev == null || prev.name == prev.ip) f else prev
        }
        return ScanResult(merged.values.toList(), noWifi = false)
    }

    /** Phone's WiFi IP as a little-endian int, or null when not on WiFi. */
    @Suppress("DEPRECATION")
    private fun wifiIpInt(wifi: WifiManager?): Int? {
        if (wifi == null) return null
        try {
            if (!wifi.isWifiEnabled) return null
        } catch (_: Exception) {
            return null
        }
        val ip = try {
            wifi.connectionInfo?.ipAddress ?: 0
        } catch (_: Exception) {
            0
        }
        // 0 means no WiFi association (mobile data, WiFi off, airplane mode).
        return if (ip == 0) null else ip
    }

    // ---- Subnet sweep (primary) ----

    // NOTE (subnet size): LANs larger than /24 (e.g. /23 or /16) are
    // deliberately NOT fully probed — thousands of hosts would blow the ~10s
    // wall-time budget. Only the /24 containing the phone's IP is swept;
    // TVs on a different /24 of a larger subnet need manual IP entry.
    private fun probeIps(selfIp: Int): List<String> {
        val a = selfIp and 0xFF
        val b = (selfIp shr 8) and 0xFF
        val c = (selfIp shr 16) and 0xFF
        val selfLast = (selfIp shr 24) and 0xFF
        val prefix = "$a.$b.$c."
        return (1..254).filter { it != selfLast }.map { "$prefix$it" }
    }

    private fun sweep(selfIp: Int, onProgress: (Int, Int) -> Unit): List<Found> {
        val ips = probeIps(selfIp)
        val pool = Executors.newFixedThreadPool(PROBE_CONCURRENCY)
        val done = AtomicInteger(0)
        try {
            val futures = ips.map { ip ->
                pool.submit(Callable<Found?> {
                    try {
                        probeHost(ip)
                    } finally {
                        onProgress(done.incrementAndGet(), ips.size)
                    }
                })
            }
            pool.shutdown()
            // Collect with a global deadline: each probe self-limits via its
            // socket timeouts, but a wedged socket must never stall the scan
            // past the wall-time budget.
            val out = mutableListOf<Found>()
            val deadline = System.currentTimeMillis() + SWEEP_WALL_CAP_MS
            for (f in futures) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                try {
                    f.get(
                        minOf(remaining, (PROBE_TIMEOUT_MS * 3).toLong()),
                        TimeUnit.MILLISECONDS
                    )?.let { out.add(it) }
                } catch (_: Exception) {
                    // Timeout / refused / non-Samsung → not a TV, skip.
                }
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /** GET api/v2; returns a Found only if the host identifies as Samsung. */
    private fun probeHost(ip: String): Found? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://$ip:8001/api/v2/").openConnection() as HttpURLConnection
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val dev = try {
                JSONObject(body).optJSONObject("device")
            } catch (_: Exception) {
                null
            } ?: return null
            if (!isSamsung(dev)) return null
            val rawName = dev.optString("name", "").trim()
            val name = if (rawName.isEmpty()) ip else stripTvPrefix(rawName)
            Found(ip, name)
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun isSamsung(dev: JSONObject): Boolean = SAMSUNG_KEYS.any { dev.has(it) }

    /**
     * Samsung TVs broadcast a default friendly-name like "[TV] Samsung 7 Series".
     * The "[TV]" is Samsung's convention, not the user's chosen name, so strip
     * a single leading "[TV] " from LAN-discovered names before display/storage.
     * Manual entries never pass through here and are left untouched.
     */
    private fun stripTvPrefix(raw: String): String {
        if (!raw.startsWith("[TV] ")) return raw
        val stripped = raw.removePrefix("[TV] ")
        return if (stripped.isEmpty()) raw else stripped
    }

    // ---- SSDP first pass (cheap, best-effort) ----

    private inline fun <T> withMulticastLock(wifi: WifiManager?, block: () -> T): T {
        val lock = try {
            wifi?.createMulticastLock("GrafRemoteSsdp")?.apply {
                setReferenceCounted(true)
            }
        } catch (e: Exception) {
            Log.d(TAG, "multicast lock create failed: ${e.message}")
            null
        }
        try {
            try {
                lock?.acquire()
            } catch (e: Exception) {
                Log.d(TAG, "multicast lock acquire failed: ${e.message}")
            }
            return block()
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (e: Exception) {
                Log.d(TAG, "multicast lock release failed: ${e.message}")
            }
        }
    }

    private fun ssdpScan(timeoutMs: Int): List<Found> {
        val locations = mutableSetOf<String>()
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket()
            sock.broadcast = true
            sock.soTimeout = 1000
            val msg = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ns=01; ns=01\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:samsung.com:device:RemoteControl:1\r\n" +
                    "\r\n"
                ).toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) {
                sock.send(DatagramPacket(msg, msg.size, group, 1900))
            }
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let {
                        locations.add(it.trim())
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // keep listening until the deadline
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ssdp scan failed: ${e.message}")
        } finally {
            try {
                sock?.close()
            } catch (_: Exception) {
            }
        }
        // One entry per responder IP; prefer the api/v2 device name.
        return locations.mapNotNull {
            try {
                URL(it).host
            } catch (_: Exception) {
                null
            }
        }.distinct().mapNotNull { ip -> Found(ip, resolveName(ip, locations)) }
    }

    private fun resolveName(ip: String, locations: Set<String>): String {
        // Same friendly-name source as the sweep: device.name from api/v2.
        // (The sweep already probed this IP too; this extra GET only happens
        // for SSDP-first-pass hosts and is capped by PROBE_TIMEOUT_MS.)
        val v2name = probeHost(ip)?.name
        if (v2name != null && v2name != ip) return v2name
        for (loc in locations) {
            try {
                if (URL(loc).host != ip) continue
            } catch (_: Exception) {
                continue
            }
            describeXml(loc)?.let { if (it.isNotEmpty()) return it }
        }
        // The M-SEARCH ST already scoped SSDP responders to Samsung, so list
        // the host even when neither name source answered.
        return v2name ?: ip
    }

    /** Fallback: GET the SSDP LOCATION device XML and read <friendlyName>. */
    private fun describeXml(location: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            if (conn.responseCode != 200) return null
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            val raw = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: return null
            stripTvPrefix(raw)
        } catch (e: Exception) {
            Log.d(TAG, "describe failed for $location: ${e.message}")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private const val TAG = "Ssdp"
}
