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

public class VanishCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final VanishManager vanishManager;
    private final TextUtil textUtil;

    public VanishCommand(ConfigurationManager configurationManager, VanishManager vanishManager, TextUtil textUtil) {
        this.settings = configurationManager.getSettings();
        this.vanishManager = vanishManager;
        this.textUtil = textUtil;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(textUtil.prefixed("errors.in-game-only"));
            return true;
        }
        Player player = (Player) sender;
        if (args.length == 0) {
            if (!settings.permits(player, "vanish.self")) {
                player.sendMessage(textUtil.prefixed("errors.no-permission"));
                return true;
            }
            boolean vanished = vanishManager.toggle(player, player);
            String key = vanished ? "vanish.self-on" : "vanish.self-off";
            player.sendMessage(textUtil.prefixed(key));
            return true;
        }
        if (!settings.permits(player, "vanish.other")) {
            player.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(textUtil.prefixed("errors.player-not-found"));
            return true;
        }
        boolean vanished = vanishManager.toggle(player, target);
        String key = vanished ? "vanish.other-on" : "vanish.other-off";
        String template = textUtil.papi(target, key, Map.of(
                "%staff_vanish_player%", target.getName(),
                "%staff_vanish_staff%", player.getName()));
        player.sendMessage(template);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !TabCompletion.allowed(sender, settings.getPermission("vanish.other"))) return List.of();
        return TabCompletion.visiblePlayers((Player) sender, args[0]);
    }
}
