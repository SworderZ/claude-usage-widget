package space.megaworld.claudeusage

import org.junit.Assert.*
import org.junit.Test
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
}
