package mikey.me.staffsystem.managers;

import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class InspectPermissionTest {

    @Test
    void inspectDoesNotGrantInventoryPermissions() {
        Player staff = mock(Player.class);
        Player target = mock(Player.class);
        when(staff.hasPermission("staff.inspect")).thenReturn(true);
        InspectManager manager = manager();
        manager.openInventory(staff, target);
        manager.openEnderChest(staff, target);
        verifyNoInteractions(target);
        verify(staff, times(2)).sendMessage("denied");
    }

    @Test
    void mainMenuRequiresInspectPermission() {
        Player staff = mock(Player.class);
        Player target = mock(Player.class);
        InspectManager manager = manager();
        manager.openMainMenu(staff, target);
        assertTrue(manager.handleMainMenuClick(staff, ChatColor.DARK_AQUA + "Inspect: target", 21));
        verifyNoInteractions(target);
        verify(staff).closeInventory();
    }

    private InspectManager manager() {
        TextUtil text = mock(TextUtil.class);
        when(text.prefixed(anyString())).thenReturn("denied");
        return new InspectManager(null, null, null, null, null, text,
                new SettingsConfig(new YamlConfiguration()));
    }
}
