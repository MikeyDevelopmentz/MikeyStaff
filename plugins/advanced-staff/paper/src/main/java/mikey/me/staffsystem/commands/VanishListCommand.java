package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.VanishManager;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class VanishListCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final VanishManager vanishManager;
    private final TextUtil textUtil;

    public VanishListCommand(ConfigurationManager configurationManager, VanishManager vanishManager, TextUtil textUtil) {
        this.settings = configurationManager.getSettings();
        this.vanishManager = vanishManager;
        this.textUtil = textUtil;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String permission = settings.getPermission("vanish.list");
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        sender.sendMessage(textUtil.prefixed("vanish.list-header"));
        for (UUID uuid : vanishManager.getVanished()) {
            Player vanished = Bukkit.getPlayer(uuid);
            if (vanished != null) {
                String line = textUtil.papi(vanished, "vanish.list-entry", Map.of("%staff_vanish_player%", vanished.getName()));
                sender.sendMessage(line);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
