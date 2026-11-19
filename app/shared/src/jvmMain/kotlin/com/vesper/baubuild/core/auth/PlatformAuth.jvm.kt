package com.vesper.baubuild.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Desktop
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

actual fun platformAuthBrowser(): AuthBrowser = DesktopAuthBrowser()

/**
 * Desktop (macOS / Windows / Linux): opens the system browser and receives the redirect on a one-shot
 * loopback listener at `http://127.0.0.1:<free port>/auth/callback` (RFC 8252 §7.3). Uses a plain
 * ServerSocket so packaged apps don't need the `jdk.httpserver` module.
 */
class DesktopAuthBrowser(
    private val openUrl: (String) -> Unit = ::openInSystemBrowser,
) : AuthBrowser {

    override suspend fun authorize(
        config: MicrosoftAuthConfig,
        buildAuthorizeUrl: (redirectUri: String) -> String,
    ): AuthCallback = withContext(Dispatchers.IO) {
        // Bound before the browser opens, so the redirect can't race the listener.
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 250
            val redirectUri = "http://127.0.0.1:${server.localPort}${config.desktopRedirectPath}"
            try {
                withTimeout(config.desktopTimeoutMillis) {
                    openUrl(buildAuthorizeUrl(redirectUri))
                    AuthCallback(redirectUri, acceptCallback(server, redirectUri, config.desktopRedirectPath))
                }
            } catch (e: TimeoutCancellationException) {
                throw MicrosoftSignInCancelled()
            }
        }
    }

    /** Serves requests until one hits [path]; answers it with a "you can close this tab" page. */
    private suspend fun acceptCallback(server: ServerSocket, redirectUri: String, path: String): String {
        while (true) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                server.accept() // soTimeout makes this return regularly so cancellation is honoured
            } catch (e: SocketTimeoutException) {
                continue
            }
            try { socket.use {
                it.soTimeout = 2_000 // browsers open speculative connections that never send a request
                val requestLine = try {
                    it.getInputStream().bufferedReader().readLine().orEmpty() // GET /auth/callback?code=… HTTP/1.1
                } catch (e: IOException) {
                    ""
                }
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                val out = it.getOutputStream()
                if (target.substringBefore('?') == path) {
                    val body = DONE_PAGE.encodeToByteArray()
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                        "Connection: close\r\nCache-Control: no-store\r\n\r\n").encodeToByteArray())
                    out.write(body)
                    out.flush()
                    val query = target.substringAfter('?', "")
                    return if (query.isEmpty()) redirectUri else "$redirectUri?$query"
                }
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                out.flush()
            } } catch (e: IOException) {
                // A dropped connection from the browser; keep listening.
            }
        }
    }

    private companion object {
        const val DONE_PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>BAU BUILD</title></head>
<body style="font-family:-apple-system,system-ui,sans-serif;background:#0D1220;color:#fff;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
<div style="text-align:center"><h1 style="font-size:28px">Back to BAU BUILD</h1>
<p style="color:#8FA8FF">You can close this tab now.</p></div></body></html>"""
    }
}

internal fun openInSystemBrowser(url: String) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url))
        return
    }
    val os = System.getProperty("os.name").lowercase()
    val command = when {
        "mac" in os -> listOf("open", url)
        "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        else -> listOf("xdg-open", url)
    }
    ProcessBuilder(command).inheritIO().start()
}
