package mikey.me.staffsystem.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffModeManagerTest {

    @Test
    void refusesToReplaceInventoryWithoutAnInventoryBackup() {
        assertFalse(StaffModeManager.canReplaceInventory(true, false));
    }

    @Test
    void permitsReplacementWhenInventoryIsBackedUp() {
        assertTrue(StaffModeManager.canReplaceInventory(true, true));
    }

    @Test
    void permitsStaffModeWithoutInventoryItems() {
        assertTrue(StaffModeManager.canReplaceInventory(false, false));
    }
}
