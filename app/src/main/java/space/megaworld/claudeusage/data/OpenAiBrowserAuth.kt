package space.megaworld.claudeusage.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable
internal data class BrowserAuthorization(
    val state: String,
    val verifier: String,
    val nonce: String,
    val expiresAtMillis: Long,
    val redirectUri: String = "http://localhost:1455/auth/callback",
    val authorizationCode: String? = null,
) {
    val authorizationUrl: String get() = "https://auth.openai.com/oauth/authorize".toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", "app_EMoamEEZ73f0CkXaXp7hrann")
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("scope", "openid profile email offline_access")
        .addQueryParameter("state", state).addQueryParameter("nonce", nonce)
        .addQueryParameter("code_challenge", Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("id_token_add_organizations", "true")
        .addQueryParameter("codex_cli_simplified_flow", "true")
        .addQueryParameter("originator", "tinyglyph").build().toString()

    override fun toString() = "BrowserAuthorization(expiresAtMillis=$expiresAtMillis, confirmed=${authorizationCode != null})"

    companion object {
        fun create(now: Long, redirectUri: String = "http://localhost:1455/auth/callback") =
            BrowserAuthorization(randomSecret(), randomSecret(), randomSecret(), now + 15 * 60_000, redirectUri)
        private fun randomSecret() = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}

@Serializable
internal data class PendingOpenAiLogin(val browser: BrowserAuthorization? = null, val device: DeviceAuthorization? = null) {
    init { require((browser == null) != (device == null)) }
    val expiresAtMillis: Long get() = browser?.expiresAtMillis ?: device!!.expiresAtMillis
}

/** Bound only to loopback. An unrelated or mismatched callback never terminates the real login. */
internal class OpenAiCallbackServer(private val session: BrowserAuthorization) : AutoCloseable {
    private val listener = ServerSocket().apply {
        reuseAddress = true
        bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), session.redirectUri.toHttpUrl().port), 4)
        soTimeout = 1000
    }

    suspend fun awaitCode(now: () -> Long = System::currentTimeMillis): String = withContext(Dispatchers.IO) {
        while (now() < session.expiresAtMillis) {
            currentCoroutineContext().ensureActive()
            val socket = try { listener.accept() } catch (e: SocketTimeoutException) { continue }
            socket.use {
                socket.soTimeout = 3000
                try {
                    // Read a bounded request line instead of retaining cookies or arbitrary headers.
                    val bytes = java.io.ByteArrayOutputStream()
                    val input = socket.getInputStream()
                    while (bytes.size() < 8192) {
                        val ch = input.read()
                        if (ch < 0 || ch == '\n'.code) break
                        bytes.write(ch)
                    }
                    // Drain bounded headers so closing the socket does not reset a browser's response.
                    var previous = -1
                    var lineLength = 0
                    var headersComplete = false
                    repeat(16_384) {
                        if (!headersComplete) {
                            val ch = input.read()
                            if (ch < 0) throw java.io.EOFException()
                            if (ch == '\n'.code) {
                                if (lineLength == 0 || (lineLength == 1 && previous == '\r'.code)) headersComplete = true
                                lineLength = 0
                            } else lineLength++
                            previous = ch
                        }
                    }
                    if (!headersComplete || bytes.size() >= 8192) return@use
                    val parts = bytes.toString(Charsets.UTF_8.name()).trim().split(' ')
                    val url = parts.getOrNull(1)?.takeIf { it.startsWith('/') && !it.startsWith("//") }
                        ?.let { target -> ("http://localhost" + target).toHttpUrlOrNull() }
                    val suppliedState = url?.queryParameterValues("state")?.singleOrNull()
                    val valid = parts.firstOrNull() == "GET" && url?.encodedPath == "/auth/callback" &&
                        suppliedState != null && MessageDigest.isEqual(session.state.toByteArray(), suppliedState.toByteArray())
                    if (!valid) { respond(socket, 400, "Неверное подтверждение входа. Вернитесь в tinyGlyph."); return@use }
                    if (url!!.queryParameter("error") != null) {
                        respond(socket, 200, "Вход отменён. Можно вернуться в tinyGlyph.")
                        throw OpenAiAuthException("Вход отменён в браузере. Можно попробовать снова.", terminal = true)
                    }
                    val code = url.queryParameterValues("code").singleOrNull()
                    if (code.isNullOrBlank() || code.length > 32768 || code.any { it <= ' ' }) {
                        respond(socket, 400, "Не получен код подтверждения."); return@use
                    }
                    // A browser closing its tab must not discard an already valid callback.
                    runCatching { respond(socket, 200, "Подтверждение получено. Вернитесь в tinyGlyph, чтобы увидеть результат.") }
                    return@withContext code
                } catch (e: java.io.IOException) {
                    if (e is OpenAiAuthException) throw e
                    currentCoroutineContext().ensureActive()
                    // Ignore truncated requests; the listener remains available for the real callback.
                }
            }
        }
        throw OpenAiAuthException("Время входа закончилось. Начните вход заново.", terminal = true)
    }

    private fun respond(socket: java.net.Socket, status: Int, message: String) {
        val body = """<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>tinyGlyph</title><style>body{background:#111;color:#eee;font:18px system-ui;margin:32px;line-height:1.5}a{display:inline-block;color:#111;background:#eee;padding:12px 20px;border-radius:24px;text-decoration:none}</style><h1>tinyGlyph</h1><p>$message</p><a href="tinyglyph://openai-login">Вернуться в tinyGlyph</a></html>""".toByteArray()
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\n" +
                "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray())
            write(body); flush()
        }
    }

    override fun close() { listener.close() }
}
