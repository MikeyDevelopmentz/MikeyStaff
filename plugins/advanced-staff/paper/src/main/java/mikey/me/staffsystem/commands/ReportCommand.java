package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.ReportManager;
import mikey.me.staffsystem.utils.NetworkPlayerResolver;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ReportCommand implements CommandExecutor, TabCompleter, Listener {

    private final SettingsConfig settings;
    private final ReportManager reportManager;
    private final TextUtil textUtil;
    private final Plugin plugin;
    private final NetworkPlayerResolver networkPlayerResolver;
    private final Map<UUID, Long> lastReportAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastReportPerTargetAt = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> pendingReports = ConcurrentHashMap.newKeySet();

    public ReportCommand(ConfigurationManager configurationManager, ReportManager reportManager,
            TextUtil textUtil, Plugin plugin, NetworkPlayerResolver networkPlayerResolver) {
        this.settings = configurationManager.getSettings();
        this.reportManager = reportManager;
        this.textUtil = textUtil;
        this.plugin = plugin;
        this.networkPlayerResolver = networkPlayerResolver;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(textUtil.prefixed("errors.in-game-only"));
            return true;
        }
        Player reporter = (Player) sender;
        String permission = settings.getPermission("reports.report");
        if (permission != null && !permission.isEmpty() && !reporter.hasPermission(permission)) {
            reporter.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (args.length < 2) {
            reporter.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }
        OfflinePlayer target = networkPlayerResolver.resolveOfflinePlayer(args[0]);
        if (target == null) {
            reporter.sendMessage(textUtil.prefixed("errors.player-not-found"));
            return true;
        }
        if (reporter.getUniqueId().equals(target.getUniqueId())) {
            reporter.sendMessage(textUtil.prefixed("errors.self-report"));
            return true;
        }
        UUID reporterId = reporter.getUniqueId();
        UUID reportedId = target.getUniqueId();

        long now = System.currentTimeMillis();
        long generalCooldownMs = Math.max(0L, settings.getReportCooldownSeconds() * 1000L);
        long sameTargetCooldownMs = Math.max(0L, settings.getReportSameTargetCooldownSeconds() * 1000L);
        pruneCooldowns(now, Math.max(generalCooldownMs, sameTargetCooldownMs));
        Long lastGeneral = lastReportAt.get(reporter.getUniqueId());
        if (lastGeneral != null && (now - lastGeneral) < generalCooldownMs) {
            long remaining = (generalCooldownMs - (now - lastGeneral) + 999) / 1000;
            Map<String, String> ph = new HashMap<>();
            ph.put("%seconds%", String.valueOf(remaining));
            reporter.sendMessage(textUtil.format(textUtil.prefixed("reports.cooldown"), ph));
            return true;
        }

        String pairKey = reporter.getUniqueId() + ":" + target.getUniqueId();
        Long lastSameTarget = lastReportPerTargetAt.get(pairKey);
        if (lastSameTarget != null && (now - lastSameTarget) < sameTargetCooldownMs) {
            long remaining = (sameTargetCooldownMs - (now - lastSameTarget) + 999) / 1000;
            Map<String, String> ph = new HashMap<>();
            ph.put("%target_name%", target.getName() == null ? args[0] : target.getName());
            ph.put("%seconds%", String.valueOf(remaining));
            reporter.sendMessage(textUtil.format(textUtil.prefixed("reports.duplicate"), ph));
            return true;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (builder.length() > 0) builder.append(" ");
            builder.append(args[i]);
        }
        String reason = builder.toString();
        if (!pendingReports.add(reporter.getUniqueId())) {
            reporter.sendMessage(ChatColor.YELLOW + "Your last report is still sending, hang on.");
            return true;
        }

        String targetName = target.getName() == null ? args[0] : target.getName();
        reportManager.createReport(reporterId, reportedId, reason).whenComplete((report, reportError) ->
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                pendingReports.remove(reporter.getUniqueId());
                boolean reporterStillOnline = reporter.isOnline()
                    && Bukkit.getPlayer(reporter.getUniqueId()) == reporter;
                if (reportError != null || report == null) {
                    Throwable cause = reportError == null
                        ? new IllegalStateException("Report repository returned no report")
                        : unwrapCompletionError(reportError);
                    plugin.getLogger().log(Level.SEVERE, "Failed to submit player report", cause);
                    if (reporterStillOnline) {
                        reporter.sendMessage(ChatColor.RED + "Couldn't submit report, try again.");
                    }
                    return;
                }

                long submittedAt = System.currentTimeMillis();
                lastReportAt.put(reporter.getUniqueId(), submittedAt);
                lastReportPerTargetAt.put(pairKey, submittedAt);
                if (reporterStillOnline) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("%target_name%", targetName);
                    placeholders.put("%report_reason%", reason);
                    reporter.sendMessage(textUtil.format(textUtil.prefixed("reports.submitted"), placeholders));
                }

                String staffMessage = ChatColor.RED + "[Report] " + ChatColor.WHITE + targetName
                    + ChatColor.GRAY + " reported by " + ChatColor.WHITE + reporter.getName()
                    + ChatColor.DARK_GRAY + " - " + ChatColor.YELLOW + reason;
                String notifyPermission = settings.getPermission("reports.view");
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getUniqueId().equals(reporter.getUniqueId())) continue;
                    if (notifyPermission != null && !notifyPermission.isEmpty()
                            && !online.hasPermission(notifyPermission)) continue;
                    online.sendMessage(staffMessage);
                }
            }));

        return true;
    }

    private void pruneCooldowns(long now, long retentionMs) {
        long cutoff = now - retentionMs;
        lastReportAt.entrySet().removeIf(entry -> entry.getValue() <= cutoff);
        lastReportPerTargetAt.entrySet().removeIf(entry -> entry.getValue() <= cutoff);
    }

    private static Throwable unwrapCompletionError(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastReportAt.remove(playerId);
        String prefix = playerId + ":";
        lastReportPerTargetAt.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!(sender instanceof Player)) return result;
        Player reporter = (Player) sender;
        String permission = settings.getPermission("reports.report");
        if (permission != null && !permission.isEmpty() && !reporter.hasPermission(permission)) return result;
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!reporter.canSee(online)) continue;
                if (online.getName().toLowerCase().startsWith(input)) result.add(online.getName());
            }
        }
        return result;
    }
}
