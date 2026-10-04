package space.megaworld.claudeusage

import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import space.megaworld.claudeusage.data.*
import space.megaworld.claudeusage.data.Credentials
import java.util.Base64

class OpenAiAuthTest {
    private val now = 1_000_000L
    private val session = DeviceAuthorization("test-device", "TEST-1234", 5000, now + 600_000)
    private val quota = """{"rate_limit":{"primary_window":{"used_percent":13,"limit_window_seconds":18000}}}"""
    private fun identity(account: String = "test-account") = "header." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"https://api.openai.com/auth":{"chatgpt_account_id":"$account"}}""".toByteArray()) + ".signature"
    private fun tokens(refresh: String = "new-refresh", account: String = "test-account") =
        """{"access_token":"new-access","refresh_token":"$refresh","id_token":"${identity(account)}","expires_in":3600}"""
    private fun client(handler: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()
    private fun response(request: Request, status: Int, body: String, type: String = "application/json") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .header("Content-Type", type).body(body.toResponseBody()).build()
    private fun body(request: Request) = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    @Test fun `device login respects server code interval and expiration`() = runTest {
        val http = client { request ->
            assertEquals("/api/accounts/deviceauth/usercode", request.url.encodedPath)
            assertEquals("tinyglyph", request.header("originator"))
            assertNull(request.header("Authorization"))
            response(request, 200, """{"device_auth_id":"test-device","user_code":"TEST-1234","interval":"5","expires_at":"1970-01-01T00:26:40Z"}""")
        }
        val value = OpenAiAuthClient(http) { now }.startDeviceLogin()
        assertEquals(session, value)
        assertFalse(value.toString().contains("TEST-1234"))
    }

    @Test fun `pending confirmation does not exchange any tokens`() = runTest {
        var count = 0
        val http = client { request ->
            count++
            assertTrue(body(request).contains("test-device"))
            response(request, 403, """{"error":{"code":"deviceauth_authorization_pending"}}""")
        }
        assertEquals(DevicePoll.Pending, OpenAiAuthClient(http) { now }.pollDeviceLogin(session))
        assertEquals(1, count)
    }

    @Test fun `confirmation exchanges the code with PKCE and creates renewable credentials`() = runTest {
        val paths = mutableListOf<String>()
        val http = client { request ->
            paths += request.url.encodedPath
            if (paths.size == 1) response(request, 200,
                """{"authorization_code":"test-grant","code_verifier":"test-verifier","code_challenge":"unused"}""")
            else {
                val form = request.body as FormBody
                val values = (0 until form.size).associate { form.name(it) to form.value(it) }
                assertEquals("authorization_code", values["grant_type"])
                assertEquals("test-verifier", values["code_verifier"])
                assertEquals("test-grant", values["code"])
                assertEquals("https://auth.openai.com/deviceauth/callback", values["redirect_uri"])
                response(request, 200, tokens())
            }
        }
        val result = OpenAiAuthClient(http) { now }.pollDeviceLogin(session) as DevicePoll.Authorized
        assertEquals(listOf("/api/accounts/deviceauth/token", "/oauth/token"), paths)
        assertEquals("test-account", result.credentials.userAgent)
        assertEquals("new-refresh", result.credentials.refreshToken)
        assertEquals(now + 3_600_000, result.credentials.expiresAtMillis)
        assertFalse(result.credentials.toString().contains("new-access"))
        assertFalse(result.credentials.toString().contains("new-refresh"))
    }

    @Test fun `expired code never contacts the server`() = runTest {
        val http = client { error("No request may be sent") }
        try {
            OpenAiAuthClient(http) { session.expiresAtMillis }.pollDeviceLogin(session)
            fail("Expired login accepted")
        } catch (e: OpenAiAuthException) { assertTrue(e.message!!.contains("закончилось")) }
    }

    @Test fun `cloudflare refusal is not treated as pending or allowed to disclose the body`() = runTest {
        val http = client { response(it, 403, "<html>fake-secret</html>", "text/html") }
        try {
            OpenAiAuthClient(http) { now }.pollDeviceLogin(session)
            fail("Challenge accepted")
        } catch (e: OpenAiAuthException) {
            assertTrue(e.message!!.contains("VPN"))
            assertFalse(e.message!!.contains("fake-secret"))
        }
    }

    @Test fun `quota rejection refreshes once and persists rotated credentials before retry`() = runTest {
        var quotaRequests = 0
        var saved: Credentials? = null
        val http = client { request ->
            if (request.url.encodedPath == "/oauth/token") response(request, 200, tokens())
            else {
                quotaRequests++
                if (quotaRequests == 1) response(request, 401, "{}")
                else {
                    assertEquals("new-refresh", saved!!.refreshToken)
                    assertEquals("Bearer new-access", request.header("Authorization"))
                    response(request, 200, quota)
                }
            }
        }
        val result = OpenAiSessionClient(OpenAiUsageClient(http), OpenAiAuthClient(http) { now }) { now }
            .fetch(Credentials("old-access", "test-account", now, "own-refresh", now + 100_000)) { saved = it }
        assertTrue(result is ApiResult.Success)
        assertEquals(2, quotaRequests)
    }

    @Test fun `imported desktop token is never refreshed`() = runTest {
        var count = 0
        val http = client { request ->
            count++
            assertEquals("/backend-api/wham/usage", request.url.encodedPath)
            response(request, 401, "{}")
        }
        val imported = parseOpenAiCredentials("""{"tokens":{"access_token":"imported","refresh_token":"desktop-secret"}}""")
        assertNull(imported.refreshToken)
        assertEquals(ApiResult.Unauthorized, OpenAiSessionClient(OpenAiUsageClient(http), OpenAiAuthClient(http))
            .fetch(imported) { fail("Desktop session must not be rotated") })
        assertEquals(1, count)
    }

    @Test fun `temporary refresh failure keeps credentials and does not mark the session expired`() = runTest {
        val http = client { response(it, 429, """{"error":{"message":"fake-secret"}}""") }
        val result = OpenAiSessionClient(OpenAiUsageClient(http), OpenAiAuthClient(http) { now }) { now }
            .fetch(Credentials("old-access", "test-account", now, "own-refresh", now)) { fail("Credentials replaced on failure") }
        assertTrue(result is ApiResult.Failure)
        assertFalse((result as ApiResult.Failure).message.contains("fake-secret"))
    }

    @Test fun `revoked refresh token requests login without overwriting stored credentials`() = runTest {
        val http = client { response(it, 400, """{"error":"invalid_grant"}""") }
        val result = OpenAiSessionClient(OpenAiUsageClient(http), OpenAiAuthClient(http) { now }) { now }
            .fetch(Credentials("old-access", "test-account", now, "own-refresh", now)) { fail("Invalid rotation saved") }
        assertEquals(ApiResult.Unauthorized, result)
    }

    @Test fun `refresh cannot silently replace the connected account`() = runTest {
        val http = client { response(it, 200, tokens(account = "other-account")) }
        val result = OpenAiAuthClient(http) { now }.refresh(Credentials("old-access", "test-account", now, "own-refresh"))
        assertTrue(result is ApiResult.Failure)
    }

    @Test fun `expiring session renews before requesting quota and never retries a second rejection`() = runTest {
        val paths = mutableListOf<String>()
        val http = client { request ->
            paths += request.url.encodedPath
            if (paths.size == 1) response(request, 200, tokens())
            else {
                assertEquals("Bearer new-access", request.header("Authorization"))
                response(request, 401, "{}")
            }
        }
        val result = OpenAiSessionClient(OpenAiUsageClient(http), OpenAiAuthClient(http) { now }) { now }
            .fetch(Credentials("old-access", "test-account", now, "own-refresh", now + 1000)) {}
        assertEquals(ApiResult.Unauthorized, result)
        assertEquals(listOf("/oauth/token", "/backend-api/wham/usage"), paths)
    }
}
