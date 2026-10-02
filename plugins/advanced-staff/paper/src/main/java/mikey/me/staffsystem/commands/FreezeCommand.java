package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.PunishmentPresetsConfig;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.FreezeManager;
import mikey.me.staffsystem.utils.TextUtil;
import mikey.me.staffsystem.utils.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

public class FreezeCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final FreezeManager freezeManager;
    private final TextUtil textUtil;
    private final TimeUtil timeUtil;
    private final PunishmentPresetsConfig timePresets;

    public FreezeCommand(ConfigurationManager configurationManager, FreezeManager freezeManager, TextUtil textUtil) {
        this.settings = configurationManager.getSettings();
        this.freezeManager = freezeManager;
        this.textUtil = textUtil;
        this.timeUtil = new TimeUtil();
        this.timePresets = configurationManager.getPunishments();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(textUtil.prefixed("errors.in-game-only"));
            return true;
        }
        Player staff = (Player) sender;
        if (!settings.permits(staff, "freeze.use")) {
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
        if (freezeManager.isFrozen(target.getUniqueId())) {
            staff.sendMessage(textUtil.prefixed("freeze.already-frozen"));
            return true;
        }

        long durationSeconds;
        if (args.length >= 2) {
            OptionalLong parsed = timeUtil.parseDurationSecondsStrict(args[1]);
            if (parsed.isEmpty()) {
                staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return true;
            }
            durationSeconds = parsed.getAsLong();
        } else {
            durationSeconds = settings.getFreezeDefaultDurationSeconds();
            if (!timeUtil.isValidConfiguredDuration(durationSeconds)) {
                staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return true;
            }
        }

        String reason = "";
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
        Map<String, String> placeholders = Map.of(
                "%staff_frozen_player%", target.getName(),
                "%staff_frozen_staff%", staff.getName(),
                "%staff_freeze_reason%", reason);
        freezeManager.freeze(staff, target, durationSeconds, reason).whenComplete((frozen, error) -> {
            if (error != null) {
                staff.sendMessage(textUtil.prefixed("errors.database-error"));
                return;
            }
            if (!frozen) {
                staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return;
            }
            String frozenTemplate = textUtil.papi(target, "freeze.frozen", placeholders);
            String notifyTemplate = textUtil.papi(target, "freeze.staff-freeze", placeholders);
            target.sendMessage(frozenTemplate);
            staff.sendMessage(notifyTemplate);
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!TabCompletion.allowed(sender, settings.getPermission("freeze.use"))) return List.of();
        if (args.length == 1) return TabCompletion.visiblePlayers((Player) sender, args[0]);
        if (args.length == 2) return TabCompletion.matching(timePresets.getTimePresets(), args[1]);
        return List.of();
    }
}
