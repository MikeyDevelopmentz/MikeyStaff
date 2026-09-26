package mikey.me.staffsystem.utils;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeUtilTest {

    private final TimeUtil timeUtil = new TimeUtil();

    @Test
    void parsesCombinedDurationUnits() {
        assertEquals(3723L, timeUtil.parseDurationSecondsStrict("1h2m3s").getAsLong());
        assertEquals(604800L, timeUtil.parseDurationSecondsStrict("1w").getAsLong());
        assertEquals(90061L, timeUtil.parseDurationSecondsStrict("1d1h1m1s").getAsLong());
    }

    @Test
    void handlesPermanentAliasesAndDefaults() {
        assertEquals(OptionalLong.of(0L), timeUtil.parseDurationSecondsStrict("perm"));
        assertEquals(OptionalLong.of(0L), timeUtil.parseDurationSecondsStrict("permanent"));
        assertEquals(OptionalLong.of(0L), timeUtil.parseDurationSecondsStrict("forever"));
        assertEquals(42L, timeUtil.parseDurationSeconds("", 42L));
    }

    @Test
    void rejectsMalformedAndOverflowingDurations() {
        assertTrue(timeUtil.parseDurationSecondsStrict("garbage").isEmpty());
        assertTrue(timeUtil.parseDurationSecondsStrict("10x").isEmpty());
        assertTrue(timeUtil.parseDurationSecondsStrict("0s").isEmpty());
        assertTrue(timeUtil.parseDurationSecondsStrict("999999999999999999999999w").isEmpty());
        assertTrue(timeUtil.parseDurationSecondsStrict("9999999999w").isEmpty());
        assertTrue(timeUtil.parseDurationSecondsStrict("3651d").isEmpty());
        assertEquals(3650L * 86400L, timeUtil.parseDurationSecondsStrict("3650d").getAsLong());
        assertThrows(IllegalArgumentException.class, () -> timeUtil.parseDurationSeconds("10x", 0L));
    }

    @Test
    void validatesConfiguredDurationsAndTicks() {
        assertTrue(timeUtil.isValidConfiguredDuration(0L));
        assertTrue(timeUtil.isValidDurationSeconds(1L));
        assertFalse(timeUtil.isValidDurationSeconds(-1L));
        assertEquals(20L, timeUtil.getSchedulerTicks(1L));
        assertThrows(IllegalArgumentException.class, () -> timeUtil.getSchedulerTicks(0L));
    }

    @Test
    void formatsReadableDurations() {
        assertEquals("0s", timeUtil.formatDuration(0L));
        assertEquals("1m 1s", timeUtil.formatDuration(61L));
        assertEquals("1h 1m 1s", timeUtil.formatDuration(3661L));
    }

    @Test
    void rejectsWhitespaceSignsAndAcceptsCaseInsensitiveUnits() {
        assertEquals(3723L, timeUtil.parseDurationSecondsStrict("1H2M3S").getAsLong());
        assertEquals(60L, timeUtil.parseDurationSecondsStrict("60").getAsLong());
        assertTrue(timeUtil.parseDurationSecondsStrict(" 1h ").isEmpty());
        assertTrue(timeUtil.parseDurationSecondsStrict("+1h").isEmpty());
        assertTrue(timeUtil.parseDurationSecondsStrict("-1h").isEmpty());
        assertTrue(timeUtil.parseDurationSecondsStrict("1h-").isEmpty());
    }

    @Test
    void enforcesSchedulerAndFormattingBoundaries() {
        long maximum = Long.MAX_VALUE / 1000L;
        assertTrue(timeUtil.isValidConfiguredDuration(maximum));
        assertEquals(maximum * 20L, timeUtil.getSchedulerTicks(maximum));
        assertThrows(IllegalArgumentException.class, () -> timeUtil.getSchedulerTicks(Long.MAX_VALUE));
        assertEquals("1h 0m 0s", timeUtil.formatDuration(3600L));
        assertEquals("0s", timeUtil.formatDuration(-1L));
    }
}
