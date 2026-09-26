package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.models.PlayerNote;
import mikey.me.staffsystem.managers.NotesManager;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NoteCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final NotesManager notesManager;
    private final TextUtil textUtil;
    private final Plugin plugin;

    public NoteCommand(ConfigurationManager configurationManager, NotesManager notesManager, TextUtil textUtil,
            Plugin plugin) {
        this.settings = configurationManager.getSettings();
        this.notesManager = notesManager;
        this.textUtil = textUtil;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(textUtil.prefixed("errors.in-game-only"));
            return true;
        }
        Player staff = (Player) sender;
        if (args.length < 1) {
            staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("add")) {
            String permission = settings.getPermission("notes.add");
            if (!staff.hasPermission(permission)) {
                staff.sendMessage(textUtil.prefixed("errors.no-permission"));
                return true;
            }
            if (args.length < 3) {
                staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
            Player onlineTarget = Bukkit.getPlayer(args[1]);
            if (target == null) target = onlineTarget;
            if (target == null) {
                staff.sendMessage(textUtil.prefixed("errors.player-not-found"));
                return true;
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append(args[i]);
            }
        String text = builder.toString();
        String targetName = target.getName() == null ? "Unknown" : target.getName();
        notesManager.addNote(staff, target, text).whenComplete((note, error) -> {
            if (error != null) {
                reportFailure(staff, error);
                return;
            }
            runOnMainThread(() -> {
                if (!staff.isOnline()) {
                    return;
                }
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("%target_name%", targetName);
                placeholders.put("%note_id%", String.valueOf(note.getId()));
                String message = textUtil.format(textUtil.prefixed("notes.added"), placeholders);
                staff.sendMessage(message);
            });
        });
        return true;
        }
        if (sub.equals("remove")) {
            String permission = settings.getPermission("notes.remove");
            if (!staff.hasPermission(permission)) {
                staff.sendMessage(textUtil.prefixed("errors.no-permission"));
                return true;
            }
            if (args.length < 2) {
                staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return true;
            }
            try {
                long id = Long.parseLong(args[1]);
                notesManager.removeNote(id).whenComplete((removed, error) -> {
                    if (error != null) {
                        reportFailure(staff, error);
                        return;
                    }
                    runOnMainThread(() -> {
                        if (!staff.isOnline()) {
                            return;
                        }
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("%note_id%", String.valueOf(id));
                        String key = Boolean.TRUE.equals(removed) ? "notes.removed" : "notes.not-found";
                        String message = textUtil.format(textUtil.prefixed(key), placeholders);
                        staff.sendMessage(message);
                    });
                });
            } catch (NumberFormatException ignored) {
                staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            }
            return true;
        }
        String permission = settings.getPermission("notes.add");
        if (!staff.hasPermission(permission)) {
            staff.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (args.length < 2) {
            staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[0]);
        Player onlineTarget = Bukkit.getPlayer(args[0]);
        if (target == null) target = onlineTarget;
        if (target == null) {
            staff.sendMessage(textUtil.prefixed("errors.player-not-found"));
            return true;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(args[i]);
        }
        String text = builder.toString();
        String targetName = target.getName() == null ? "Unknown" : target.getName();
        notesManager.addNote(staff, target, text).whenComplete((note, error) -> {
                if (error != null) {
                    reportFailure(staff, error);
                    return;
                }
                runOnMainThread(() -> {
                    if (!staff.isOnline()) {
                        return;
                    }
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("%target_name%", targetName);
                    placeholders.put("%note_id%", String.valueOf(note.getId()));
                    String message = textUtil.format(textUtil.prefixed("notes.added"), placeholders);
                    staff.sendMessage(message);
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
        Player player = (Player) sender;
        String addPermission = settings.getPermission("notes.add");
        boolean canAdd = addPermission == null || addPermission.isEmpty() || player.hasPermission(addPermission);
        String removePermission = settings.getPermission("notes.remove");
        boolean canRemove = removePermission == null || removePermission.isEmpty() || player.hasPermission(removePermission);
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            if (canAdd && "add".startsWith(input)) {
                result.add("add");
            }
            if (canRemove && "remove".startsWith(input)) {
                result.add("remove");
            }
            if (canAdd) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    String name = online.getName();
                    if (name.toLowerCase().startsWith(input)) {
                        result.add(name);
                    }
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            if (!canAdd) {
                return result;
            }
            String input = args[1].toLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) {
                String name = online.getName();
                if (name.toLowerCase().startsWith(input)) {
                    result.add(name);
                }
            }
            return result;
        }
        return result;
    }
}
