package space.megaworld.claudeusage

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import space.megaworld.claudeusage.data.*
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64

class OpenAiBrowserAuthTest {
    @Test fun `each browser attempt uses fresh secrets and publishes only the PKCE challenge`() {
        val first = BrowserAuthorization.create(0)
        val second = BrowserAuthorization.create(0)
        val url = first.authorizationUrl.toHttpUrl()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.verifier, second.verifier)
        assertNotEquals(first.nonce, second.nonce)
        assertEquals("http://localhost:1455/auth/callback", url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(first.verifier.toByteArray())), url.queryParameter("code_challenge"))
        assertFalse(first.authorizationUrl.contains(first.verifier))
        assertFalse(first.toString().contains(first.state))
        assertFalse(PendingOpenAiLogin(browser = first).toString().contains(first.verifier))
    }

    @Test fun `unrelated callbacks and truncated requests cannot end a real sign in`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val session = BrowserAuthorization.create(System.currentTimeMillis(), "http://127.0.0.1:$port/auth/callback")
        OpenAiCallbackServer(session).use { server ->
            val code = async(Dispatchers.IO) { server.awaitCode() }
            val http = OkHttpClient()
            java.net.Socket("127.0.0.1", port).use { it.getOutputStream().write("GET /auth/callback".toByteArray()) }
            fun request(query: String): Int = http.newCall(Request.Builder().url(session.redirectUri + query).build())
                .execute().use { response -> response.body!!.string(); response.code }
            assertEquals(400, request("?state=wrong&code=unrelated"))
            assertEquals(400, request("?state=${session.state}&state=duplicate&code=unrelated"))
            assertFalse(code.isCompleted)
            assertEquals(200, request("?state=${session.state}&code=approved-code"))
            assertEquals("approved-code", withTimeout(5000) { code.await() })
        }
    }

    @Test fun `browser exchange sends the bound verifier and rejects a different nonce`() = runBlocking {
        val session = BrowserAuthorization.create(0).copy(authorizationCode = "approved-code")
        var nonce = session.nonce
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val form = request.body as FormBody
            val values = (0 until form.size).associate { form.name(it) to form.value(it) }
            assertEquals(session.verifier, values["code_verifier"])
            assertEquals(session.redirectUri, values["redirect_uri"])
            assertEquals(session.authorizationCode, values["code"])
            val token = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"nonce":"$nonce","https://api.openai.com/auth":{"chatgpt_account_id":"account"}}""".toByteArray()) + ".s"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body("""{"access_token":"access","refresh_token":"refresh","id_token":"$token","expires_in":3600}""".toResponseBody()).build()
        }.build()
        assertEquals("refresh", OpenAiAuthClient(http) { 0 }.exchangeBrowserLogin(session).refreshToken)
        nonce = "other-attempt"
        try {
            OpenAiAuthClient(http) { 0 }.exchangeBrowserLogin(session)
            fail("Mismatched nonce accepted")
        } catch (e: OpenAiAuthException) { assertTrue(e.terminal) }
    }
}
