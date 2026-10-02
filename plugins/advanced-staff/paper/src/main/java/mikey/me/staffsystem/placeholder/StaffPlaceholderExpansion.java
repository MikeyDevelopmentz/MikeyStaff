package mikey.me.staffsystem.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import mikey.me.staffsystem.cache.FreezeState;
import mikey.me.staffsystem.database.models.PlayerNote;
import mikey.me.staffsystem.managers.FreezeManager;
import mikey.me.staffsystem.managers.NotesManager;
import mikey.me.staffsystem.managers.PunishmentManager;
import mikey.me.staffsystem.managers.VanishManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class StaffPlaceholderExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final VanishManager vanishManager;
    private final FreezeManager freezeManager;
    private final NotesManager notesManager;
    private final PunishmentManager punishmentManager;

    public StaffPlaceholderExpansion(Plugin plugin, VanishManager vanishManager, FreezeManager freezeManager, NotesManager notesManager, PunishmentManager punishmentManager) {
        this.plugin = plugin;
        this.vanishManager = vanishManager;
        this.freezeManager = freezeManager;
        this.notesManager = notesManager;
        this.punishmentManager = punishmentManager;
    }

    @Override
    public String getIdentifier() {
        return "staff";
    }

    @Override
    public String getAuthor() {
        return String.join(",", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        Player player = offlinePlayer != null ? offlinePlayer.getPlayer() : null;
        if (player == null) {
            // null = leave the placeholder alone, dont blank it out
            return null;
        }
        String key = params.toLowerCase();

        if (key.equals("vanish_status")) {
            return vanishManager.isVanished(player.getUniqueId()) ? "vanished" : "visible";
        }
        if (key.equals("vanish_player")) {
            return vanishManager.isVanished(player.getUniqueId()) ? player.getName() : "";
        }
        if (key.equals("vanish_staff")) {
            if (!vanishManager.isVanished(player.getUniqueId())) {
                return "";
            }
            if (vanishManager.getVanishState(player.getUniqueId()) == null) {
                return "";
            }
            return Bukkit.getOfflinePlayer(vanishManager.getVanishState(player.getUniqueId()).getStaffUuid()).getName();
        }

        if (key.equals("frozen_player")) {
            return freezeManager.isFrozen(player.getUniqueId()) ? player.getName() : "";
        }
        // isFrozen() is fail-closed: true for everyone while the manager is down, so read the state map once instead of re-deriving it
        if (key.equals("frozen_staff") || key.equals("freeze_reason")
                || key.equals("freeze_time_total") || key.equals("freeze_time_elapsed")) {
            if (!freezeManager.isFrozen(player.getUniqueId())) {
                return "";
            }
            FreezeState state = freezeManager.getFrozen().get(player.getUniqueId());
            if (state == null) {
                return "";
            }
            if (key.equals("frozen_staff")) {
                return Bukkit.getOfflinePlayer(state.getStaffUuid()).getName();
            }
            if (key.equals("freeze_reason")) {
                return state.getReason();
            }
            if (key.equals("freeze_time_total")) {
                return String.valueOf(state.getDurationSeconds());
            }
            long seconds = (System.currentTimeMillis() - state.getStartTime()) / 1000L;
            return String.valueOf(seconds);
        }
        if (key.equals("freeze_time_remaining")) {
            return freezeManager.getRemainingFormatted(player.getUniqueId());
        }
        if (key.equals("ban_time_remaining") || key.equals("staff_ban_time_remaining")) {
            if (!punishmentManager.isBannedCached(player.getUniqueId())) {
                return "";
            }
            return punishmentManager.getBanRemainingFormatted(player.getUniqueId());
        }

        if (key.equals("target_name")) {
            return player.getName();
        }
        if (key.equals("target_gamemode")) {
            GameMode mode = player.getGameMode();
            return mode != null ? mode.name() : "";
        }
        if (key.equals("target_health")) {
            return String.valueOf(player.getHealth());
        }
        if (key.equals("target_ping")) {
            return String.valueOf(player.getPing());
        }
        if (key.equals("target_location_world")) {
            return player.getWorld().getName();
        }
        if (key.equals("target_location_x")) {
            return String.valueOf(player.getLocation().getBlockX());
        }
        if (key.equals("target_location_y")) {
            return String.valueOf(player.getLocation().getBlockY());
        }
        if (key.equals("target_location_z")) {
            return String.valueOf(player.getLocation().getBlockZ());
        }
        if (key.equals("target_uuid")) {
            UUID uuid = player.getUniqueId();
            return uuid.toString();
        }

        if (key.equals("note_text")) {
            PlayerNote latest = notesManager.getLatestNote(player.getUniqueId());
            return latest != null ? latest.getText() : "";
        }
        if (key.equals("note_staff")) {
            PlayerNote latest = notesManager.getLatestNote(player.getUniqueId());
            if (latest == null) {
                return "";
            }
            return Bukkit.getOfflinePlayer(latest.getStaffUuid()).getName();
        }
        if (key.equals("note_id")) {
            PlayerNote latest = notesManager.getLatestNote(player.getUniqueId());
            return latest != null ? String.valueOf(latest.getId()) : "";
        }
        if (key.equals("note_date")) {
            PlayerNote latest = notesManager.getLatestNote(player.getUniqueId());
            if (latest == null) {
                return "";
            }
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH);
            return format.format(new Date(latest.getCreatedAt()));
        }

        // null = unknown placeholder, papi leaves it untouched
        return null;
    }
}
