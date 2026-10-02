package mikey.me.staffsystem.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import mikey.me.staffsystem.cache.FreezeState;
import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.models.FreezeLog;
import mikey.me.staffsystem.database.repositories.FreezeLogRepository;
import mikey.me.staffsystem.messaging.VelocityMessenger;
import mikey.me.core.json.JsonUtil;
import mikey.me.staffsystem.utils.NetworkPlayerResolver;
import mikey.me.staffsystem.utils.SchedulerProvider;
import mikey.me.staffsystem.utils.TextUtil;
import mikey.me.staffsystem.utils.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class FreezeManager {

    private final SettingsConfig settings;
    private final SchedulerProvider schedulerProvider;
    private final FreezeLogRepository freezeLogRepository;
    private final TextUtil textUtil;
    private final TimeUtil timeUtil;
    private final VelocityMessenger velocityMessenger;
    private final NetworkPlayerResolver networkPlayerResolver;
    private final Map<UUID, FreezeState> frozen;
    private final Map<UUID, ScheduledTask> titleTasks;
    private final Map<UUID, ScheduledTask> unfreezeTasks;
    private final Map<UUID, Long> activeLogIds;
    private final Set<UUID> pendingFreezes;
    private final Set<UUID> pendingUnfreezes;
    private final AtomicBoolean available;
    private volatile CompletableFuture<Void> initialization;
    private volatile boolean shuttingDown;

    public FreezeManager(ConfigurationManager configurationManager, SchedulerProvider schedulerProvider,
            FreezeLogRepository freezeLogRepository, TextUtil textUtil,
            VelocityMessenger velocityMessenger, NetworkPlayerResolver networkPlayerResolver) {
        this.settings = configurationManager.getSettings();
        this.schedulerProvider = schedulerProvider;
        this.freezeLogRepository = freezeLogRepository;
        this.textUtil = textUtil;
        this.timeUtil = new TimeUtil();
        this.velocityMessenger = velocityMessenger;
        this.networkPlayerResolver = networkPlayerResolver;
        this.frozen = new ConcurrentHashMap<>();
        this.titleTasks = new ConcurrentHashMap<>();
        this.unfreezeTasks = new ConcurrentHashMap<>();
        this.activeLogIds = new ConcurrentHashMap<>();
        this.pendingFreezes = ConcurrentHashMap.newKeySet();
        this.pendingUnfreezes = ConcurrentHashMap.newKeySet();
        this.available = new AtomicBoolean(false);
        this.initialization = null;
        this.shuttingDown = false;
        velocityMessenger.onFreezeSync(this::handleFreezeSync);
    }

    public synchronized CompletableFuture<Void> initialize() {
        if (initialization != null) {
            return initialization;
        }
        available.set(false);
        final CompletableFuture<List<FreezeLog>> activeFuture;
        try {
            activeFuture = freezeLogRepository.findActive();
        } catch (RuntimeException e) {
            initialization = CompletableFuture.failedFuture(e);
            return initialization;
        }
        CompletableFuture<Void> result = activeFuture.thenCompose(logs -> {
            if (logs == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("freeze restore returned no result"));
            }
            CompletableFuture<Void> applied = new CompletableFuture<>();
            try {
                schedulerProvider.runSync(() -> {
                    if (shuttingDown) {
                        applied.completeExceptionally(new IllegalStateException("freeze manager shut down during restore"));
                        return;
                    }
                    try {
                        restoreActive(logs).whenComplete((ignored, error) -> {
                            if (error != null) {
                                applied.completeExceptionally(error);
                            } else if (shuttingDown) {
                                applied.completeExceptionally(new IllegalStateException("freeze manager shut down during restore"));
                            } else {
                                applied.complete(null);
                            }
                        });
                    } catch (Throwable e) {
                        applied.completeExceptionally(e);
                    }
                });
            } catch (RuntimeException e) {
                applied.completeExceptionally(e);
            }
            return applied;
        });
        result.whenComplete((ignored, error) -> available.set(error == null));
        initialization = result;
        return result;
    }

    public boolean isAvailable() {
        return available.get();
    }

    public void markUnavailable() {
        available.set(false);
    }

    public boolean isFrozen(UUID uuid) {
        return uuid != null && (!available.get() || frozen.containsKey(uuid));
    }

    public Map<UUID, FreezeState> getFrozen() {
        return Collections.unmodifiableMap(frozen);
    }

    public CompletableFuture<Boolean> freeze(Player staff, Player target, long durationSeconds, String reason) {
        if (staff == null || target == null || !timeUtil.isValidConfiguredDuration(durationSeconds)) {
            return CompletableFuture.completedFuture(false);
        }
        if (!available.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("freeze state unavailable"));
        }
        UUID playerId = target.getUniqueId();
        UUID staffId = staff.getUniqueId();
        if (frozen.containsKey(playerId) || !pendingFreezes.add(playerId)) {
            return CompletableFuture.completedFuture(false);
        }
        long now = System.currentTimeMillis();
        String safeReason = reason == null ? "" : reason;
        FreezeState state = new FreezeState(playerId, staffId, safeReason, now, durationSeconds);
        FreezeLog log = new FreezeLog(0, playerId, staffId, safeReason, now, null, durationSeconds, true);
        final CompletableFuture<FreezeLog> insertFuture;
        try {
            insertFuture = freezeLogRepository.insertAndReturn(log);
        } catch (RuntimeException e) {
            pendingFreezes.remove(playerId);
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        insertFuture.whenComplete((saved, error) -> completeFreezeOnMain(
                target, state, saved, error, result));
        return result;
    }

    public CompletableFuture<Boolean> unfreeze(Player staff, Player target) {
        return target != null ? unfreeze(target.getUniqueId()) : CompletableFuture.completedFuture(false);
    }

    public CompletableFuture<Boolean> unfreeze(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (!available.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("freeze state unavailable"));
        }
        FreezeState state = frozen.get(playerId);
        if (state == null || !pendingUnfreezes.add(playerId)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Void> persistence;
        try {
            persistence = deactivate(playerId, activeLogIds.get(playerId), System.currentTimeMillis());
        } catch (RuntimeException e) {
            pendingUnfreezes.remove(playerId);
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        persistence.whenComplete((ignored, error) -> {
            try {
                schedulerProvider.runSync(() -> {
                    try {
                        if (error != null) {
                            result.completeExceptionally(error);
                        } else if (frozen.get(playerId) != state) {
                            result.complete(false);
                        } else {
                            frozen.remove(playerId, state);
                            cancelTitleTask(playerId);
                            cancelUnfreezeTask(playerId);
                            activeLogIds.remove(playerId);
                            sendFreezeSync(state, false);
                            result.complete(true);
                        }
                    } finally {
                        pendingUnfreezes.remove(playerId);
                    }
                });
            } catch (RuntimeException throwable) {
                pendingUnfreezes.remove(playerId);
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    // keep the freeze state so reapplyOnJoin restores it after relog
    public void handleLogout(Player player) {
        UUID uuid = player.getUniqueId();
        FreezeState state = frozen.get(uuid);
        if (state == null) {
            return;
        }
        cancelTitleTask(uuid);
        String name = player.getName();
        if (velocityMessenger.isProxyless()) {
            notifyLogout(uuid, name, state);
            return;
        }
        // server switch fires quit too, wait a sec then check the network
        schedulerProvider.runAsyncLater(() -> {
            if (networkPlayerResolver.isOnNetwork(uuid)) return;
            schedulerProvider.runSync(() -> {
                if (frozen.get(uuid) == state) notifyLogout(uuid, name, state);
            });
        }, 20L);
    }

    private void notifyLogout(UUID uuid, String playerName, FreezeState state) {
        long now = System.currentTimeMillis();
        FreezeLog logoutLog = new FreezeLog(0, uuid, state.getStaffUuid(), "Logout while frozen", now, now, 0, false);
        freezeLogRepository.insert(logoutLog).whenComplete((unused, error) -> {
            if (error != null) {
                Bukkit.getLogger().warning("[Staff] freeze logout log insert failed for " + uuid + ": " + error.getMessage());
            }
        });

        String permission = settings.getPermission("freeze.logout-notify");
        String template = textUtil.prefixed("freeze.logged-out");
        String message = template.replace("%player_name%", playerName);
        if (!velocityMessenger.isProxyless()) {
            velocityMessenger.send(VelocityMessenger.CH_STAFFCHAT, "{\"message\":\"" + JsonUtil.escape(message)
                    + "\",\"permission\":\"" + JsonUtil.escape(permission == null ? "" : permission) + "\"}", uuid);
            return;
        }
        if (permission == null || permission.isEmpty()) {
            Bukkit.getOnlinePlayers().forEach(online -> online.sendMessage(message));
        } else {
            Bukkit.getOnlinePlayers().forEach(online -> {
                if (online.hasPermission(permission)) online.sendMessage(message);
            });
        }
    }

    public void reapplyOnJoin(Player player) {
        UUID uuid = player.getUniqueId();
        FreezeState state = frozen.get(uuid);
        if (state == null) {
            return;
        }
        if (state.getDurationSeconds() > 0L && getRemainingMillis(state, System.currentTimeMillis()) <= 0L) {
            expire(uuid, state);
            return;
        }
        sendFreezeTitle(player);
        if (settings.isFreezeTitlePersistent() && settings.isFreezeTitleEnabled()) {
            startTitleTask(player);
        }
    }

    public CompletableFuture<List<FreezeLog>> getLogs(UUID playerUuid) {
        return freezeLogRepository.findByPlayer(playerUuid);
    }

    public String getRemainingFormatted(UUID uuid) {
        FreezeState state = frozen.get(uuid);
        if (state == null) {
            return "";
        }
        if (state.getDurationSeconds() <= 0L) {
            return "Permanent";
        }
        long remainingMillis = getRemainingMillis(state, System.currentTimeMillis());
        if (remainingMillis <= 0L) {
            return timeUtil.formatDuration(0L);
        }
        return timeUtil.formatDuration(remainingMillis / 1000L);
    }

    private CompletableFuture<Void> restoreActive(List<FreezeLog> logs) {
        Map<UUID, FreezeLog> latest = new HashMap<>();
        for (FreezeLog log : logs) {
            if (log == null || !log.isActive() || log.getPlayerUuid() == null || log.getStaffUuid() == null) {
                continue;
            }
            FreezeLog current = latest.get(log.getPlayerUuid());
            if (current == null || log.getId() > current.getId()) {
                latest.put(log.getPlayerUuid(), log);
            }
        }
        long now = System.currentTimeMillis();
        List<CompletableFuture<Void>> deactivations = new ArrayList<>();
        for (FreezeLog log : latest.values()) {
            try {
                CompletableFuture<Void> deactivation = restoreLog(log, now);
                if (deactivation != null) {
                    deactivations.add(deactivation);
                }
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return CompletableFuture.allOf(deactivations.toArray(CompletableFuture<?>[]::new));
    }

    private CompletableFuture<Void> restoreLog(FreezeLog log, long now) {
        if (log == null || log.getPlayerUuid() == null || log.getStaffUuid() == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (frozen.containsKey(log.getPlayerUuid())) {
            return CompletableFuture.completedFuture(null);
        }
        long duration = log.getDurationSeconds();
        if (!timeUtil.isValidConfiguredDuration(duration)) {
            return deactivate(log.getPlayerUuid(), log.getId() > 0L ? log.getId() : null, now);
        }
        FreezeState state = new FreezeState(log.getPlayerUuid(), log.getStaffUuid(), safeReason(log.getReason()),
                log.getStartTime(), duration);
        if (duration > 0L) {
            long remaining = getRemainingMillis(log.getStartTime(), duration, now);
            if (remaining <= 0L) {
                return deactivate(log.getPlayerUuid(), log.getId() > 0L ? log.getId() : null, now);
            }
            if (!scheduleExpiry(log.getPlayerUuid(), state, ticksForMillis(remaining))) {
                return deactivate(log.getPlayerUuid(), log.getId() > 0L ? log.getId() : null, now);
            }
            if (frozen.putIfAbsent(log.getPlayerUuid(), state) != null) {
                cancelUnfreezeTask(log.getPlayerUuid());
                return CompletableFuture.completedFuture(null);
            }
        } else if (frozen.putIfAbsent(log.getPlayerUuid(), state) != null) {
            return CompletableFuture.completedFuture(null);
        }
        if (log.getId() > 0L) {
            activeLogIds.put(log.getPlayerUuid(), log.getId());
        }
        Player online = Bukkit.getPlayer(log.getPlayerUuid());
        if (online != null) {
            reapplyOnJoin(online);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void applySyncedState(UUID uuid, UUID staffUuid, String reason, long startTime, long duration) {
        if (!available.get() || shuttingDown || staffUuid == null || startTime <= 0L
                || !timeUtil.isValidConfiguredDuration(duration)) {
            return;
        }
        // the proxy echoes our own freeze back, keep its existing timer
        if (frozen.containsKey(uuid)) return;
        FreezeState state = new FreezeState(uuid, staffUuid, safeReason(reason), startTime, duration);
        if (duration > 0L) {
            long remaining = getRemainingMillis(startTime, duration, System.currentTimeMillis());
            if (remaining <= 0L) {
                deactivateAfterSync(uuid, null, System.currentTimeMillis());
                return;
            }
            boolean expiryScheduled = scheduleExpiry(uuid, state, ticksForMillis(remaining));
            if (frozen.putIfAbsent(uuid, state) != null) {
                if (expiryScheduled) {
                    cancelUnfreezeTask(uuid);
                }
                return;
            }
            if (!expiryScheduled) {
                markUnavailable();
                Bukkit.getLogger().warning("[Staff] failed to schedule synced freeze expiry for " + uuid);
            }
        } else if (frozen.putIfAbsent(uuid, state) != null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            reapplyOnJoin(player);
        }
    }

    private void deactivateAfterSync(UUID uuid, Long logId, long endTime) {
        try {
            deactivate(uuid, logId, endTime).whenComplete((ignored, error) -> {
                if (error != null) {
                    Bukkit.getLogger().warning("[Staff] failed to deactivate synced freeze for "
                            + uuid + ": " + error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] failed to start synced freeze deactivation for "
                    + uuid + ": " + e.getMessage());
        }
    }

    private void completeFreezeOnMain(Player target, FreezeState state, FreezeLog saved, Throwable error,
                                       CompletableFuture<Boolean> result) {
        try {
            schedulerProvider.runSync(() -> {
                UUID playerId = state.getPlayerUuid();
                try {
                    if (error != null) {
                        result.completeExceptionally(error);
                        return;
                    }
                    if (saved == null) {
                        result.completeExceptionally(new IllegalStateException("freeze insert returned no record"));
                        return;
                    }
                    if (shuttingDown) {
                        rollbackFreezeInsert(saved, playerId);
                        result.completeExceptionally(new IllegalStateException("freeze manager shut down"));
                        return;
                    }
                    if (frozen.containsKey(playerId)) {
                        rollbackFreezeInsert(saved, playerId);
                        result.complete(false);
                        return;
                    }
                    cancelTitleTask(playerId);
                    cancelUnfreezeTask(playerId);
                    if (state.getDurationSeconds() > 0L
                            && !scheduleExpiry(playerId, state, timeUtil.getSchedulerTicks(state.getDurationSeconds()))) {
                        rollbackFreezeInsert(saved, playerId);
                        result.completeExceptionally(new IllegalStateException("failed to schedule freeze expiry"));
                        return;
                    }
                    if (frozen.putIfAbsent(playerId, state) != null) {
                        cancelUnfreezeTask(playerId);
                        rollbackFreezeInsert(saved, playerId);
                        result.complete(false);
                        return;
                    }
                    if (saved.getId() > 0L) {
                        activeLogIds.put(playerId, saved.getId());
                    }
                    sendFreezeTitle(target);
                    if (settings.isFreezeTitlePersistent() && settings.isFreezeTitleEnabled()) {
                        startTitleTask(target);
                    }
                    sendFreezeSync(state, true);
                    result.complete(true);
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                } finally {
                    pendingFreezes.remove(playerId);
                }
            });
        } catch (RuntimeException throwable) {
            pendingFreezes.remove(state.getPlayerUuid());
            result.completeExceptionally(throwable);
        }
    }

    private void rollbackFreezeInsert(FreezeLog saved, UUID playerId) {
        if (saved.getId() <= 0L) {
            Bukkit.getLogger().warning("[Staff] freeze insert for " + playerId + " has no generated id");
            return;
        }
        try {
            freezeLogRepository.markInactive(saved.getId(), System.currentTimeMillis())
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            Bukkit.getLogger().warning("[Staff] failed to roll back freeze insert "
                                    + saved.getId() + ": " + error.getMessage());
                        }
                    });
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] failed to roll back freeze insert "
                    + saved.getId() + ": " + e.getMessage());
        }
    }

    private void sendFreezeSync(FreezeState state, boolean frozenState) {
        String json = "{\"target_uuid\":\"" + state.getPlayerUuid()
                + "\",\"frozen\":" + frozenState
                + ",\"staff_uuid\":\"" + (state.getStaffUuid() == null ? "" : state.getStaffUuid())
                + "\",\"reason\":\"" + JsonUtil.escape(state.getReason())
                + "\",\"start_time\":" + state.getStartTime()
                + ",\"duration_seconds\":" + state.getDurationSeconds() + "}";
        velocityMessenger.send(VelocityMessenger.CH_FREEZE, json, state.getPlayerUuid());
    }

    private void expire(UUID playerId, FreezeState expectedState) {
        if (!available.get() || frozen.get(playerId) != expectedState) {
            return;
        }
        unfreezeTasks.remove(playerId);
        if (pendingUnfreezes.contains(playerId)) {
            retryExpiry(playerId, expectedState);
            return;
        }
        CompletableFuture<Void> persistence;
        try {
            persistence = deactivate(playerId, activeLogIds.get(playerId), System.currentTimeMillis());
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] failed to start freeze expiry for "
                    + playerId + ": " + e.getMessage());
            retryExpiry(playerId, expectedState);
            return;
        }
        persistence.whenComplete((ignored, error) -> {
            if (error != null) {
                Bukkit.getLogger().warning("[Staff] failed to deactivate expired freeze for "
                        + playerId + ": " + error.getMessage());
                retryExpiry(playerId, expectedState);
                return;
            }
            try {
                schedulerProvider.runSync(() -> {
                    if (frozen.get(playerId) != expectedState) {
                        return;
                    }
                    frozen.remove(playerId, expectedState);
                    cancelTitleTask(playerId);
                    cancelUnfreezeTask(playerId);
                    activeLogIds.remove(playerId);
                    sendFreezeSync(expectedState, false);
                });
            } catch (RuntimeException schedulingError) {
                Bukkit.getLogger().warning("[Staff] failed to apply freeze expiry for "
                        + playerId + ": " + schedulingError.getMessage());
                retryExpiry(playerId, expectedState);
            }
        });
    }

    private void retryExpiry(UUID playerId, FreezeState expectedState) {
        try {
            schedulerProvider.runSync(() -> {
                if (!shuttingDown && available.get() && frozen.get(playerId) == expectedState) {
                    scheduleExpiry(playerId, expectedState, 100L);
                }
            });
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] could not retry freeze expiry for " + playerId + ": " + e.getMessage());
        }
    }

    private CompletableFuture<Void> deactivate(UUID playerId, Long logId, long endTime) {
        if (logId != null && logId > 0L) {
            return freezeLogRepository.markInactive(logId, endTime);
        }
        return freezeLogRepository.findActiveByPlayer(playerId).thenCompose(optional -> {
            if (optional.isEmpty() || optional.get().getId() <= 0L) {
                return CompletableFuture.completedFuture(null);
            }
            return freezeLogRepository.markInactive(optional.get().getId(), endTime);
        });
    }

    private boolean scheduleExpiry(UUID playerId, FreezeState state, long ticks) {
        cancelUnfreezeTask(playerId);
        try {
            ScheduledTask task = schedulerProvider.runSyncLater(() -> expire(playerId, state), Math.max(1L, ticks));
            unfreezeTasks.put(playerId, task);
            return true;
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] freeze expiry scheduling failed for " + playerId + ": " + e.getMessage());
            return false;
        }
    }

    static long ticksForMillis(long millis) {
        if (millis <= 0L) {
            return 1L;
        }
        return millis / 50L + (millis % 50L == 0L ? 0L : 1L);
    }

    private long getRemainingMillis(FreezeState state, long now) {
        return getRemainingMillis(state.getStartTime(), state.getDurationSeconds(), now);
    }

    static long getRemainingMillis(long startTime, long durationSeconds, long now) {
        if (durationSeconds <= 0L) {
            return Long.MAX_VALUE;
        }
        final long durationMillis;
        try {
            durationMillis = Math.multiplyExact(durationSeconds, 1000L);
        } catch (ArithmeticException e) {
            return 0L;
        }
        if (startTime > now) {
            return durationMillis;
        }
        long elapsed = now - startTime;
        if (elapsed < 0L || elapsed >= durationMillis) {
            return 0L;
        }
        return durationMillis - elapsed;
    }

    private UUID parseUuidOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String safeReason(String reason) {
        return reason == null ? "" : reason;
    }

    private void sendFreezeTitle(Player target) {
        if (!target.isOnline() || !settings.isFreezeTitleEnabled()) return;
        String rawTitle = textUtil.color(textUtil.prefixed("freeze-display.title"));
        String rawSubtitle = textUtil.color(textUtil.prefixed("freeze-display.subtitle"));
        int fadeIn = settings.getFreezeTitleFadeIn();
        int stay = settings.getFreezeTitleStay();
        int fadeOut = settings.getFreezeTitleFadeOut();
        target.sendTitle(rawTitle, rawSubtitle, fadeIn, stay, fadeOut);
    }

    private void startTitleTask(Player target) {
        UUID playerId = target.getUniqueId();
        int interval = settings.getFreezeTitleUpdateIntervalTicks();
        if (interval <= 0) {
            return;
        }
        ScheduledTask existing = titleTasks.remove(playerId);
        if (existing != null) existing.cancel();
        ScheduledTask task = schedulerProvider.runForTimer(target, () -> {
            if (!isFrozen(playerId) || !target.isOnline()) {
                ScheduledTask current = titleTasks.remove(playerId);
                if (current != null) current.cancel();
                return;
            }
            sendFreezeTitle(target);
        }, interval, interval);
        if (task != null) titleTasks.put(playerId, task);
    }

    private void handleFreezeSync(String json) {
        String uuidStr = JsonUtil.extractString(json, "target_uuid");
        UUID uuid = parseUuidOrNull(uuidStr);
        if (uuid == null) {
            // bad payload (version skew or bug), not a db failure. never markUnavailable() here,
            // that would fail closed for everyone and one bad msg freezes the whole server
            Bukkit.getLogger().warning("[Staff] ignoring malformed freeze sync, bad target_uuid: " + uuidStr);
            return;
        }
        try {
            boolean freeze = JsonUtil.extractBool(json, "frozen", false);
            if (!freeze) {
                String staffValue = JsonUtil.extractString(json, "staff_uuid");
                long startTime = JsonUtil.extractLong(json, "start_time", -1L);
                long duration = JsonUtil.extractLong(json, "duration_seconds", -1L);
                UUID staffUuid = parseUuidOrNull(staffValue);
                schedulerProvider.runSync(() -> applySyncedUnfreeze(
                        uuid, staffUuid, startTime, duration));
                return;
            }

            String staffValue = JsonUtil.extractString(json, "staff_uuid");
            long startTime = JsonUtil.extractLong(json, "start_time", -1L);
            long duration = JsonUtil.extractLong(json, "duration_seconds", -1L);
            UUID staffUuid = null;
            if (!staffValue.isEmpty()) {
                try {
                    staffUuid = UUID.fromString(staffValue);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (staffUuid != null && startTime > 0L && duration >= 0L) {
                String reason = JsonUtil.extractString(json, "reason");
                UUID syncedStaffUuid = staffUuid;
                String syncedReason = reason;
                schedulerProvider.runSync(() -> applySyncedState(uuid, syncedStaffUuid, syncedReason, startTime, duration));
                return;
            }

            freezeLogRepository.findActiveByPlayer(uuid).whenComplete((optional, error) -> {
                if (error != null) {
                    markUnavailable();
                    Bukkit.getLogger().warning("[Staff] failed to resolve synced freeze for "
                            + uuid + ": " + error.getMessage());
                    return;
                }
                if (optional.isEmpty()) {
                    return;
                }
                FreezeLog log = optional.get();
                try {
                    schedulerProvider.runSync(() -> applySyncedLog(uuid, log));
                } catch (RuntimeException e) {
                    // scheduling failure, not a db failure. don't fail closed
                    Bukkit.getLogger().warning(
                            "[Staff] failed to apply synced freeze for " + uuid + ": " + e.getMessage());
                }
            });
        } catch (Exception e) {
            // markUnavailable() is only for real db failures; it's irreversible and then
            // isFrozen() is true for everyone server-wide, cancelling movement/interaction/commands.
            // everything reachable here is parsing/scheduling on an already-validated payload,
            // so a bad message must never end up here.
            Bukkit.getLogger().warning("[Staff] failed to handle synced freeze state: " + e.getMessage());
        }
    }

    private void applySyncedUnfreeze(UUID uuid, UUID staffUuid, long startTime, long duration) {
        if (!available.get() || shuttingDown || pendingUnfreezes.contains(uuid)) {
            return;
        }
        FreezeState current = frozen.get(uuid);
        if (current == null) {
            return;
        }
        if (startTime > 0L && (current.getStartTime() != startTime
                || (staffUuid != null && !staffUuid.equals(current.getStaffUuid()))
                || (duration >= 0L && current.getDurationSeconds() != duration))) {
            return;
        }
        final CompletableFuture<Void> persistence;
        try {
            persistence = deactivate(uuid, activeLogIds.get(uuid), System.currentTimeMillis());
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] failed to start synced unfreeze for "
                    + uuid + ": " + e.getMessage());
            return;
        }
        persistence.whenComplete((ignored, error) -> {
            if (error != null) {
                Bukkit.getLogger().warning("[Staff] failed to persist synced unfreeze for "
                        + uuid + ": " + error.getMessage());
                return;
            }
            try {
                schedulerProvider.runSync(() -> {
                    if (frozen.get(uuid) != current) {
                        return;
                    }
                    frozen.remove(uuid, current);
                    cancelTitleTask(uuid);
                    cancelUnfreezeTask(uuid);
                    activeLogIds.remove(uuid);
                });
            } catch (RuntimeException schedulingError) {
                Bukkit.getLogger().warning("[Staff] failed to apply synced unfreeze for "
                        + uuid + ": " + schedulingError.getMessage());
            }
        });
    }

    private void applySyncedLog(UUID uuid, FreezeLog log) {
        if (log == null || !log.isActive() || log.getPlayerUuid() == null || !uuid.equals(log.getPlayerUuid())) {
            return;
        }
        try {
            restoreLog(log, System.currentTimeMillis()).whenComplete((ignored, error) -> {
                if (error != null) {
                    markUnavailable();
                    Bukkit.getLogger().warning("[Staff] failed to apply synced freeze for "
                            + uuid + ": " + error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] failed to apply synced freeze for "
                    + uuid + ": " + e.getMessage());
        }
    }

    public void shutdown() {
        shuttingDown = true;
        available.set(false);
        for (ScheduledTask task : titleTasks.values()) task.cancel();
        titleTasks.clear();
        for (ScheduledTask task : unfreezeTasks.values()) task.cancel();
        unfreezeTasks.clear();
        activeLogIds.clear();
        pendingFreezes.clear();
        pendingUnfreezes.clear();
        frozen.clear();
    }

    private void cancelTitleTask(UUID uuid) {
        ScheduledTask task = titleTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    private void cancelUnfreezeTask(UUID uuid) {
        ScheduledTask task = unfreezeTasks.remove(uuid);
        if (task != null) task.cancel();
    }
}
