package com.inspiredandroid.kai.mcp

import android.util.Base64
import android.net.Uri
import android.os.SystemClock
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

internal actual fun mcpOAuthProof(): OAuthProof? {
    val random = SecureRandom()
    fun encode(data: ByteArray) = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    fun secret() = encode(ByteArray(32).also(random::nextBytes))
    val verifier = secret()
    return OAuthProof(secret(), verifier, encode(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())))
}

internal actual fun openMcpOAuthLoopback(state: String, onCallback: (String) -> Unit): McpOAuthLoopback? {
    val server = ServerSocket()
    try {
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        server.soTimeout = 1000
    } catch (_: IOException) {
        runCatching { server.close() }
        return null
    }
    val origin = "http://127.0.0.1:${server.localPort}"
    val redirect = "$origin/mcp-oauth/callback"
    val deadline = SystemClock.elapsedRealtime() + 600_000
    Thread({
        server.use {
            while (!server.isClosed && SystemClock.elapsedRealtime() < deadline) {
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { break }
                var callback: String? = null
                socket.use {
                    try {
                        socket.soTimeout = 2000
                        val input = socket.getInputStream()
                        val line = StringBuilder()
                        while (line.length < 8192) {
                            val ch = input.read()
                            if (ch < 0 || ch == 10) break
                            if (ch != 13) line.append(ch.toChar())
                        }
                        val target = line.toString().split(' ').takeIf { it.size == 3 && it[0] == "GET" }?.get(1)
                        if (target?.startsWith("/mcp-oauth/callback?") == true && '#' !in target) {
                            val raw = origin + target
                            if (Uri.parse(raw).getQueryParameters("state").singleOrNull() == state) callback = raw
                        }
                        val body = if (callback != null) "Return to Re-Kai to finish connecting." else "Callback not found."
                        val status = if (callback != null) "200 OK" else "404 Not Found"
                        socket.getOutputStream().write(
                            ("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.length}\r\n" +
                                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n$body").encodeToByteArray(),
                        )
                        socket.getOutputStream().flush()
                    } catch (_: IOException) {
                        // A closed browser socket must not discard a validated callback.
                    }
                }
                callback?.let { onCallback(it); break }
            }
        }
    }, "kai-mcp-oauth-loopback").apply { isDaemon = true }.start()
    return McpOAuthLoopback(redirect) { runCatching { server.close() } }
}
