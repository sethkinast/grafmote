package net.graf.remote

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Optional SSDP scan for Samsung TVs on the LAN. Sends M-SEARCH for
 * urn:samsung.com:device:RemoteControl:1, collects LOCATION URLs, then GETs
 * each device XML for its <friendlyName>. Untested against real hardware
 * (no LAN TVs reachable from the build machine) — failures just yield an
 * empty list and the user can always add a TV by IP manually.
 */
object Ssdp {
    data class Found(val ip: String, val name: String)

    fun discover(timeoutMs: Int = 3500): List<Found> {
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
        return locations.mapNotNull { describe(it) }
    }

    private fun describe(location: String): Found? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode != 200) return null
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim().orEmpty()
            val host = URL(location).host ?: return null
            Found(host, name.ifEmpty { host })
        } catch (e: Exception) {
            Log.d(TAG, "describe failed for $location: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private const val TAG = "Ssdp"
}
