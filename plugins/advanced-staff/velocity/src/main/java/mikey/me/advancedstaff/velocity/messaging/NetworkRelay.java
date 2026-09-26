package mikey.me.advancedstaff.velocity.messaging;

import mikey.me.advancedstaff.velocity.database.VelocityDatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

// db relay between proxies
public class NetworkRelay {

    public static final Set<String> RELAYED_CHANNELS = Set.of(
            PluginProtocol.CH_STAFFCHAT, PluginProtocol.CH_KICK, PluginProtocol.CH_BAN_NOTIFY,
            PluginProtocol.CH_MUTE, PluginProtocol.CH_VANISH, PluginProtocol.CH_FREEZE);

    private static final long WINDOW_MILLIS = 15_000L;
    private static final long KEEP_MILLIS = 60_000L;
    private static final long DEAD_PROXY_MILLIS = VelocityDatabaseManager.LIVE_PROXY_MILLIS;
    private static final String NOW = VelocityDatabaseManager.DB_NOW_MILLIS;

    private final VelocityDatabaseManager databaseManager;
    private final Logger logger;
    private final String proxyId;
    private final Map<Long, Long> seen = new ConcurrentHashMap<>();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicLong messageCounter = new AtomicLong();
    // backends remember ids across our restarts, so the counter alone isnt unique
    private final String bootId = UUID.randomUUID().toString().substring(0, 8);
    private BiConsumer<String, String> receiver = (channel, json) -> {};
    private Runnable onHeartbeatRestored = () -> {};
    private boolean started;
    private int ticks;
    private volatile long lastFailureLog;

    public NetworkRelay(VelocityDatabaseManager databaseManager, Logger logger) {
        this.databaseManager = databaseManager;
        this.logger = logger;
        this.proxyId = databaseManager.getProxyId();
    }

    public void onReceive(BiConsumer<String, String> receiver) {
        this.receiver = receiver;
    }

    public void onHeartbeatRestored(Runnable callback) {
        this.onHeartbeatRestored = callback;
    }

    public void start() throws SQLException {
        try (Connection connection = databaseManager.openConnection()) {
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT COUNT(*) FROM network_proxies WHERE proxy_id = ? AND last_seen >= " + NOW + " - ?")) {
                check.setString(1, proxyId);
                check.setLong(2, VelocityDatabaseManager.LIVE_PROXY_MILLIS);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        logger.warning("Another live proxy seems to use network.proxy-id=" + proxyId
                                + ", give every proxy its own id (ignore this if this proxy just crashed)");
                    }
                }
            }
            // anything already in the table is old news for us
            try (PreparedStatement existing = connection.prepareStatement(
                    "SELECT id FROM network_messages WHERE created_at >= " + NOW + " - ?")) {
                existing.setLong(1, WINDOW_MILLIS * 2);
                long now = System.currentTimeMillis();
                try (ResultSet rs = existing.executeQuery()) {
                    while (rs.next()) seen.put(rs.getLong(1), now);
                }
            }
        }
        heartbeat();
        started = true;
    }

    public void stop() {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement players = connection.prepareStatement("DELETE FROM network_players WHERE proxy_id = ?");
             PreparedStatement proxy = connection.prepareStatement("DELETE FROM network_proxies WHERE proxy_id = ?")) {
            players.setString(1, proxyId);
            players.executeUpdate();
            proxy.setString(1, proxyId);
            proxy.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Failed to clear this proxy from the network tables: " + e.getMessage());
        }
    }

    // more than one proxy can reach a backend, it drops dupe ids
    public String withMessageId(String json) {
        if (json == null || !json.startsWith("{") || json.contains("\"mid\"")) return json;
        String id = "\"mid\":\"" + proxyId + "-" + bootId + "-" + messageCounter.incrementAndGet() + "\"";
        String rest = json.substring(1).trim();
        return rest.equals("}") ? "{" + id + "}" : "{" + id + "," + json.substring(1);
    }

    public void publish(String channel, String json) {
        if (!RELAYED_CHANNELS.contains(channel)) return;
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO network_messages (source_proxy, channel, payload, created_at) VALUES (?, ?, ?, " + NOW + ")")) {
            statement.setString(1, proxyId);
            statement.setString(2, channel);
            statement.setString(3, json);
            statement.executeUpdate();
        } catch (SQLException e) {
            logFailure("Failed to relay " + channel + " to other proxies: " + e.getMessage());
        }
    }

    public void poll() {
        if (!started || !polling.compareAndSet(false, true)) return;
        try {
            heartbeat();
            List<Row> rows = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, channel, payload FROM network_messages " +
                     "WHERE source_proxy <> ? AND created_at >= " + NOW + " - ? ORDER BY id")) {
                statement.setString(1, proxyId);
                statement.setLong(2, WINDOW_MILLIS);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
            long now = System.currentTimeMillis();
            for (Row row : rows) {
                if (seen.putIfAbsent(row.id(), now) != null) continue;
                if (!RELAYED_CHANNELS.contains(row.channel())) continue;
                try {
                    receiver.accept(row.channel(), row.payload());
                } catch (RuntimeException e) {
                    logger.warning("Failed to deliver relayed " + row.channel() + ": " + e.getMessage());
                }
            }
            if (++ticks % 5 == 0) cleanup(now);
        } catch (SQLException e) {
            logFailure("Network relay poll failed: " + e.getMessage());
        } finally {
            polling.set(false);
        }
    }

    private void heartbeat() throws SQLException {
        int updated;
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO network_proxies (proxy_id, last_seen) VALUES (?, " + NOW + ") " +
                 "ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen)")) {
            statement.setString(1, proxyId);
            updated = statement.executeUpdate();
        }
        // our row got cleaned up while we were stuck, put our players back
        if (started && updated == 1) onHeartbeatRestored.run();
    }

    private void cleanup(long now) throws SQLException {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement messages = connection.prepareStatement(
                 "DELETE FROM network_messages WHERE created_at < " + NOW + " - ?");
             PreparedStatement players = connection.prepareStatement(
                 "DELETE FROM network_players WHERE proxy_id NOT IN " +
                 "(SELECT proxy_id FROM network_proxies WHERE last_seen >= " + NOW + " - ?)");
             PreparedStatement proxies = connection.prepareStatement(
                 "DELETE FROM network_proxies WHERE last_seen < " + NOW + " - ?")) {
            messages.setLong(1, KEEP_MILLIS);
            messages.executeUpdate();
            players.setLong(1, DEAD_PROXY_MILLIS);
            players.executeUpdate();
            proxies.setLong(1, DEAD_PROXY_MILLIS);
            proxies.executeUpdate();
        }
        seen.entrySet().removeIf(entry -> now - entry.getValue() > KEEP_MILLIS);
    }

    private void logFailure(String message) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLog < 60_000L) return;
        lastFailureLog = now;
        logger.warning(message);
    }

    private record Row(long id, String channel, String payload) {}
}
