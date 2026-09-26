package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.models.PlayerReport;
import mikey.me.staffsystem.managers.ReportManager;
import mikey.me.staffsystem.managers.ReportsGuiManager;
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
import org.bukkit.plugin.Plugin;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public class ReportsCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 10;
    private final SettingsConfig settings;
    private final ReportManager reportManager;
    private final ReportsGuiManager reportsGuiManager;
    private final TextUtil textUtil;
    private final Plugin plugin;
    private final NetworkPlayerResolver networkPlayerResolver;

    public ReportsCommand(ConfigurationManager configurationManager, ReportManager reportManager, TextUtil textUtil,
            ReportsGuiManager reportsGuiManager, Plugin plugin, NetworkPlayerResolver networkPlayerResolver) {
        this.settings = configurationManager.getSettings();
        this.reportManager = reportManager;
        this.textUtil = textUtil;
        this.reportsGuiManager = reportsGuiManager;
        this.plugin = plugin;
        this.networkPlayerResolver = networkPlayerResolver;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(textUtil.prefixed("errors.in-game-only"));
            return true;
        }
        Player staff = (Player) sender;
        String viewPermission = settings.getPermission("reports.view");
        if (viewPermission != null && !viewPermission.isEmpty() && !staff.hasPermission(viewPermission)) {
            staff.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (args.length == 0) {
            if (reportsGuiManager != null) {
                reportsGuiManager.openMain(staff);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ENGLISH);
        if (sub.equals("gui")) {
            if (reportsGuiManager != null) {
                reportsGuiManager.openMain(staff);
            }
            return true;
        }
        if (sub.equals("clear")) {
            String clearPermission = settings.getPermission("reports.clear");
            if (clearPermission != null && !clearPermission.isEmpty() && !staff.hasPermission(clearPermission)) {
                staff.sendMessage(textUtil.prefixed("errors.no-permission"));
                return true;
            }
            if (args.length < 2) {
                staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return true;
            }
            OfflinePlayer target = resolvePlayer(args[1]);
            if (target == null) {
                staff.sendMessage(textUtil.prefixed("errors.player-not-found"));
                return true;
            }
            UUID reporterUuid = target.getUniqueId();
            String targetName = target.getName() == null ? "Unknown" : target.getName();
            reportManager.clearReportsByReporter(reporterUuid).whenComplete((ignored, error) ->
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    if (!staff.isOnline() || Bukkit.getPlayer(staff.getUniqueId()) != staff) return;
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to clear reports for " + reporterUuid,
                            unwrapCompletionError(error));
                        staff.sendMessage(ChatColor.RED + "[Reports] Failed to clear reports.");
                        return;
                    }
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("%target_name%", targetName);
                    String message = textUtil.format(textUtil.prefixed("reports.cleared"), placeholders);
                    staff.sendMessage(message);
                }));
            return true;
        }
        if (sub.equals("list")) {
            if (args.length < 2) {
                staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return true;
            }
            OfflinePlayer target = resolvePlayer(args[1]);
            if (target == null) {
                staff.sendMessage(textUtil.prefixed("errors.player-not-found"));
                return true;
            }
            int page = parsePage(args, 2);
            if (page < 1) {
                staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return true;
            }
            handleList(staff, target, page);
            return true;
        }
        OfflinePlayer target = resolvePlayer(args[0]);
        if (target == null) {
            staff.sendMessage(textUtil.prefixed("errors.player-not-found"));
            return true;
        }
        int page = parsePage(args, 1);
        if (page < 1) {
            staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }
        handleList(staff, target, page);
        return true;
    }

    private int parsePage(String[] args, int index) {
        if (args.length <= index) return 1;
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
    }

    private void handleList(Player staff, OfflinePlayer target, int page) {
        reportManager.getReports(target.getUniqueId()).whenComplete((list, error) ->
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                if (!staff.isOnline() || Bukkit.getPlayer(staff.getUniqueId()) != staff) return;
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to load reports for " + target.getUniqueId(),
                        unwrapCompletionError(error));
                    staff.sendMessage(ChatColor.RED + "[Reports] Failed to load reports.");
                    return;
                }
                if (list.isEmpty()) {
                    Map<String, String> nonePh = new HashMap<>();
                    nonePh.put("%target_name%", target.getName() == null ? "Unknown" : target.getName());
                    staff.sendMessage(textUtil.format(textUtil.prefixed("reports.none"), nonePh));
                    return;
                }
                Map<String, String> headerPlaceholders = new HashMap<>();
                headerPlaceholders.put("%target_name%", target.getName() == null ? "Unknown" : target.getName());
                staff.sendMessage(textUtil.format(textUtil.prefixed("reports.header"), headerPlaceholders));
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH);
                int pages = (list.size() + PAGE_SIZE - 1) / PAGE_SIZE;
                int shownPage = Math.min(page, pages);
                int from = (shownPage - 1) * PAGE_SIZE;
                for (PlayerReport report : list.subList(from, Math.min(list.size(), from + PAGE_SIZE))) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("%report_id%", String.valueOf(report.getId()));
                    placeholders.put("%report_reason%", report.getReason());
                    placeholders.put("%report_reporter%", networkPlayerResolver.resolveName(report.getReporterUuid(), "Unknown"));
                    placeholders.put("%report_date%", format.format(new Date(report.getCreatedAt())));
                    String line = textUtil.format(textUtil.prefixed("reports.entry"), placeholders);
                    staff.sendMessage(line);
                }
                if (pages > 1) {
                    Map<String, String> pagePlaceholders = new HashMap<>();
                    pagePlaceholders.put("%page%", String.valueOf(shownPage));
                    pagePlaceholders.put("%pages%", String.valueOf(pages));
                    pagePlaceholders.put("%target_name%", target.getName() == null ? "Unknown" : target.getName());
                    staff.sendMessage(textUtil.format(textUtil.prefixed("reports.page"), pagePlaceholders));
                }
            }));
    }

    private static Throwable unwrapCompletionError(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!(sender instanceof Player)) {
            return result;
        }
        Player staff = (Player) sender;
        String permission = settings.getPermission("reports.view");
        if (permission != null && !permission.isEmpty() && !staff.hasPermission(permission)) {
            return result;
        }
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ENGLISH);
            if ("gui".startsWith(input)) {
                result.add("gui");
            }
            if ("list".startsWith(input)) {
                result.add("list");
            }
            String clearPermission = settings.getPermission("reports.clear");
            if (clearPermission != null && !clearPermission.isEmpty() && staff.hasPermission(clearPermission)) {
                if ("clear".startsWith(input)) {
                    result.add("clear");
                }
            }
        } else if ((args.length == 2 && args[0].equalsIgnoreCase("clear")) || (args.length == 2 && args[0].equalsIgnoreCase("list"))) {
            if (args[0].equalsIgnoreCase("clear")) {
                String clearPermission = settings.getPermission("reports.clear");
                if (clearPermission != null && !clearPermission.isEmpty() && !staff.hasPermission(clearPermission)) {
                    return result;
                }
            }
            String input = args[1].toLowerCase(Locale.ENGLISH);
            // cached only, never block tab completion on a db query
            for (UUID uuid : reportManager.getCachedReporters()) {
                OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
                String name = off.getName();
                if (name != null && name.toLowerCase(Locale.ENGLISH).startsWith(input) && !result.contains(name)) {
                    result.add(name);
                }
            }
        }
        return result;
    }
}
