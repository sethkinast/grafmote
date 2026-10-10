package net.graf.remote

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import org.json.JSONObject

/**
 * Optional SSDP scan for Samsung TVs on the LAN. Sends M-SEARCH for
 * urn:samsung.com:device:RemoteControl:1, collects LOCATION URLs, then
 * resolves each responder's friendly name via http://<ip>:8001/api/v2/
 * (device.name), falling back to the LOCATION device XML's
 * <friendlyName>. Failures just yield an empty list and the user can
 * always add a TV by IP manually.
 *
 * A WifiManager multicast lock is held for the duration of the scan:
 * without it Android's WiFi stack silently drops incoming multicast
 * packets, so the M-SEARCH goes out but responses never reach the app.
 * The lock is always released in a finally block. The scan runs only on
 * demand (Settings "Scan" button) so it has no effect on cold start.
 */
object Ssdp {
    data class Found(val ip: String, val name: String)

    fun discover(context: Context, timeoutMs: Int = 4000): List<Found> {
        // Hold a multicast lock so M-SEARCH replies reach us on WiFi.
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
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
            return scan(timeoutMs)
        } finally {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (e: Exception) {
                Log.d(TAG, "multicast lock release failed: ${e.message}")
            }
        }
    }

    private fun scan(timeoutMs: Int): List<Found> {
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
            Log.d(TAG, "discover failed: ${e.message}")
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
        deviceNameV2(ip)?.let { if (it.isNotEmpty()) return it }
        for (loc in locations) {
            try {
                if (URL(loc).host != ip) continue
            } catch (_: Exception) {
                continue
            }
            describeXml(loc)?.let { if (it.isNotEmpty()) return it }
        }
        return ip
    }

    /** GET http://<ip>:8001/api/v2/ and read device.name (Samsung LAN API). */
    private fun deviceNameV2(ip: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://$ip:8001/api/v2/").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optJSONObject("device")?.optString("name")?.trim()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.d(TAG, "api/v2 name failed for $ip: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Fallback: GET the SSDP LOCATION device XML and read <friendlyName>. */
    private fun describeXml(location: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode != 200) return null
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.d(TAG, "describe failed for $location: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private const val TAG = "Ssdp"
}
