package roma.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** A blocking request through HttpURLConnection: the status code and the body, an error's body included. */
fun httpCall(url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000): Pair<Int, String> {
    val c = URI(url).toURL().openConnection() as HttpURLConnection
    c.connectTimeout = timeoutMs
    c.readTimeout = timeoutMs
    headers.forEach(c::setRequestProperty)
    if (body != null) {
        c.doOutput = true
        c.outputStream.use { it.write(body) }
    }
    val code = c.responseCode
    val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
    return code to text
}

/** The body of a successful request; throws on an HTTP error. */
fun httpText(url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000): String {
    val (code, text) = httpCall(url, body, headers, timeoutMs)
    if (code !in 200..299) throw IOException("HTTP $code")
    return text
}
