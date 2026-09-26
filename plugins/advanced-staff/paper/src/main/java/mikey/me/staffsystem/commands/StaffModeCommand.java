package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.cache.StaffModeState;
import mikey.me.staffsystem.managers.StaffModeManager;
import mikey.me.staffsystem.managers.VanishManager;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public class StaffModeCommand implements CommandExecutor, TabCompleter {

    private final ConfigurationManager configurationManager;
    private final SettingsConfig settings;
    private final StaffModeManager staffModeManager;
    private final VanishManager vanishManager;
    private final TextUtil textUtil;

    public StaffModeCommand(ConfigurationManager configurationManager, StaffModeManager staffModeManager, VanishManager vanishManager, TextUtil textUtil) {
        this.configurationManager = configurationManager;
        this.settings = configurationManager.getSettings();
        this.staffModeManager = staffModeManager;
        this.vanishManager = vanishManager;
        this.textUtil = textUtil;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            String permission = settings.getPermission("staffmode.reload");
            if (!sender.hasPermission(permission)) {
                sender.sendMessage(textUtil.prefixed("errors.no-permission"));
                return true;
            }
            configurationManager.reloadAll();
            sender.sendMessage(textUtil.prefixed("staffmode.reloaded"));
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(textUtil.prefixed("errors.in-game-only"));
            return true;
        }
        Player player = (Player) sender;
        String permission = settings.getPermission("staffmode.use");
        if (!player.hasPermission(permission)) {
            player.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        boolean enabled = !staffModeManager.isInStaffMode(player.getUniqueId());
        if (enabled) {
            boolean vanishedBeforeStaffMode = vanishManager.isVanished(player.getUniqueId());
            staffModeManager.enableAsync(player, vanishedBeforeStaffMode).whenComplete((result, error) -> {
                if (error != null || !Boolean.TRUE.equals(result)) {
                    if (player.isOnline()) {
                        player.sendMessage(ChatColor.RED + "Couldn't turn on staff mode.");
                    }
                    return;
                }
                if (settings.isStaffModeAutoVanish()) {
                    vanishManager.setVanished(player, player, true);
                }
                if (player.isOnline()) {
                    player.sendMessage(textUtil.prefixed("staffmode.on"));
                }
            });
        } else {
            StaffModeState state = staffModeManager.getState(player.getUniqueId());
            if (!staffModeManager.disable(player)) {
                player.sendMessage(ChatColor.RED + "Inventory restore failed, you're still in staff mode.");
                return true;
            }
            if (settings.isStaffModeAutoVanish()
                    && state != null
                    && !state.wasVanishedBeforeStaffMode()) {
                vanishManager.setVanished(player, player, false);
            }
            player.sendMessage(textUtil.prefixed("staffmode.off"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !TabCompletion.allowed(sender, settings.getPermission("staffmode.reload"))) return List.of();
        return TabCompletion.matching(List.of("reload"), args[0]);
    }
}
