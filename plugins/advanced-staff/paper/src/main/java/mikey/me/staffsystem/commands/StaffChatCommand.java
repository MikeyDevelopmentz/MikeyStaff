package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.StaffChatManager;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public class StaffChatCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final StaffChatManager staffChatManager;
    private final TextUtil textUtil;

    public StaffChatCommand(ConfigurationManager configurationManager, StaffChatManager staffChatManager, TextUtil textUtil) {
        this.settings = configurationManager.getSettings();
        this.staffChatManager = staffChatManager;
        this.textUtil = textUtil;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(textUtil.prefixed("errors.in-game-only"));
            return true;
        }
        Player player = (Player) sender;
        if (!settings.isStaffChatEnabled()) {
            player.sendMessage(textUtil.prefixed("staffchat.disabled"));
            return true;
        }
        if (!settings.permits(player, "staffchat")) {
            player.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (args.length == 0) {
            boolean enabled = staffChatManager.toggle(player);
            String key = enabled ? "staffchat.on" : "staffchat.off";
            player.sendMessage(textUtil.prefixed(key));
            return true;
        }
        StringBuilder builder = new StringBuilder();
        for (String arg : args) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(arg);
        }
        staffChatManager.sendStaffMessage(player, builder.toString());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0 || !TabCompletion.allowed(sender, settings.getPermission("staffchat"))) return List.of();
        return TabCompletion.visiblePlayers((Player) sender, args[args.length - 1]);
    }
}
