package space.megaworld.claudeusage

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.megaworld.claudeusage.data.UsageSnapshot
import space.megaworld.claudeusage.data.parseIsoMillis
import space.megaworld.claudeusage.data.parseWindows
import space.megaworld.claudeusage.ui.UsageFormat
import space.megaworld.claudeusage.ui.UsageLevel

class UsageParsingTest {

    private fun parse(raw: String) = parseWindows(Json.parseToJsonElement(raw))

    @Test
    fun `reads documented windows`() {
        val windows = parse(
            """
            {
              "five_hour": { "utilization": 42.5, "resets_at": "2026-09-30T18:00:00Z" },
              "seven_day": { "utilization": 71, "resets_at": "2026-10-03T00:00:00Z" }
            }
            """.trimIndent()
        )
        assertEquals(2, windows.size)
        assertEquals(UsageSnapshot.KEY_FIVE_HOUR, windows[0].key)
        assertEquals(42.5, windows[0].utilization, 0.001)
        assertEquals(71.0, windows[1].utilization, 0.001)
    }

    @Test
    fun `keeps unknown windows and ignores unknown fields`() {
        val windows = parse(
            """
            {
              "seven_day_opus": { "utilization": 10, "resets_at": null, "extra": 1 },
              "five_hour": { "utilization": 5 },
              "account_uuid": "abc",
              "some_flag": true
            }
            """.trimIndent()
        )
        assertEquals(listOf("five_hour", "seven_day_opus"), windows.map { it.key })
        assertNull(windows[0].resetsAtMillis)
    }

    @Test
    fun `finds windows nested one level deeper`() {
        val windows = parse("""{ "usage": { "five_hour": { "utilization": 3 } } }""")
        assertEquals(1, windows.size)
        assertEquals("five_hour", windows[0].key)
    }

    @Test
    fun `tolerates a response without any window`() {
        assertTrue(parse("""{ "error": "nope" }""").isEmpty())
        assertTrue(parse("""[1, 2]""").isEmpty())
    }

    @Test
    fun `parses both instant and offset timestamps`() {
        assertEquals(
            parseIsoMillis("2026-09-30T18:00:00Z"),
            parseIsoMillis("2026-09-30T20:00:00+02:00"),
        )
        assertNull(parseIsoMillis("не дата"))
    }

    @Test
    fun `formats remaining time`() {
        val now = 1_000_000L
        assertEquals("2ч 14м", UsageFormat.remaining(now + (134 * 60_000L), now))
        assertEquals("43м", UsageFormat.remaining(now + (43 * 60_000L), now))
        assertEquals("< 1м", UsageFormat.remaining(now - 5_000L, now))
        assertNull(UsageFormat.remaining(null, now))
    }

    @Test
    fun `levels follow the 70 and 90 thresholds`() {
        assertEquals(UsageLevel.NORMAL, UsageFormat.level(69.9))
        assertEquals(UsageLevel.WARNING, UsageFormat.level(70.0))
        assertEquals(UsageLevel.WARNING, UsageFormat.level(90.0))
        assertEquals(UsageLevel.CRITICAL, UsageFormat.level(90.1))
    }
}
