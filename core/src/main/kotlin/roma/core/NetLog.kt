package roma.core

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Response
import java.net.InetSocketAddress
import java.net.Proxy

// TEMPORARY latency instrumentation; removed before the final commit.
object NetLog {
    fun mark(what: String) = println("ROMALAT MARK $what up=${System.nanoTime() / 1_000_000} wall=${System.currentTimeMillis()}")

    fun factory(client: String) = EventListener.Factory { call -> Listener(client) }

    private class Listener(val client: String) : EventListener() {
        var t0 = 0L
        val ev = StringBuilder()
        var bytes = 0L
        fun at() = (System.nanoTime() - t0) / 1_000_000
        override fun callStart(call: Call) { t0 = System.nanoTime() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) { ev.append(" dns=").append(at()) }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { ev.append(" connS=").append(at()) }
        override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) { ev.append(" tls=").append(at()).append(handshake?.tlsVersion?.javaName ?: "") }
        override fun connectionAcquired(call: Call, connection: Connection) { ev.append(" acq=").append(at()).append(connection.protocol()).append('@').append(connection.route().socketAddress.address.hostAddress).append('#').append(System.identityHashCode(connection)) }
        override fun responseHeadersEnd(call: Call, response: Response) { if (client == "media3") println("ROMALAT HDR media3 ${call.request().url.host} up=${t0 / 1_000_000} hdr=${at()} range=${call.request().header("Range")}"); ev.append(" hdr=").append(at()).append('/').append(response.code).append(if (response.cacheResponse != null) " CACHE" else "") }
        override fun responseBodyEnd(call: Call, byteCount: Long) { bytes = byteCount }
        override fun cacheHit(call: Call, response: Response) { ev.append(" cacheHit") }
        override fun callEnd(call: Call) = done(call, "")
        override fun callFailed(call: Call, ioe: java.io.IOException) = done(call, " FAIL=" + ioe.javaClass.simpleName)
        override fun canceled(call: Call) { ev.append(" cancel=").append(at()) }
        fun done(call: Call, extra: String) {
            val u = call.request().url
            println("ROMALAT NET $client ${call.request().method} ${u.host}${u.encodedPath.take(60)}${if (client == "coil") "…" + u.toString().takeLast(30) else ""} up=${t0 / 1_000_000}$ev end=${at()} bytes=$bytes$extra")
        }
    }
}
