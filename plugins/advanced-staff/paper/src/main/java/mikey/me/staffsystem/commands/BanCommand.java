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

public class BanCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final PunishmentManager punishmentManager;
    private final TextUtil textUtil;
    private final TimeUtil timeUtil;
    private final PunishmentPresetsConfig punishmentPresets;
    private final NetworkPlayerResolver networkPlayerResolver;
    private final SchedulerProvider schedulerProvider;

    public BanCommand(ConfigurationManager configurationManager, PunishmentManager punishmentManager,
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
        String permission = settings.getPermission("punishments.ban");
        if (sender instanceof Player && !sender.hasPermission(permission)) {
            sender.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }

        boolean silent = false;
        List<String> filteredArgs = new ArrayList<>();
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--silent")) {
                silent = true;
            } else {
                filteredArgs.add(arg);
            }
        }

        if (filteredArgs.isEmpty()) {
            sender.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }

        String staffName = sender instanceof Player ? ((Player) sender).getName() : "CONSOLE";
        OfflinePlayer target = networkPlayerResolver.resolveOfflinePlayer(filteredArgs.get(0));
        if (target == null) {
            sender.sendMessage(textUtil.prefixed("errors.player-not-found"));
            return true;
        }
        try {
            if (punishmentManager.isBannedCached(target.getUniqueId())) {
                sender.sendMessage(textUtil.prefixed("punishments.already-banned"));
                return true;
            }
        } catch (RuntimeException e) {
            sender.sendMessage(textUtil.prefixed("errors.database-error"));
            return true;
        }
        long durationSeconds = 0L;
        String reason = "";
        if (filteredArgs.size() >= 2) {
            // dont silently perm-ban on a typo
            if (!timeUtil.isValidDuration(filteredArgs.get(1))) {
                sender.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return true;
            }
            durationSeconds = timeUtil.parseDurationSeconds(filteredArgs.get(1), 0L);
        }
        if (filteredArgs.size() >= 3) {
            StringBuilder builder = new StringBuilder();
            for (int i = 2; i < filteredArgs.size(); i++) {
                if (builder.length() > 0) builder.append(" ");
                builder.append(filteredArgs.get(i));
            }
            reason = builder.toString();
        }
        long issuedDuration = durationSeconds;
        String issuedReason = reason;
        punishmentManager.ban(sender, target, issuedDuration, issuedReason, silent)
            .whenComplete((ignored, error) -> schedulerProvider.runSync(() -> {
                if (PunishmentManager.isAlreadyActive(error)) {
                    sender.sendMessage(textUtil.prefixed("punishments.already-banned"));
                    return;
                }
                if (error != null) {
                    sender.sendMessage(textUtil.prefixed("errors.database-error"));
                    return;
                }
                String targetName = target.getName() == null ? filteredArgs.get(0) : target.getName();
                String durationText = issuedDuration <= 0 ? "Permanent" : timeUtil.formatDuration(issuedDuration);
                String reasonText = issuedReason.isEmpty() ? "None" : issuedReason;

                java.util.Map<String, String> placeholders = new java.util.HashMap<>();
                placeholders.put("%target_name%", targetName);
                placeholders.put("%staff_name%", staffName);
                placeholders.put("%duration%", durationText);
                placeholders.put("%reason%", reasonText);
                String message = textUtil.papi(target, "punishments.ban-issued");
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
        String permission = settings.getPermission("punishments.ban");
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
            if ("--silent".startsWith(input)) result.add("--silent");
        } else if (args.length >= 3) {
            boolean alreadySilent = false;
            for (String arg : args) {
                if (arg.equalsIgnoreCase("--silent")) { alreadySilent = true; break; }
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                if (args[i].equalsIgnoreCase("--silent")) continue;
                if (builder.length() > 0) builder.append(" ");
                builder.append(args[i]);
            }
            String prefix = builder.toString().toLowerCase();
            if (!alreadySilent && "--silent".startsWith(args[args.length - 1].toLowerCase())) {
                result.add("--silent");
            }
            for (String reasonPreset : punishmentPresets.getReasonPresets()) {
                if (reasonPreset.toLowerCase().startsWith(prefix)) {
                    result.add(reasonPreset);
                }
            }
        }
        return result;
    }
}
