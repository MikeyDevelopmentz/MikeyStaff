package mikey.me.staffsystem.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreezeTimingTest {

    private static final long NOW = 1_000_000L;

    @Test
    void nonPositiveDurationIsPermanent() {
        assertEquals(Long.MAX_VALUE, FreezeManager.getRemainingMillis(NOW, 0L, NOW));
        assertEquals(Long.MAX_VALUE, FreezeManager.getRemainingMillis(NOW, -1L, NOW));
    }

    @Test
    void overflowingDurationExpiresImmediately() {
        long overflowing = Long.MAX_VALUE / 1000L + 1L;
        assertEquals(0L, FreezeManager.getRemainingMillis(NOW, overflowing, NOW));
    }

    @Test
    void startTimeInTheFutureYieldsFullDuration() {
        assertEquals(60_000L, FreezeManager.getRemainingMillis(NOW + 5_000L, 60L, NOW));
    }

    @Test
    void partialElapsedTimeIsSubtracted() {
        assertEquals(40_000L, FreezeManager.getRemainingMillis(NOW - 20_000L, 60L, NOW));
    }

    @Test
    void elapsedAtOrBeyondDurationIsZero() {
        assertEquals(0L, FreezeManager.getRemainingMillis(NOW - 60_000L, 60L, NOW));
        assertEquals(0L, FreezeManager.getRemainingMillis(NOW - 120_000L, 60L, NOW));
    }

    @Test
    void clockGoingBackwardsDoesNotProduceNegativeTime() {
        long remaining = FreezeManager.getRemainingMillis(NOW, 60L, NOW - 5_000L);
        assertTrue(remaining >= 0L, "remaining time must never be negative");
    }

    @Test
    void ticksRoundUpToWholeTicksAndNeverZero() {
        assertEquals(1L, FreezeManager.ticksForMillis(0L));
        assertEquals(1L, FreezeManager.ticksForMillis(-500L));
        assertEquals(1L, FreezeManager.ticksForMillis(1L));
        assertEquals(1L, FreezeManager.ticksForMillis(50L));
        assertEquals(2L, FreezeManager.ticksForMillis(51L));
        assertEquals(20L, FreezeManager.ticksForMillis(1000L));
    }

    @Test
    void missingReasonFallsBackToNone() {
        assertEquals("None", PunishmentManager.resolveReason(null));
        assertEquals("None", PunishmentManager.resolveReason(""));
        assertEquals("Spamming", PunishmentManager.resolveReason("Spamming"));
    }
}
