package mikey.me.staffsystem.utils;

import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.staffsystem.messaging.VelocityMessenger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NetworkPlayerResolver {

    private static final Pattern PLAYER_ENTRY_PATTERN = Pattern.compile(
            "\\{\\s*\"uuid\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"username\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"server\"\\s*:\\s*\"([^\"]*)\"");
    private static final long PLAYER_LIST_REFRESH_MILLIS = 2_500L;
    private static final long BANNED_NAMES_REFRESH_MILLIS = 5_000L;

    private final DatabaseManager databaseManager;
    private final VelocityMessenger velocityMessenger;
    private final Set<String> cachedNetworkPlayerNames = ConcurrentHashMap.newKeySet();
    private final Map<String, UUID> cachedNetworkPlayerIds = new ConcurrentHashMap<>();
    private final Map<UUID, String> cachedNetworkPlayerServers = new ConcurrentHashMap<>();
    private volatile long lastPlayerListRequestMillis;
    private final Set<String> cachedBannedNames = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean bannedNamesRefreshing = new AtomicBoolean();
    private volatile long lastBannedNamesRefreshMillis;

    public NetworkPlayerResolver(DatabaseManager databaseManager, VelocityMessenger velocityMessenger) {
        this.databaseManager = databaseManager;
        this.velocityMessenger = velocityMessenger;
        this.velocityMessenger.onPlayerListResponse(this::handlePlayerListResponse);
        refreshBannedNamesIfStale();
    }

    // for /unban tab complete
    public List<String> getBannedPlayerNames() {
        refreshBannedNamesIfStale();
        List<String> names = new ArrayList<>(cachedBannedNames);
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private void refreshBannedNamesIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastBannedNamesRefreshMillis < BANNED_NAMES_REFRESH_MILLIS
                || !bannedNamesRefreshing.compareAndSet(false, true)) {
            return;
        }
        lastBannedNamesRefreshMillis = now;
        try {
            databaseManager.getDbExecutor().execute(this::loadBannedNames);
        } catch (RuntimeException e) {
            bannedNamesRefreshing.set(false);
        }
    }

    private void loadBannedNames() {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT DISTINCT n.username FROM punishments p JOIN player_names n ON n.uuid = p.player_uuid " +
                 "WHERE p.type = 'BAN' AND p.active = 1 " +
                 "AND (p.duration_seconds <= 0 OR p.start_time + p.duration_seconds * 1000 > ?)")) {
            statement.setLong(1, System.currentTimeMillis());
            Set<String> names = new LinkedHashSet<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
            cachedBannedNames.retainAll(names);
            cachedBannedNames.addAll(names);
        } catch (SQLException e) {
            databaseManager.logSqlFailure("banned name lookup", e);
        } finally {
            bannedNamesRefreshing.set(false);
        }
    }

    // name from the proxys player_names when this server never saw them
    public String resolveName(UUID uuid, String fallback) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        if (name != null) return name;
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT username FROM player_names WHERE uuid = ? LIMIT 1")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : fallback;
            }
        } catch (SQLException e) {
            databaseManager.logSqlFailure("player name lookup", e);
            return fallback;
        }
    }

    // still listed by a live proxy, so leaving this server was just a switch
    public boolean isOnNetwork(UUID uuid) {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT 1 FROM network_players np JOIN network_proxies pr ON pr.proxy_id = np.proxy_id " +
                 "WHERE np.uuid = ? AND pr.last_seen > ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) - 15000")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            databaseManager.logSqlFailure("network presence lookup", e);
            return false;
        }
    }

    public Player getLocalPlayer(String name) {
        return Bukkit.getPlayer(name);
    }

    public OfflinePlayer resolveOfflinePlayer(String name) {
        Player local = Bukkit.getPlayer(name);
        if (local != null) return local;
        UUID uuid = resolveUUID(name);
        if (uuid != null) return Bukkit.getOfflinePlayer(uuid);
        // dont hit the mojang api on the main thread for unknown names
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) return cached;
        // never been on this server, check the names the proxy saved
        UUID stored = findStoredUUID(name);
        return stored == null ? null : Bukkit.getOfflinePlayer(stored);
    }

    private UUID findStoredUUID(String name) {
        if (name == null) return null;
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT uuid FROM player_names WHERE username = ? ORDER BY last_seen DESC LIMIT 1")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString("uuid")) : null;
            }
        } catch (SQLException e) {
            databaseManager.logSqlFailure("player name lookup", e);
            return null;
        }
    }

    public UUID resolveUUID(String name) {
        if (name == null) {
            return null;
        }
        if (velocityMessenger.isProxyless()) {
            Player local = Bukkit.getPlayer(name);
            if (local != null) {
                return local.getUniqueId();
            }
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
            return cached == null ? null : cached.getUniqueId();
        }
        return cachedNetworkPlayerIds.get(normalize(name));
    }

    public String resolveServer(String name) {
        if (velocityMessenger.isProxyless()) {
            return null;
        }
        UUID uuid = resolveUUID(name);
        return uuid == null ? null : cachedNetworkPlayerServers.get(uuid);
    }

    public boolean isKnownPlayerName(String name) {
        if (name == null) {
            return false;
        }
        if (getCachedNetworkPlayerNames().stream().anyMatch(candidate -> candidate.equalsIgnoreCase(name))) {
            return true;
        }
        if (!velocityMessenger.isProxyless()) {
            return false;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && cached.getUniqueId() != null;
    }

    public List<String> getNetworkPlayerNames() {
        if (velocityMessenger.isProxyless()) {
            return getLocalPlayerNames();
        }
        requestNetworkPlayerListIfStale();
        return new ArrayList<>(cachedNetworkPlayerNames);
    }

    public void requestNetworkPlayerList() {
        if (velocityMessenger.isProxyless()) {
            return;
        }
        velocityMessenger.requestPlayerList();
        lastPlayerListRequestMillis = System.currentTimeMillis();
    }

    private List<String> getLocalPlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private void requestNetworkPlayerListIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastPlayerListRequestMillis < PLAYER_LIST_REFRESH_MILLIS) return;
        requestNetworkPlayerList();
    }

    private void handlePlayerListResponse(String json) {
        Set<String> names = new LinkedHashSet<>();
        Map<String, UUID> ids = new ConcurrentHashMap<>();
        Map<UUID, String> servers = new ConcurrentHashMap<>();
        Matcher matcher = PLAYER_ENTRY_PATTERN.matcher(json == null ? "" : json);
        while (matcher.find()) {
            String username = matcher.group(2);
            if (username == null || username.isBlank()) {
                continue;
            }
            names.add(username);
            try {
                UUID uuid = UUID.fromString(matcher.group(1));
                ids.put(normalize(username), uuid);
                servers.put(uuid, matcher.group(3));
            } catch (IllegalArgumentException ignored) {
            }
        }
        cachedNetworkPlayerNames.clear();
        cachedNetworkPlayerNames.addAll(names);
        cachedNetworkPlayerIds.clear();
        cachedNetworkPlayerIds.putAll(ids);
        cachedNetworkPlayerServers.clear();
        cachedNetworkPlayerServers.putAll(servers);
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public List<String> getCachedNetworkPlayerNames() {
        if (velocityMessenger.isProxyless()) {
            return getLocalPlayerNames();
        }
        List<String> names = new ArrayList<>(cachedNetworkPlayerNames);
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }
}
