package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.StaffSystemPlugin;
import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandReloadTest {
    @Test
    void reloadChangesRegisteredCommandPermission() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("permissions.reports.report", "old.report");
        ConfigurationManager config = mock(ConfigurationManager.class);
        when(config.getSettings()).thenReturn(new SettingsConfig(yaml));
        doAnswer(call -> { yaml.set("permissions.reports.report", "new.report"); return null; })
                .when(config).reloadAll();
        StaffSystemPlugin plugin = mock(StaffSystemPlugin.class);
        PluginCommand report = mock(PluginCommand.class, CALLS_REAL_METHODS);
        when(plugin.getCommand("report")).thenReturn(report);
        Field field = StaffSystemPlugin.class.getDeclaredField("configurationManager");
        field.setAccessible(true);
        field.set(plugin, config);
        Method apply = StaffSystemPlugin.class.getDeclaredMethod("applyCommandPermissions");
        apply.setAccessible(true);
        try (MockedStatic<Bukkit> ignored = mockStatic(Bukkit.class)) {
            apply.invoke(plugin);
            assertEquals("old.report", report.getPermission());
            CommandSender sender = mock(CommandSender.class);
            when(sender.hasPermission(anyString())).thenReturn(true);
            StaffModeCommand command = new StaffModeCommand(config, null, null, mock(TextUtil.class), () -> {
                try { apply.invoke(plugin); } catch (Exception e) { throw new RuntimeException(e); }
            });
            command.onCommand(sender, null, "staffmode", new String[]{"reload"});
            assertEquals("new.report", report.getPermission());
        }
    }
}
