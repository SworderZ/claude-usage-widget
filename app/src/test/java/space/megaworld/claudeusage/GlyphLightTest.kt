package space.megaworld.claudeusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.megaworld.claudeusage.data.GlyphLight
import space.megaworld.claudeusage.data.AmbientChannel
import space.megaworld.claudeusage.data.AmbientSettings
import space.megaworld.claudeusage.data.GlyphChannelMode

/**
 * Яркость каналов A и B. Проверяется здесь, а не глазами на телефоне, потому что
 * на устройстве видно только «горит ярче / горит тусклее» — промахнуться в
 * арифметике рампы и не заметить этого очень легко.
 */
class GlyphLightTest {

    private val threshold = 30

    @Test
    fun `idle channel stays dark before the threshold`() {
        assertEquals(GlyphLight.OFF, GlyphLight.forIdle(0L, threshold))
        assertEquals(GlyphLight.OFF, GlyphLight.forIdle(minutes(29), threshold))
    }

    @Test
    fun `idle channel starts to glow exactly at the threshold`() {
        assertEquals(GlyphLight.FAINT, GlyphLight.forIdle(minutes(30), threshold))
    }

    @Test
    fun `idle channel reaches maximum at four times the threshold and holds`() {
        assertEquals(GlyphLight.MAX, GlyphLight.forIdle(minutes(120), threshold))
        // Сутки простоя не должны ни переполнить шкалу, ни погасить канал.
        assertEquals(GlyphLight.MAX, GlyphLight.forIdle(minutes(24 * 60), threshold))
    }

    @Test
    fun `idle ramp is monotonic between the threshold and the maximum`() {
        var previous = -1
        for (minute in 30..120) {
            val light = GlyphLight.forIdle(minutes(minute), threshold)
            assertTrue("яркость упала на $minute мин", light >= previous)
            previous = light
        }
    }

    @Test
    fun `idle channel survives a nonsense threshold`() {
        assertEquals(GlyphLight.OFF, GlyphLight.forIdle(minutes(10), 0))
    }

    @Test
    fun `rain channel is dark without a forecast and below thirty percent`() {
        assertEquals(GlyphLight.OFF, GlyphLight.forRain(null))
        assertEquals(GlyphLight.OFF, GlyphLight.forRain(0))
        assertEquals(GlyphLight.OFF, GlyphLight.forRain(29))
    }

    @Test
    fun `rain channel climbs by steps`() {
        assertEquals(GlyphLight.FAINT, GlyphLight.forRain(30))
        assertEquals(GlyphLight.FAINT, GlyphLight.forRain(49))
        assertEquals(1800, GlyphLight.forRain(50))
        assertEquals(1800, GlyphLight.forRain(69))
        assertEquals(GlyphLight.MAX, GlyphLight.forRain(70))
        assertEquals(GlyphLight.MAX, GlyphLight.forRain(100))
    }

    @Test
    fun `rain and idle reach their independently selected physical channels`() {
        val settings = AmbientSettings(channelA = GlyphChannelMode.RAIN, channelB = GlyphChannelMode.IDLE)
        assertEquals(GlyphLight.MAX, settings.lightFor(AmbientChannel.A, minutes(30), 80))
        assertEquals(GlyphLight.FAINT, settings.lightFor(AmbientChannel.B, minutes(30), 80))
        val swapped = settings.copy(channelA = GlyphChannelMode.IDLE, channelB = GlyphChannelMode.RAIN)
        assertEquals(GlyphLight.FAINT, swapped.lightFor(AmbientChannel.A, minutes(30), 80))
        assertEquals(GlyphLight.MAX, swapped.lightFor(AmbientChannel.B, minutes(30), 80))
    }

    @Test
    fun `disabled channel stays dark even when both input functions are active`() {
        val settings = AmbientSettings(channelA = GlyphChannelMode.OFF, channelB = GlyphChannelMode.RAIN)
        assertEquals(GlyphLight.OFF, settings.lightFor(AmbientChannel.A, minutes(120), 80))
        assertEquals(GlyphLight.MAX, settings.lightFor(AmbientChannel.B, minutes(120), 80))
    }

    private fun minutes(value: Int): Long = value.toLong() * 60_000L
}
