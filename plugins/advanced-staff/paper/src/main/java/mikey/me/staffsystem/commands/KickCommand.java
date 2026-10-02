package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.PunishmentPresetsConfig;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.PunishmentManager;
import mikey.me.staffsystem.utils.NetworkPlayerResolver;
import mikey.me.staffsystem.utils.SchedulerProvider;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class KickCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final PunishmentManager punishmentManager;
    private final TextUtil textUtil;
    private final PunishmentPresetsConfig punishmentPresets;
    private final NetworkPlayerResolver networkPlayerResolver;
    private final SchedulerProvider schedulerProvider;

    public KickCommand(ConfigurationManager configurationManager, PunishmentManager punishmentManager,
            TextUtil textUtil, NetworkPlayerResolver networkPlayerResolver, SchedulerProvider schedulerProvider) {
        this.settings = configurationManager.getSettings();
        this.punishmentManager = punishmentManager;
        this.textUtil = textUtil;
        this.punishmentPresets = configurationManager.getPunishments();
        this.networkPlayerResolver = networkPlayerResolver;
        this.schedulerProvider = schedulerProvider;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!settings.permits(sender, "punishments.kick")) {
            sender.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "CONSOLE";
        networkPlayerResolver.resolvePlayerId(args[0]).whenComplete((uuid, lookupError) ->
            schedulerProvider.runSync(() -> {
                if (sender instanceof Player player && (!player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player)) return;
                if (lookupError != null) {
                    sender.sendMessage(textUtil.prefixed("errors.database-error"));
                    return;
                }
                execute(sender, networkPlayerResolver.getOfflinePlayer(uuid), args, staffName);
            }));
        return true;
    }

    private boolean execute(CommandSender sender, OfflinePlayer target, String[] args, String staffName) {
        if (!settings.permits(sender, "punishments.kick")) {
            sender.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (target == null
                || (!(target instanceof Player)
                    && !networkPlayerResolver.isKnownPlayerName(args[0]))) {
            sender.sendMessage(textUtil.prefixed("errors.player-not-found"));
            return true;
        }
        String reason = "";
        if (args.length >= 2) {
            StringBuilder builder = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append(args[i]);
            }
            reason = builder.toString();
        }
        String issuedReason = reason;
        punishmentManager.kick(sender, target, issuedReason).whenComplete((ignored, error) ->
            schedulerProvider.runSync(() -> {
                if (error != null) {
                    sender.sendMessage(textUtil.prefixed("errors.database-error"));
                    return;
                }
                String targetName = target.getName() == null ? args[0] : target.getName();
                String reasonText = issuedReason.isEmpty() ? "None" : issuedReason;
                java.util.Map<String, String> placeholders = new java.util.HashMap<>();
                placeholders.put("%target_name%", targetName);
                placeholders.put("%staff_name%", staffName);
                placeholders.put("%reason%", reasonText);
                String message = textUtil.papi(target instanceof Player ? (Player) target : null,
                    "punishments.kick-issued");
                for (java.util.Map.Entry<String, String> entry : placeholders.entrySet()) {
                    message = message.replace(entry.getKey(), entry.getValue());
                }
                sender.sendMessage(message);
            }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!(sender instanceof Player)) {
            return result;
        }
        Player staff = (Player) sender;
        String permission = settings.getPermission("punishments.kick");
        if (permission != null && !permission.isEmpty() && !staff.hasPermission(permission)) {
            return result;
        }
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.equals(staff)) {
                    continue;
                }
                String name = online.getName();
                if (name.toLowerCase().startsWith(input)) {
                    result.add(name);
                }
            }
            for (String name : networkPlayerResolver.getCachedNetworkPlayerNames()) {
                if (!name.equalsIgnoreCase(staff.getName())
                        && name.toLowerCase().startsWith(input)
                        && !result.contains(name)) {
                    result.add(name);
                }
            }
        } else if (args.length >= 2) {
            StringBuilder builder = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append(args[i]);
            }
            String prefix = builder.toString().toLowerCase();
            for (String reasonPreset : punishmentPresets.getReasonPresets()) {
                if (reasonPreset.toLowerCase().startsWith(prefix)) {
                    result.add(reasonPreset);
                }
            }
        }
        return result;
    }
}
