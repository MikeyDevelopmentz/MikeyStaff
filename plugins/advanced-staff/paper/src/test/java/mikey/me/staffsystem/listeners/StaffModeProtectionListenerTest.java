package mikey.me.staffsystem.listeners;

import mikey.me.staffsystem.inventory.StaffMenuHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffModeProtectionListenerTest {

    @Test
    void normalizesNamespacedCommands() {
        assertEquals("/clear", StaffModeProtectionListener.commandName("/minecraft:clear @s"));
        assertEquals("/give", StaffModeProtectionListener.commandName("/bukkit:give @s stone"));
        assertEquals("/staffmode", StaffModeProtectionListener.commandName("/staffmode"));
    }

    @Test
    void blocksStaffInventoryClicksButAllowsStaffMenuClicks() {
        assertTrue(StaffModeProtectionListener.shouldBlockInventoryClick(true, false, false, false, false, false, false));
        assertTrue(StaffModeProtectionListener.shouldBlockInventoryClick(false, true, false, true, false, false, false));
        assertTrue(StaffModeProtectionListener.shouldBlockInventoryClick(false, true, true, false, true, false, false));
        assertTrue(StaffModeProtectionListener.shouldBlockInventoryClick(false, true, true, true, true, false, false));
        assertTrue(StaffModeProtectionListener.shouldBlockInventoryClick(false, true, true, true, false, true, false));
        assertTrue(StaffModeProtectionListener.shouldBlockInventoryClick(false, true, true, true, false, false, true));
        assertFalse(StaffModeProtectionListener.shouldBlockInventoryClick(false, true, true, true, false, false, false));
        assertTrue(StaffModeProtectionListener.shouldBlockInventoryClick(false, true, true, false, false, false, false));
        assertFalse(StaffModeProtectionListener.shouldBlockInventoryClick(false, false, false, false, false, false, false));
    }

    @Test
    void blocksDragsOutsideStaffMenus() {
        assertTrue(StaffModeProtectionListener.shouldBlockInventoryDrag(false, true, false, true));
        assertTrue(StaffModeProtectionListener.shouldBlockInventoryDrag(false, true, true, false));
        assertFalse(StaffModeProtectionListener.shouldBlockInventoryDrag(false, true, true, true));
        assertTrue(StaffModeProtectionListener.shouldBlockInventoryDrag(true, false, true, true));
    }

    @Test
    void recognizesOnlyPluginOwnedMenuInventories() {
        assertTrue(StaffModeProtectionListener.isStaffMenu(new StaffMenuHolder()));
        assertFalse(StaffModeProtectionListener.isStaffMenu(new InventoryHolder() {
            @Override
            public Inventory getInventory() {
                return null;
            }
        }));
        assertFalse(StaffModeProtectionListener.isStaffMenu(null));
    }

    @Test
    void blocksItemActionsDuringTransitionsAndActiveStaffMode() {
        assertTrue(StaffModeProtectionListener.shouldBlockItemAction(true, false));
        assertTrue(StaffModeProtectionListener.shouldBlockItemAction(false, true));
        assertFalse(StaffModeProtectionListener.shouldBlockItemAction(false, false));
    }
}
