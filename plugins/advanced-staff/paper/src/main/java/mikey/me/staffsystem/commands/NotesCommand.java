package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.models.PlayerNote;
import mikey.me.staffsystem.managers.NotesManager;
import mikey.me.staffsystem.utils.NetworkPlayerResolver;
import mikey.me.staffsystem.utils.TextUtil;
import mikey.me.staffsystem.utils.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class NotesCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 10;
    private final SettingsConfig settings;
    private final NotesManager notesManager;
    private final TextUtil textUtil;
    private final TimeUtil timeUtil;
    private final Plugin plugin;
    private final NetworkPlayerResolver networkPlayerResolver;

    public NotesCommand(ConfigurationManager configurationManager, NotesManager notesManager, TextUtil textUtil,
            Plugin plugin, NetworkPlayerResolver networkPlayerResolver) {
        this.settings = configurationManager.getSettings();
        this.notesManager = notesManager;
        this.textUtil = textUtil;
        this.timeUtil = new TimeUtil();
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
        if (!settings.permits(staff, "notes.view")) {
            staff.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (target == null) target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            staff.sendMessage(textUtil.prefixed("errors.player-not-found"));
            return true;
        }
        int requestedPage = 1;
        if (args.length >= 2) {
            try {
                requestedPage = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                requestedPage = 0;
            }
            if (requestedPage < 1) {
                staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return true;
            }
        }
        final int page = requestedPage;
        final String targetName = target.getName() == null ? "Unknown" : target.getName();
        notesManager.getNotes(target.getUniqueId()).whenComplete((list, error) -> {
            if (error != null) {
                reportFailure(staff, error);
                return;
            }
            runOnMainThread(() -> {
                if (!staff.isOnline()) {
                    return;
                }
                if (list == null || list.isEmpty()) {
                    staff.sendMessage(textUtil.prefixed("notes.none"));
                    return;
                }
                Map<String, String> headerPlaceholders = new HashMap<>();
                headerPlaceholders.put("%target_name%", targetName);
                staff.sendMessage(textUtil.format(textUtil.prefixed("notes.header"), headerPlaceholders));
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH);
                int pages = (list.size() + PAGE_SIZE - 1) / PAGE_SIZE;
                int shownPage = Math.min(page, pages);
                int from = (shownPage - 1) * PAGE_SIZE;
                for (PlayerNote note : list.subList(from, Math.min(list.size(), from + PAGE_SIZE))) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("%note_id%", String.valueOf(note.getId()));
                    placeholders.put("%note_text%", note.getText());
                    placeholders.put("%note_staff%", networkPlayerResolver.resolveName(note.getStaffUuid(), "Unknown"));
                    placeholders.put("%note_date%", format.format(new Date(note.getCreatedAt())));
                    String line = textUtil.format(textUtil.prefixed("notes.entry"), placeholders);
                    staff.sendMessage(line);
                }
                if (pages > 1) {
                    Map<String, String> pagePlaceholders = new HashMap<>();
                    pagePlaceholders.put("%page%", String.valueOf(shownPage));
                    pagePlaceholders.put("%pages%", String.valueOf(pages));
                    pagePlaceholders.put("%target_name%", targetName);
                    staff.sendMessage(textUtil.format(textUtil.prefixed("notes.page"), pagePlaceholders));
                }
            });
        });
        return true;
    }

    private void runOnMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        }
    }

    private void reportFailure(Player staff, Throwable error) {
        plugin.getLogger().warning("Note operation failed: " + error.getMessage());
        runOnMainThread(() -> {
            if (staff.isOnline()) {
                staff.sendMessage(textUtil.prefixed("errors.database-error"));
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!(sender instanceof Player)) {
            return result;
        }
        Player staff = (Player) sender;
        if (!settings.permits(staff, "notes.view")) {
            return result;
        }
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) {
                String name = online.getName();
                if (name.toLowerCase().startsWith(input)) {
                    result.add(name);
                }
            }
        }
        return result;
    }
}
