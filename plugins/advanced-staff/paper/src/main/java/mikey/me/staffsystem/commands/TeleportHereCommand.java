package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.TeleportManager;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public class TeleportHereCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final TeleportManager teleportManager;
    private final TextUtil textUtil;

    public TeleportHereCommand(ConfigurationManager configurationManager, TeleportManager teleportManager, TextUtil textUtil) {
        this.settings = configurationManager.getSettings();
        this.teleportManager = teleportManager;
        this.textUtil = textUtil;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(textUtil.prefixed("errors.in-game-only"));
            return true;
        }
        Player player = (Player) sender;
        String permission = settings.getPermission("teleport.tphere");
        if (!player.hasPermission(permission)) {
            player.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(textUtil.prefixed("errors.player-not-found"));
            return true;
        }
        if (!teleportManager.teleportHere(player, target)) {
            player.sendMessage(textUtil.prefixed("teleport.failed"));
            return true;
        }
        player.sendMessage(textUtil.papi(target, "teleport.tphere", Map.of("%staff_target_name%", target.getName())));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !TabCompletion.allowed(sender, settings.getPermission("teleport.tphere"))) return List.of();
        return TabCompletion.visiblePlayers((Player) sender, args[0]);
    }
}
