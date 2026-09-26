package mikey.me.staffsystem.commands;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.FreezeManager;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UnfreezeCommand implements CommandExecutor, TabCompleter {

    private final SettingsConfig settings;
    private final FreezeManager freezeManager;
    private final TextUtil textUtil;

    public UnfreezeCommand(ConfigurationManager configurationManager, FreezeManager freezeManager, TextUtil textUtil) {
        this.settings = configurationManager.getSettings();
        this.freezeManager = freezeManager;
        this.textUtil = textUtil;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(textUtil.prefixed("errors.in-game-only"));
            return true;
        }
        Player staff = (Player) sender;
        String permission = settings.getPermission("freeze.use");
        if (!staff.hasPermission(permission)) {
            staff.sendMessage(textUtil.prefixed("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
            return true;
        }
        UUID targetId = parseUuid(args[0]);
        if (targetId == null) {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (cached == null) {
                staff.sendMessage(textUtil.prefixed("errors.player-not-found"));
                return true;
            }
            targetId = cached.getUniqueId();
        }
        final UUID resolvedTargetId = targetId;
        String targetName = resolveTargetName(resolvedTargetId, args[0]);
        freezeManager.unfreeze(resolvedTargetId).whenComplete((unfrozen, error) -> {
            if (!staff.isOnline() || Bukkit.getPlayer(staff.getUniqueId()) != staff) return;
            if (error != null) {
                staff.sendMessage(textUtil.prefixed("errors.database-error"));
                return;
            }
            if (!unfrozen) {
                staff.sendMessage(textUtil.prefixed("freeze.not-frozen"));
                return;
            }
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("%frozen_player%", targetName);
            placeholders.put("%frozen_staff%", staff.getName());
            String unfrozenTemplate = textUtil.format(textUtil.prefixed("freeze.unfrozen"), placeholders);
            String notifyTemplate = textUtil.format(textUtil.prefixed("freeze.staff-unfreeze"), placeholders);
            Player onlineTarget = Bukkit.getPlayer(resolvedTargetId);
            if (onlineTarget != null) onlineTarget.sendMessage(unfrozenTemplate);
            staff.sendMessage(notifyTemplate);
        });
        return true;
    }

    private String resolveTargetName(UUID targetId, String fallback) {
        Player online = Bukkit.getPlayer(targetId);
        if (online != null) return online.getName();
        OfflinePlayer cached = Bukkit.getOfflinePlayer(targetId);
        if (cached != null && cached.getName() != null) return cached.getName();
        return fallback;
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !TabCompletion.allowed(sender, settings.getPermission("freeze.use"))) return List.of();
        List<String> frozenNames = new java.util.ArrayList<>();
        for (UUID uuid : freezeManager.getFrozen().keySet()) {
            Player online = Bukkit.getPlayer(uuid);
            String name = online != null ? online.getName() : Bukkit.getOfflinePlayer(uuid).getName();
            if (name != null) frozenNames.add(name);
        }
        return TabCompletion.matching(frozenNames, args[0]);
    }
}
