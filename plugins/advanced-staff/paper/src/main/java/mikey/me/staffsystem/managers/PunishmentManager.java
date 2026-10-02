package mikey.me.staffsystem.managers;

import mikey.me.core.persistence.ActivePunishmentPolicy;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.models.PunishmentLog;
import mikey.me.staffsystem.database.repositories.PunishmentLogRepository;
import mikey.me.staffsystem.messaging.VelocityMessenger;
import mikey.me.core.json.JsonUtil;
import mikey.me.staffsystem.utils.NetworkPlayerResolver;
import mikey.me.staffsystem.utils.SchedulerProvider;
import mikey.me.staffsystem.utils.TextUtil;
import mikey.me.staffsystem.utils.TimeUtil;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PunishmentManager {

    public static final UUID CONSOLE_UUID = new UUID(0L, 0L);

    private static final String TYPE_BAN  = "BAN";
    private static final String TYPE_MUTE = "MUTE";
    private static final String TYPE_KICK = "KICK";

    private final PunishmentLogRepository repository;
    private final SchedulerProvider schedulerProvider;
    private final TextUtil textUtil;
    private final TimeUtil timeUtil;
    private final VelocityMessenger velocityMessenger;
    private final SettingsConfig settings;
    private final NetworkPlayerResolver networkPlayerResolver;

    private final Map<UUID, PunishmentLog> activeMutes;
    private final Map<UUID, PunishmentLog> activeBans;
    private final java.util.Set<UUID> knownUnmuted;
    private final java.util.Set<UUID> knownUnbanned;
    private final java.util.Set<UUID> pendingMutes;
    private final java.util.Set<UUID> pendingBans;
    private final Map<UUID, Long> muteRefreshVersions;
    private final Map<UUID, Long> banRefreshVersions;

    public PunishmentManager(PunishmentLogRepository repository, SchedulerProvider schedulerProvider,
            TextUtil textUtil, VelocityMessenger velocityMessenger, SettingsConfig settings,
            NetworkPlayerResolver networkPlayerResolver) {
        this.repository = repository;
        this.schedulerProvider = schedulerProvider;
        this.textUtil = textUtil;
        this.timeUtil = new TimeUtil();
        this.velocityMessenger = velocityMessenger;
        this.settings = settings;
        this.networkPlayerResolver = networkPlayerResolver;
        this.activeMutes = new ConcurrentHashMap<>();
        this.activeBans = new ConcurrentHashMap<>();
        this.knownUnmuted = ConcurrentHashMap.newKeySet();
        this.knownUnbanned = ConcurrentHashMap.newKeySet();
        this.pendingMutes = ConcurrentHashMap.newKeySet();
        this.pendingBans = ConcurrentHashMap.newKeySet();
        this.muteRefreshVersions = new ConcurrentHashMap<>();
        this.banRefreshVersions = new ConcurrentHashMap<>();
        velocityMessenger.onBanNotify(this::handleBanSync);
        velocityMessenger.onMuteSync(this::handleMuteSync);
    }

    public boolean isMutedCached(UUID uuid) {
        if (pendingMutes.contains(uuid)) return true;
        PunishmentLog log = activeMutes.get(uuid);
        // an expired mute is still a known result, dont turn it into an unknown one
        if (log != null) return isStillActive(log);
        return !knownUnmuted.contains(uuid);
    }

    public boolean hasActiveMuteCached(UUID uuid) {
        if (pendingMutes.contains(uuid)) return true;
        PunishmentLog log = activeMutes.get(uuid);
        return log != null && isStillActive(log);
    }

    public boolean isBannedCached(UUID uuid) {
        if (pendingBans.contains(uuid)) return true;
        PunishmentLog log = activeBans.get(uuid);
        if (log != null && isStillActive(log)) return true;
        if (log != null) activeBans.remove(uuid);
        return false;
    }

    public CompletableFuture<Void> mute(CommandSender staff, OfflinePlayer target,
                                         long durationSeconds, String reason) {
        UUID staffUuid = uuidOf(staff);
        UUID playerId = target.getUniqueId();
        if (!pendingMutes.add(playerId)) {
            return CompletableFuture.failedFuture(new IllegalStateException("mute operation already pending"));
        }
        long now = System.currentTimeMillis();
        PunishmentLog log = new PunishmentLog(0L, playerId, staffUuid, TYPE_MUTE, reason, now, null,
                durationSeconds, true);
        CompletableFuture<Void> persistence;
        try {
            persistence = repository.insertIfNoActive(log).thenApply(PunishmentManager::requireInserted);
        } catch (RuntimeException e) {
            pendingMutes.remove(playerId);
            return CompletableFuture.failedFuture(e);
        }
        return completeOnMain(persistence, pendingMutes, playerId, () -> {
            invalidateMuteRefresh(playerId);
            activeMutes.put(playerId, log);
            knownUnmuted.remove(playerId);
            // target on another server gets the notice through the mute sync
            String notice = target instanceof Player ? "" : ",\"message\":\""
                + JsonUtil.escape(formatTargetMessage("punishments.mute-target", log, target.getName())) + "\"";
            velocityMessenger.send(VelocityMessenger.CH_MUTE,
                "{\"target_uuid\":\"" + playerId + "\",\"muted\":true" + notice + "}", playerId);
            if (target instanceof Player online) {
                String message = formatTargetMessage("punishments.mute-target", log, online.getName());
                online.sendMessage(message);
            }
        });
    }

    public CompletableFuture<Boolean> unmute(CommandSender staff, OfflinePlayer target) {
        UUID playerId = target.getUniqueId();
        if (!pendingMutes.add(playerId)) {
            return CompletableFuture.failedFuture(new IllegalStateException("mute operation already pending"));
        }
        CompletableFuture<Boolean> persistence;
        try {
            persistence = repository.deactivateActiveByPlayerAndType(
                    playerId, TYPE_MUTE, System.currentTimeMillis());
        } catch (RuntimeException e) {
            pendingMutes.remove(playerId);
            return CompletableFuture.failedFuture(e);
        }
        return completeOnMain(persistence, pendingMutes, playerId, () -> {
            invalidateMuteRefresh(playerId);
            activeMutes.remove(playerId);
            knownUnmuted.add(playerId);
            String notice = target instanceof Player ? "" : ",\"message\":\"" + JsonUtil.escape(
                formatTargetMessage("punishments.unmute-target", null, target.getName())
                    .replace("%staff_name%", resolveStaffName(uuidOf(staff)))) + "\"";
            velocityMessenger.send(VelocityMessenger.CH_MUTE,
                "{\"target_uuid\":\"" + playerId + "\",\"muted\":false" + notice + "}", playerId);
            if (target instanceof Player online) {
                String message = formatTargetMessage("punishments.unmute-target", null, online.getName())
                        .replace("%staff_name%", resolveStaffName(uuidOf(staff)));
                online.sendMessage(message);
            }
        });
    }

    public CompletableFuture<Void> ban(CommandSender staff, OfflinePlayer target, long durationSeconds,
                                       String reason, boolean silent) {
        UUID staffUuid = uuidOf(staff);
        UUID playerId = target.getUniqueId();
        if (!pendingBans.add(playerId)) {
            return CompletableFuture.failedFuture(new IllegalStateException("ban operation already pending"));
        }
        long now = System.currentTimeMillis();
        PunishmentLog log = new PunishmentLog(0L, playerId, staffUuid, TYPE_BAN, reason, now, null,
                durationSeconds, true);
        CompletableFuture<Void> persistence;
        try {
            persistence = repository.insertIfNoActive(log).thenApply(PunishmentManager::requireInserted);
        } catch (RuntimeException e) {
            pendingBans.remove(playerId);
            return CompletableFuture.failedFuture(e);
        }
        return completeOnMain(persistence, pendingBans, playerId, () -> {
            invalidateBanRefresh(playerId);
            activeBans.put(playerId, log);
            knownUnbanned.remove(playerId);
            velocityMessenger.send(VelocityMessenger.CH_BAN_NOTIFY,
                "{\"target_uuid\":\"" + playerId + "\",\"banned\":true}", playerId);
            if (target instanceof Player online) schedulerProvider.runOn(online, () -> kickForBan(online, log));
            if (!silent) {
                broadcastBan(networkPlayerResolver.resolveName(playerId, "Unknown"),
                    resolveStaffName(staffUuid), resolveReason(reason));
            }
        });
    }

    public CompletableFuture<Boolean> unban(CommandSender staff, OfflinePlayer target) {
        UUID playerId = target.getUniqueId();
        if (!pendingBans.add(playerId)) {
            return CompletableFuture.failedFuture(new IllegalStateException("ban operation already pending"));
        }
        CompletableFuture<Boolean> persistence;
        try {
            persistence = repository.deactivateActiveByPlayerAndType(
                    playerId, TYPE_BAN, System.currentTimeMillis());
        } catch (RuntimeException e) {
            pendingBans.remove(playerId);
            return CompletableFuture.failedFuture(e);
        }
        return completeOnMain(persistence, pendingBans, playerId, () -> {
            invalidateBanRefresh(playerId);
            activeBans.remove(playerId);
            knownUnbanned.add(playerId);
            velocityMessenger.send(VelocityMessenger.CH_BAN_NOTIFY,
                "{\"target_uuid\":\"" + playerId + "\",\"banned\":false}", playerId);
        });
    }

    public CompletableFuture<Void> kick(CommandSender staff, Player target, String reason) {
        return kick(staff, (OfflinePlayer) target, reason);
    }

    public CompletableFuture<Void> kick(CommandSender staff, OfflinePlayer target, String reason) {
        UUID staffUuid = uuidOf(staff);
        UUID playerId = target.getUniqueId();
        long now = System.currentTimeMillis();
        PunishmentLog log = new PunishmentLog(0L, playerId, staffUuid, TYPE_KICK, reason, now, now, 0L,
                false);
        final CompletableFuture<Void> persistence;
        try {
            persistence = repository.insert(log);
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] kick log insert failed for " + playerId + ": " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
        return completeOnMain(persistence, () -> {
            String message = formatKickMessage(log, target.getName());
            String json = "{\"target_uuid\":\"" + playerId + "\",\"reason\":\"" + JsonUtil.escape(message) + "\"}";
            velocityMessenger.send(VelocityMessenger.CH_KICK, json, playerId);
            if (target instanceof Player online) schedulerProvider.runOn(online, () -> online.kickPlayer(message));
        });
    }

    public CompletableFuture<List<PunishmentLog>> getPunishments(UUID playerUuid) {
        return repository.findByPlayer(playerUuid);
    }

    public void handlePreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();

        // always ask the db, the cached ban can be stale if someone edited it directly
        PunishmentLog ban;
        try {
            ban = findActive(repository.findActiveByPlayerAndTypeAll(uuid, TYPE_BAN).join());
            if (ban != null) {
                activeBans.put(uuid, ban);
                knownUnbanned.remove(uuid);
            } else {
                activeBans.remove(uuid);
                knownUnbanned.add(uuid);
            }
        } catch (Exception e) {
            // dont fail open the ban check if the db dies
            org.bukkit.Bukkit.getLogger().warning("[Staff] ban check failed for " + uuid + ": " + e.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "Can't check bans right now, try again in a sec.");
            return;
        }

        if (ban != null) {
            String message = formatBanPreLoginMessage(ban, event.getName());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, message);
        }
    }

    public void loadMute(Player player) {
        refreshMute(player.getUniqueId());
    }

    private void refreshMute(UUID uuid) {
        long version = nextMuteRefresh(uuid);
        knownUnmuted.remove(uuid);
        activeMutes.computeIfPresent(uuid, (id, log) -> isStillActive(log) ? log : null);
        final CompletableFuture<List<PunishmentLog>> refresh;
        try {
            refresh = repository.findActiveByPlayerAndTypeAll(uuid, TYPE_MUTE);
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] mute refresh failed for " + uuid + ": " + e.getMessage());
            retryMuteRefresh(uuid, version);
            return;
        }
        refresh.whenComplete((logs, error) -> {
            if (!isLatestMuteRefresh(uuid, version)) return;
            if (pendingMutes.contains(uuid)) {
                retryMuteRefresh(uuid, version);
                return;
            }
            if (error != null) {
                Bukkit.getLogger().warning("[Staff] mute refresh failed for " + uuid + ": " + error.getMessage());
                retryMuteRefresh(uuid, version);
                return;
            }
            PunishmentLog active = findActive(logs);
            if (active != null) {
                activeMutes.put(uuid, active);
            } else {
                activeMutes.remove(uuid);
                knownUnmuted.add(uuid);
            }
        });
    }

    private void retryMuteRefresh(UUID uuid, long version) {
        if (!isLatestMuteRefresh(uuid, version)) return;
        try {
            schedulerProvider.runSyncLater(() -> {
                if (!isLatestMuteRefresh(uuid, version)) return;
                if (pendingMutes.contains(uuid)) {
                    retryMuteRefresh(uuid, version);
                } else {
                    refreshMute(uuid);
                }
            }, 100L);
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] could not retry mute refresh for " + uuid + ": " + e.getMessage());
        }
    }

    private void handleMuteSync(String json) {
        try {
            String uuidStr = JsonUtil.extractString(json, "target_uuid");
            refreshMute(UUID.fromString(uuidStr));
            String message = JsonUtil.extractString(json, "message");
            Player online = Bukkit.getPlayer(UUID.fromString(uuidStr));
            if (!message.isEmpty() && online != null) online.sendMessage(message);
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] ignoring malformed mute sync payload: " + e.getMessage());
        }
    }

    private void handleBanSync(String json) {
        try {
            String uuidStr = JsonUtil.extractString(json, "target_uuid");
            refreshBan(UUID.fromString(uuidStr));
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] ignoring malformed ban sync payload: " + e.getMessage());
        }
    }

    private void refreshBan(UUID uuid) {
        long version = nextBanRefresh(uuid);
        knownUnbanned.remove(uuid);
        final CompletableFuture<List<PunishmentLog>> refresh;
        try {
            refresh = repository.findActiveByPlayerAndTypeAll(uuid, TYPE_BAN);
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] ban refresh failed for " + uuid + ": " + e.getMessage());
            return;
        }
        refresh.whenComplete((logs, error) -> {
            if (!isLatestBanRefresh(uuid, version) || pendingBans.contains(uuid)) return;
            if (error != null) {
                Bukkit.getLogger().warning("[Staff] ban refresh failed for " + uuid + ": " + error.getMessage());
                return;
            }
            PunishmentLog active = findActive(logs);
            if (active != null) {
                activeBans.put(uuid, active);
            } else {
                activeBans.remove(uuid);
                knownUnbanned.add(uuid);
            }
        });
    }

    private long nextMuteRefresh(UUID uuid) {
        return muteRefreshVersions.merge(uuid, 1L, Long::sum);
    }

    private void invalidateMuteRefresh(UUID uuid) {
        nextMuteRefresh(uuid);
    }

    private boolean isLatestMuteRefresh(UUID uuid, long version) {
        return version == muteRefreshVersions.getOrDefault(uuid, -1L);
    }

    private long nextBanRefresh(UUID uuid) {
        return banRefreshVersions.merge(uuid, 1L, Long::sum);
    }

    private void invalidateBanRefresh(UUID uuid) {
        nextBanRefresh(uuid);
    }

    private boolean isLatestBanRefresh(UUID uuid, long version) {
        return version == banRefreshVersions.getOrDefault(uuid, -1L);
    }

    private PunishmentLog findActive(List<PunishmentLog> logs) {
        for (PunishmentLog log : logs) {
            if (isStillActive(log)) return log;
        }
        return null;
    }

    private <T> CompletableFuture<T> completeOnMain(CompletableFuture<T> persistence, Runnable success) {
        return completeOnMain(persistence, null, null, success);
    }

    private <T> CompletableFuture<T> completeOnMain(CompletableFuture<T> persistence, Set<UUID> pending,
                                                       UUID playerId, Runnable success) {
        CompletableFuture<T> result = new CompletableFuture<>();
        persistence.whenComplete((value, error) -> {
            try {
                schedulerProvider.runSync(() -> {
                    Throwable failure = error;
                    try {
                        if (failure == null) {
                            success.run();
                        }
                    } catch (Throwable throwable) {
                        failure = throwable;
                    } finally {
                        if (pending != null) {
                            pending.remove(playerId);
                        }
                    }
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(value);
                    }
                });
            } catch (RuntimeException throwable) {
                if (pending != null) {
                    pending.remove(playerId);
                }
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    private void kickForBan(Player player, PunishmentLog log) {
        String message = formatBanKickMessage(log, player);
        player.kickPlayer(message);
    }

    private String formatDuration(long durationSeconds) {
        if (durationSeconds <= 0) return "Permanent";
        return timeUtil.formatDuration(durationSeconds);
    }

    static String resolveReason(String reason) {
        return (reason == null || reason.isEmpty()) ? "None" : reason;
    }

    private String formatBanKickMessage(PunishmentLog log, OfflinePlayer target) {
        Map<String, String> placeholders = new HashMap<>();
        String staffName = resolveStaffName(log.getStaffUuid());
        String targetName = target != null ? target.getName() : "Unknown";
        placeholders.put("%target_name%", targetName == null ? "Unknown" : targetName);
        placeholders.put("%staff_name%", staffName);
        placeholders.put("%reason%", resolveReason(log.getReason()));
        placeholders.put("%staff_ban_time_remaining%", getRemainingFormatted(log));

        String message = textUtil.papi(target, "punishments.ban-kick");
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        return message;
    }

    private String formatBanPreLoginMessage(PunishmentLog log, String targetName) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%target_name%", targetName == null ? "Unknown" : targetName);
        placeholders.put("%staff_name%", resolveStaffName(log.getStaffUuid()));
        placeholders.put("%reason%", resolveReason(log.getReason()));
        placeholders.put("%staff_ban_time_remaining%", getRemainingFormatted(log));

        String message = textUtil.prefixed("punishments.ban-kick");
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        return message;
    }

    private String formatKickMessage(PunishmentLog log, String targetName) {
        Map<String, String> placeholders = new HashMap<>();
        String staffName = resolveStaffName(log.getStaffUuid());
        placeholders.put("%target_name%", targetName == null ? "Unknown" : targetName);
        placeholders.put("%staff_name%", staffName);
        placeholders.put("%reason%", resolveReason(log.getReason()));

        Player target = targetName != null ? Bukkit.getPlayerExact(targetName) : null;
        String message = textUtil.papi(target, "punishments.kick-target");
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        return message;
    }

    private String formatTargetMessage(String path, PunishmentLog log, String targetName) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%target_name%", targetName == null ? "Unknown" : targetName);
        if (log != null) {
            placeholders.put("%staff_name%", resolveStaffName(log.getStaffUuid()));
            placeholders.put("%reason%", resolveReason(log.getReason()));
            placeholders.put("%duration%", formatDuration(log.getDurationSeconds()));
        }

        Player target = targetName != null ? Bukkit.getPlayerExact(targetName) : null;
        String message = textUtil.papi(target, path);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        return message;
    }

    private void broadcastBan(String targetName, String staffName, String reason) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%target_name%", targetName);
        placeholders.put("%staff_name%", staffName);
        placeholders.put("%reason%", reason);
        broadcastWithPermission("punishments.ban-broadcast", placeholders);
    }

    public void broadcastAltBan(String targetName, String linkedName) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%target_name%", targetName);
        placeholders.put("%linked_name%", linkedName);
        broadcastWithPermission("punishments.alt-ban-broadcast", placeholders);
    }

    private void broadcastWithPermission(String messageKey, Map<String, String> placeholders) {
        String permission = settings.getPermission("punishments.ban-notify");
        String template = textUtil.prefixed(messageKey);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            template = template.replace(entry.getKey(), entry.getValue());
        }
        final String message = template;
        if (message == null || message.isEmpty()) return;
        if (!velocityMessenger.isProxyless()) {
            // staffchat channel already reaches every server, the echo delivers it here too
            velocityMessenger.send(VelocityMessenger.CH_STAFFCHAT, "{\"message\":\"" + JsonUtil.escape(message)
                + "\",\"permission\":\"" + JsonUtil.escape(permission == null ? "" : permission) + "\"}");
            return;
        }
        schedulerProvider.runSync(() -> {
            if (permission == null || permission.isEmpty()) {
                Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
            } else {
                Bukkit.getOnlinePlayers().forEach(p -> {
                    if (p.hasPermission(permission)) p.sendMessage(message);
                });
            }
        });
    }

    private boolean isStillActive(PunishmentLog log) {
        return ActivePunishmentPolicy.isActive(log.isActive(), log.getDurationSeconds(),
                log.getStartTime(), System.currentTimeMillis());
    }

    public boolean isActiveNow(PunishmentLog log) {
        return isStillActive(log);
    }

    public String getBanRemainingFormatted(UUID uuid) {
        PunishmentLog log = activeBans.get(uuid);
        if (log == null) return "";
        return getRemainingFormatted(log);
    }

    private String getRemainingFormatted(PunishmentLog log) {
        if (log.getDurationSeconds() <= 0) return "Permanent";
        long elapsedSeconds = (System.currentTimeMillis() - log.getStartTime()) / 1000L;
        long remaining = log.getDurationSeconds() - elapsedSeconds;
        if (remaining < 0) remaining = 0;
        return timeUtil.formatDuration(remaining);
    }

    public void shutdown() {
        activeMutes.clear();
        activeBans.clear();
        knownUnmuted.clear();
        knownUnbanned.clear();
        pendingMutes.clear();
        pendingBans.clear();
        muteRefreshVersions.clear();
        banRefreshVersions.clear();
    }

    public java.util.List<UUID> getCurrentlyBannedPlayerUuids() {
        java.util.List<UUID> result = new java.util.ArrayList<>();
        for (Map.Entry<UUID, PunishmentLog> entry : activeBans.entrySet()) {
            if (isStillActive(entry.getValue())) result.add(entry.getKey());
        }
        return result;
    }

    private static Void requireInserted(Boolean inserted) {
        if (!Boolean.TRUE.equals(inserted)) throw new AlreadyActiveException();
        return null;
    }

    // someone else got there first, the db already has an active row
    public static boolean isAlreadyActive(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof AlreadyActiveException) return true;
        }
        return false;
    }

    public static class AlreadyActiveException extends RuntimeException {
        public AlreadyActiveException() {
            super("punishment already active");
        }
    }

    private static UUID uuidOf(CommandSender sender) {
        if (sender instanceof Player) return ((Player) sender).getUniqueId();
        return CONSOLE_UUID;
    }

    private String resolveStaffName(UUID staffUuid) {
        if (staffUuid == null || CONSOLE_UUID.equals(staffUuid)) return "CONSOLE";
        return networkPlayerResolver.resolveName(staffUuid, "Unknown");
    }
}
