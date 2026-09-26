package mikey.me.advancedstaff.velocity.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityDatabaseManagerTest {

    @Test
    void permanentPunishmentsNeverExpire() {
        assertTrue(VelocityDatabaseManager.isActive(0, 0L, Long.MAX_VALUE));
        assertTrue(VelocityDatabaseManager.isActive(-1, 0L, Long.MAX_VALUE));
    }

    @Test
    void temporaryPunishmentsExpireAtTheirDeadline() {
        long now = 10_000L;

        assertTrue(VelocityDatabaseManager.isActive(5, now - 1_000L, now));
        assertFalse(VelocityDatabaseManager.isActive(5, now - 5_000L, now));
        assertFalse(VelocityDatabaseManager.isActive(5, now - 6_000L, now));
    }
}
