package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.InspectManager;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public class InvseeCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final InspectManager inspectManager;
    private final TextUtil textUtil;

    public InvseeCommand(ConfigurationManager configurationManager, InspectManager inspectManager, TextUtil textUtil) {
        this.settings = configurationManager.getSettings();
        this.inspectManager = inspectManager;
        this.textUtil = textUtil;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(textUtil.prefixed("errors.in-game-only"));
            return true;
        }
        Player staff = (Player) sender;
        String permission = settings.getPermission("inventory.invsee");
        if (!staff.hasPermission(permission)) {
            staff.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            staff.sendMessage(textUtil.prefixed("errors.player-not-found"));
            return true;
        }
        inspectManager.openInventory(staff, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !TabCompletion.allowed(sender, settings.getPermission("inventory.invsee"))) return List.of();
        return TabCompletion.visiblePlayers((Player) sender, args[0]);
    }
}
