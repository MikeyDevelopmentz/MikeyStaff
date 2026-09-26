package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.FreezeManager;
import mikey.me.staffsystem.utils.NetworkPlayerResolver;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FreezeListCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final FreezeManager freezeManager;
    private final TextUtil textUtil;
    private final NetworkPlayerResolver networkPlayerResolver;

    public FreezeListCommand(ConfigurationManager configurationManager, FreezeManager freezeManager, TextUtil textUtil,
            NetworkPlayerResolver networkPlayerResolver) {
        this.settings = configurationManager.getSettings();
        this.freezeManager = freezeManager;
        this.textUtil = textUtil;
        this.networkPlayerResolver = networkPlayerResolver;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String permission = settings.getPermission("freeze.list");
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        sender.sendMessage(textUtil.prefixed("freeze.list-header"));
        for (UUID uuid : freezeManager.getFrozen().keySet()) {
            Player online = Bukkit.getPlayer(uuid);
            String playerName = online != null ? online.getName() : networkPlayerResolver.resolveName(uuid, uuid.toString());
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("%staff_frozen_player%", playerName);
            placeholders.put("%staff_freeze_time_remaining%", freezeManager.getRemainingFormatted(uuid));
            sender.sendMessage(textUtil.format(textUtil.prefixed("freeze.list-entry"), placeholders));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
