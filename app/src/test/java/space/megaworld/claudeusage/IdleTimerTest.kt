package space.megaworld.claudeusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.megaworld.claudeusage.glyph.IdleTimer

class IdleTimerTest {
    private var now = 10_000L
    private val timer = IdleTimer({ now })

    @Test
    fun `screen off at zero idle still schedules the first alarm`() {
        timer.setInteractive(false)
        assertEquals(0L, timer.idleMillis)
        assertEquals(now + minutes(15), timer.nextUpdateAt(15))
    }

    @Test
    fun `an interactive screen has no idle alarm`() {
        now += minutes(60)
        assertEquals(0L, timer.idleMillis)
        assertNull(timer.nextUpdateAt(15))
    }

    @Test
    fun `quota refreshes cannot postpone the first deadline`() {
        timer.setInteractive(false)
        val deadline = now + minutes(30)
        repeat(29) {
            now += minutes(1)
            assertEquals(deadline, timer.nextUpdateAt(30))
        }
    }

    @Test
    fun `late delivery advances to the next absolute brightness step`() {
        timer.setInteractive(false)
        val offAt = now
        now += minutes(37)
        assertEquals(offAt + minutes(45), timer.nextUpdateAt(15))
        assertEquals(minutes(37), timer.idleMillis)
    }

    @Test
    fun `brightness steps are at least fifteen minutes apart`() {
        timer.setInteractive(false)
        val offAt = now
        now += minutes(15)
        assertEquals(offAt + minutes(30), timer.nextUpdateAt(15))
        now += minutes(15)
        assertEquals(offAt + minutes(45), timer.nextUpdateAt(15))
    }

    @Test
    fun `repeated screen off events do not restart the timer`() {
        timer.setInteractive(false)
        val deadline = timer.nextUpdateAt(30)
        now += minutes(20)
        timer.setInteractive(false)
        assertEquals(minutes(20), timer.idleMillis)
        assertEquals(deadline, timer.nextUpdateAt(30))
    }

    @Test
    fun `screen on cancels idle and next screen off starts a new period`() {
        timer.setInteractive(false)
        now += minutes(20)
        timer.setInteractive(true)
        assertEquals(0L, timer.idleMillis)
        assertNull(timer.nextUpdateAt(15))
        now += minutes(5)
        timer.setInteractive(false)
        assertEquals(now + minutes(15), timer.nextUpdateAt(15))
    }

    @Test
    fun `no further wakeups are scheduled at maximum brightness`() {
        timer.setInteractive(false)
        now += minutes(60)
        assertNull(timer.nextUpdateAt(15))
        now += minutes(24 * 60)
        assertNull(timer.nextUpdateAt(15))
    }

    @Test
    fun `changing the threshold uses the existing screen off time`() {
        timer.setInteractive(false)
        val offAt = now
        now += minutes(20)
        assertEquals(offAt + minutes(30), timer.nextUpdateAt(30))
        assertEquals(offAt + minutes(30), timer.nextUpdateAt(15))
    }

    @Test
    fun `starting while already asleep schedules an alarm`() {
        val asleep = IdleTimer({ now }, initiallyInteractive = false)
        assertEquals(0L, asleep.idleMillis)
        assertEquals(now + minutes(15), asleep.nextUpdateAt(15))
    }

    @Test
    fun `invalid thresholds do not schedule alarms`() {
        timer.setInteractive(false)
        assertNull(timer.nextUpdateAt(0))
        assertNull(timer.nextUpdateAt(-1))
    }

    private fun minutes(value: Int) = value.toLong() * 60_000L
}
