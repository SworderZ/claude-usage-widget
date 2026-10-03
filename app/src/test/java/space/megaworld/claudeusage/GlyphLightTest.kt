package space.megaworld.claudeusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.megaworld.claudeusage.data.GlyphLight
import space.megaworld.claudeusage.data.AmbientChannel
import space.megaworld.claudeusage.data.AmbientSettings
import space.megaworld.claudeusage.data.GlyphChannelMode
import space.megaworld.claudeusage.data.GlyphStripMode
import space.megaworld.claudeusage.data.WeatherPlace

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

    @Test
    fun `default strip keeps quota source and bounds valid percentages`() {
        val settings = AmbientSettings()
        assertEquals(GlyphStripMode.USAGE, settings.stripMode)
        assertEquals(71, settings.stripPercent(70.6, 20))
        assertEquals(0, settings.stripPercent(-5.0, 90))
        assertEquals(100, settings.stripPercent(120.0, 20))
        assertEquals(null, settings.stripPercent(null, 90))
        assertEquals(null, settings.stripPercent(Double.NaN, 90))
    }

    @Test
    fun `rain strip shows low percentages and distinguishes missing forecast from zero`() {
        val settings = AmbientSettings(stripMode = GlyphStripMode.RAIN)
        assertEquals(20, settings.stripPercent(90.0, 20))
        assertEquals(70, settings.stripPercent(null, 70))
        assertEquals(0, settings.stripPercent(90.0, 0))
        assertEquals(null, settings.stripPercent(90.0, null))
    }

    @Test
    fun `rain strip requests weather when both short channels are off`() {
        val settings = AmbientSettings(stripMode = GlyphStripMode.RAIN)
        assertTrue(settings.rainEnabled)
        assertEquals(false, settings.wantsWeather)
        val withCity = settings.copy(place = WeatherPlace("Москва", 55.75, 37.62))
        assertTrue(withCity.wantsWeather)
        assertEquals(false, withCity.copy(stripMode = GlyphStripMode.USAGE).wantsWeather)
        assertEquals(false, withCity.copy(stripMode = GlyphStripMode.OFF).wantsWeather)
    }

    @Test
    fun `turning off the strip keeps short channel rain and idle working`() {
        val settings = AmbientSettings(channelA = GlyphChannelMode.RAIN,
            channelB = GlyphChannelMode.IDLE, stripMode = GlyphStripMode.OFF,
            place = WeatherPlace("Москва", 55.75, 37.62))
        assertEquals(null, settings.stripPercent(90.0, 80))
        assertTrue(settings.wantsWeather)
        assertTrue(settings.idleEnabled)
        assertEquals(GlyphLight.MAX, settings.lightFor(AmbientChannel.A, minutes(30), 80))
        assertEquals(GlyphLight.FAINT, settings.lightFor(AmbientChannel.B, minutes(30), 80))
    }

    private fun minutes(value: Int): Long = value.toLong() * 60_000L
}
