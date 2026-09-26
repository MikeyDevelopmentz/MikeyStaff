package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.PunishmentPresetsConfig;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.PunishmentManager;
import mikey.me.staffsystem.utils.NetworkPlayerResolver;
import mikey.me.staffsystem.utils.SchedulerProvider;
import mikey.me.staffsystem.utils.TextUtil;
import mikey.me.staffsystem.utils.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class MuteCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final PunishmentManager punishmentManager;
    private final TextUtil textUtil;
    private final TimeUtil timeUtil;
    private final PunishmentPresetsConfig punishmentPresets;
    private final NetworkPlayerResolver networkPlayerResolver;
    private final SchedulerProvider schedulerProvider;

    public MuteCommand(ConfigurationManager configurationManager, PunishmentManager punishmentManager,
            TextUtil textUtil, NetworkPlayerResolver networkPlayerResolver, SchedulerProvider schedulerProvider) {
        this.settings = configurationManager.getSettings();
        this.punishmentManager = punishmentManager;
        this.textUtil = textUtil;
        this.timeUtil = new TimeUtil();
        this.punishmentPresets = configurationManager.getPunishments();
        this.networkPlayerResolver = networkPlayerResolver;
        this.schedulerProvider = schedulerProvider;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String permission = settings.getPermission("punishments.mute");
        if (sender instanceof Player && !sender.hasPermission(permission)) {
            sender.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }
        String staffName = sender instanceof Player ? ((Player) sender).getName() : "CONSOLE";
        OfflinePlayer target = networkPlayerResolver.resolveOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage(textUtil.prefixed("errors.player-not-found"));
            return true;
        }
        try {
            if (punishmentManager.hasActiveMuteCached(target.getUniqueId())) {
                sender.sendMessage(textUtil.prefixed("punishments.already-muted"));
                return true;
            }
        } catch (RuntimeException e) {
            sender.sendMessage(textUtil.prefixed("errors.database-error"));
            return true;
        }
        long durationSeconds = 0L;
        String reason = "";
        if (args.length >= 2) {
            // dont silently perm-mute on a typo
            if (!timeUtil.isValidDuration(args[1])) {
                sender.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return true;
            }
            durationSeconds = timeUtil.parseDurationSeconds(args[1], 0L);
        }
        if (args.length >= 3) {
            StringBuilder builder = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append(args[i]);
            }
            reason = builder.toString();
        }
        long issuedDuration = durationSeconds;
        String issuedReason = reason;
        punishmentManager.mute(sender, target, issuedDuration, issuedReason)
            .whenComplete((ignored, error) -> schedulerProvider.runSync(() -> {
                if (PunishmentManager.isAlreadyActive(error)) {
                    sender.sendMessage(textUtil.prefixed("punishments.already-muted"));
                    return;
                }
                if (error != null) {
                    sender.sendMessage(textUtil.prefixed("errors.database-error"));
                    return;
                }
                String targetName = target.getName() == null ? args[0] : target.getName();
                String durationText = issuedDuration <= 0 ? "Permanent" : timeUtil.formatDuration(issuedDuration);
                String reasonText = issuedReason.isEmpty() ? "None" : issuedReason;

                java.util.Map<String, String> placeholders = new java.util.HashMap<>();
                placeholders.put("%target_name%", targetName);
                placeholders.put("%staff_name%", staffName);
                placeholders.put("%duration%", durationText);
                placeholders.put("%reason%", reasonText);
                String message = textUtil.papi(target, "punishments.mute-issued");
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
        String permission = settings.getPermission("punishments.mute");
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
        } else if (args.length == 2) {
            String input = args[1].toLowerCase();
            for (String preset : punishmentPresets.getTimePresets()) {
                if (preset.toLowerCase().startsWith(input)) {
                    result.add(preset);
                }
            }
        } else if (args.length >= 3) {
            StringBuilder builder = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
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
