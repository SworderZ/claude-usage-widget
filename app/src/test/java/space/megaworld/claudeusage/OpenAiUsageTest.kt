package space.megaworld.claudeusage

import org.junit.Assert.*
import org.junit.Test
import space.megaworld.claudeusage.data.classifyOpenAiUsageResponse
import space.megaworld.claudeusage.data.ApiResult
import space.megaworld.claudeusage.data.OpenAiUsageClient
import space.megaworld.claudeusage.data.Credentials
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import space.megaworld.claudeusage.data.parseCodexUsage
import space.megaworld.claudeusage.data.parseOpenAiCredentials
import space.megaworld.claudeusage.data.UsageSnapshot

class OpenAiUsageTest {
    @Test fun `reads live server schema and seconds timestamp`() {
        val windows = parseCodexUsage("""{"rate_limit":{"primary_window":{"used_percent":6,"limit_window_seconds":18000,"reset_at":1791053504},"secondary_window":{"used_percent":7,"limit_window_seconds":604800,"reset_at":1791584566}},"code_review_rate_limit":null,"additional_rate_limits":null}""")
        assertEquals(2, windows.size)
        assertEquals(UsageSnapshot.KEY_FIVE_HOUR, windows[0].key)
        assertEquals(6.0, windows[0].utilization, 0.0)
        assertEquals(1791053504000L, windows[0].resetsAtMillis)
        assertEquals(UsageSnapshot.KEY_SEVEN_DAY, windows[1].key)
    }
    @Test fun `missing window is not fabricated as zero`() {
        val windows = parseCodexUsage("""{"rate_limit":{"primary_window":{"used_percent":0,"limit_window_seconds":18000},"secondary_window":null}}""")
        assertEquals(1, windows.size)
        assertEquals(0.0, windows[0].utilization, 0.0)
        assertNull(windows[0].resetsAtMillis)
    }
    @Test(expected = IllegalArgumentException::class) fun `no quota is unavailable`() {
        parseCodexUsage("""{"rate_limit":null,"model_usage":{}}""")
    }
    @Test(expected = IllegalArgumentException::class) fun `out of range percent rejected`() {
        parseCodexUsage("""{"rate_limit":{"primary_window":{"used_percent":101}}}""")
    }
    @Test fun `unknown duration is not treated as five hours`() {
        val windows = parseCodexUsage("""{"rate_limit":{"primary_window":{"used_percent":20,"limit_window_seconds":3600}}}""")
        assertEquals("3600s", windows.single().key)
    }
    @Test fun `additional quota preserved`() {
        val windows = parseCodexUsage("""{"additional_rate_limits":[{"limit_name":"fast","rate_limit":{"primary_window":{"used_percent":9,"limit_window_seconds":18000}}}]}""")
        assertEquals("fast_five_hour", windows.single().key)
    }
    @Test fun `imports only access token without shared refresh token`() {
        val credentials = parseOpenAiCredentials("""{"tokens":{"access_token":"fake-access","account_id":"test-account","refresh_token":"never-import"}}""")
        assertEquals("fake-access", credentials.cookieHeader)
        assertEquals("test-account", credentials.userAgent)
        assertFalse(credentials.toString().contains("never-import"))
    }
    @Test(expected = IllegalArgumentException::class) fun `API key is not subscription login`() {
        parseOpenAiCredentials("""{"OPENAI_API_KEY":"fake-api-key"}""")
    }
    @Test(expected = IllegalArgumentException::class) fun `header injection rejected`() {
        parseOpenAiCredentials("""{"access_token":"fake\\nheader"}""".replace("\\\\n", "\\n"))
    }
    @Test fun `403 HTML is a routing error and not expired session`() {
        val result = classifyOpenAiUsageResponse(403, "<html>blocked</html>", "text/html")
        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).message.contains("HTTP 403"))
    }
    @Test fun `403 JSON is not automatically expired session`() {
        assertTrue(classifyOpenAiUsageResponse(403, "{}", "application/json") is ApiResult.Failure)
    }
    @Test fun `401 JSON rejects credentials`() {
        assertEquals(ApiResult.Unauthorized, classifyOpenAiUsageResponse(401, "{}"))
    }
    @Test fun `HTML challenge even on 401 is not token expiry`() {
        assertTrue(classifyOpenAiUsageResponse(401, "<html>verify</html>") is ApiResult.Failure)
    }
    @Test fun `200 challenge cannot look like valid quota`() {
        assertTrue(classifyOpenAiUsageResponse(200, "<html>verify</html>", "text/html") is ApiResult.Failure)
    }
    @Test fun `challenge header does not require HTML`() {
        assertTrue(classifyOpenAiUsageResponse(403, "{}", mitigation = "challenge") is ApiResult.Failure)
    }
    @Test fun `known token error on 403 rejects credentials`() {
        assertEquals(ApiResult.Unauthorized, classifyOpenAiUsageResponse(403, """{"error":{"code":"token_expired"}}"""))
    }
    @Test fun `region error explains network instead of asking for reimport`() {
        val result = classifyOpenAiUsageResponse(403, """{"error":{"code":"unsupported_country_region_territory"}}""") as ApiResult.Failure
        assertTrue(result.message.contains("регион"))
    }
    @Test fun `rate limiting preserves credentials`() {
        assertTrue(classifyOpenAiUsageResponse(429, "{}") is ApiResult.Failure)
    }
    @Test fun `error body secrets never reach messages`() {
        val result = classifyOpenAiUsageResponse(403, """{"error":{"code":"token_fake-secret","message":"fake-sensitive-body"}}""") as ApiResult.Failure
        assertFalse(result.message.contains("fake-secret"))
        assertFalse(result.message.contains("fake-sensitive-body"))
    }
    @Test fun `BOM file is accepted`() {
        assertEquals("fake-access", parseOpenAiCredentials("\uFEFF" + """{"access_token":"fake-access"}""").cookieHeader)
    }
    @Test fun `HTTP import preserves headers and classifies actual response`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer fake-access", chain.request().header("Authorization"))
            assertEquals("fake-account", chain.request().header("ChatGPT-Account-Id"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(403).message("Forbidden").header("Content-Type", "text/html")
                .body("<html>blocked</html>".toResponseBody()).build()
        }.build()
        assertTrue(OpenAiUsageClient(client).fetch(Credentials("fake-access", "fake-account", 0)) is ApiResult.Failure)
    }
    @Test fun `connectivity probe sends no credentials`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Authorization"))
            assertNull(chain.request().header("ChatGPT-Account-Id"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(401).message("Unauthorized").header("Content-Type", "application/json")
                .body("{}".toResponseBody()).build()
        }.build()
        assertTrue(OpenAiUsageClient(client).checkConnectivity().contains("HTTP 401"))
    }

}
