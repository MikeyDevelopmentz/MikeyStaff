package mikey.me.advancedstaff.velocity.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import mikey.me.core.persistence.ActivePunishmentPolicy;
import mikey.me.core.persistence.DatabaseConfig;
import mikey.me.core.persistence.YamlFile;
import mikey.me.core.registry.CoreRegistry;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

public class VelocityDatabaseManager {

    // db clock so proxies on different hosts agree on heartbeat age
    public static final String DB_NOW_MILLIS = "ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)";
    public static final long LIVE_PROXY_MILLIS = 15_000L;

    private final Logger logger;
    private HikariDataSource dataSource;
    private String sharedSecret = "";
    private String proxyId = "";
    private int enforcementIntervalSeconds = 5;

    public VelocityDatabaseManager(Logger logger) {
        this.logger = logger;
    }

    public void initialize(Path configFile) throws Exception {
        Properties props = new Properties();
        // saveProxyId rewrites this file as utf-8, so read with a utf-8 Reader;
        // otherwise a non-ascii shared-secret changes across boots (it's the hmac key)
        try (Reader in = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            props.load(in);
        }
        sharedSecret = props.getProperty("network.shared-secret", "");
        enforcementIntervalSeconds = readInterval(props.getProperty("database.enforcement-interval-seconds", "5"));
        proxyId = props.getProperty("network.proxy-id", "").trim();
        if (proxyId.isEmpty()) {
            proxyId = UUID.randomUUID().toString().substring(0, 8);
            saveProxyId(configFile, proxyId);
        } else if (proxyId.length() > 64) {
            proxyId = proxyId.substring(0, 64);
        }

        CoreRegistry.PluginData staff = CoreRegistry.register("staff");
        if (!Files.exists(staff.file("database.yml"))) {
            Map<String, Object> db = new HashMap<>();
            db.put("host", props.getProperty("database.host", "localhost"));
            db.put("port", Integer.parseInt(props.getProperty("database.port", "3306")));
            db.put("database", props.getProperty("database.database", "advancedstaff"));
            db.put("user", props.getProperty("database.user", "root"));
            db.put("password", props.getProperty("database.password", ""));
            db.put("pool-size", Integer.parseInt(props.getProperty("database.pool-size", "5")));
            db.put("jdbc-url", props.getProperty("database.jdbc-url", ""));
            YamlFile.write(staff.file("database.yml"), db);
        }
        DatabaseConfig databaseConfig = staff.database("AdvancedStaff-Velocity-Pool");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(databaseConfig.jdbcUrl());
        config.setUsername(databaseConfig.username());
        config.setPassword(databaseConfig.password());
        config.setMaximumPoolSize(databaseConfig.maximumPoolSize());
        config.setPoolName(databaseConfig.poolName());
        // connectionTimeout only bounds pool waits; without socketTimeout a hung connect
        // or stalled query blocks the caller forever, so set both (event thread must never hang)
        config.setConnectionTimeout(5_000L);
        config.setValidationTimeout(3_000L);
        // socketTimeout is a connector/J property, not a Hikari one.
        config.addDataSourceProperty("socketTimeout", "10000");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource = new HikariDataSource(config);
        createSchemaIfNeeded();
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) dataSource.close();
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public int getEnforcementIntervalSeconds() {
        return enforcementIntervalSeconds;
    }

    public String getProxyId() {
        return proxyId;
    }

    // "" if none
    public String findOtherLiveProxy(UUID uuid) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT np.proxy_id FROM network_players np " +
                 "JOIN network_proxies pr ON pr.proxy_id = np.proxy_id " +
                 "WHERE np.uuid = ? AND np.proxy_id <> ? AND pr.last_seen >= " + DB_NOW_MILLIS + " - ?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, proxyId);
            statement.setLong(3, LIVE_PROXY_MILLIS);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    public List<RemotePlayer> loadRemotePlayers() throws SQLException {
        List<RemotePlayer> players = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT np.uuid, np.username, np.server_name FROM network_players np " +
                 "JOIN network_proxies pr ON pr.proxy_id = np.proxy_id " +
                 "WHERE np.proxy_id <> ? AND pr.last_seen >= " + DB_NOW_MILLIS + " - ?")) {
            statement.setString(1, proxyId);
            statement.setLong(2, LIVE_PROXY_MILLIS);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    players.add(new RemotePlayer(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return players;
    }

    public Connection openConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public Map<UUID, ModerationState> loadModerationStates(Collection<UUID> onlinePlayers) throws SQLException {
        if (onlinePlayers == null || onlinePlayers.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = List.copyOf(onlinePlayers);
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT player_uuid, type, duration_seconds, start_time FROM punishments " +
                "WHERE type IN ('BAN', 'MUTE') AND active = 1 " +
                "AND (duration_seconds <= 0 OR start_time + duration_seconds * 1000 > ?) " +
                "AND player_uuid IN (" + placeholders + ")";
        Map<UUID, boolean[]> flags = new HashMap<>();
        long now = System.currentTimeMillis();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setLong(parameter++, now);
            for (UUID uuid : ids) {
                statement.setString(parameter++, uuid.toString());
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String uuidValue = rs.getString("player_uuid");
                    if (uuidValue == null || uuidValue.isBlank()) {
                        continue;
                    }
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidValue);
                    } catch (IllegalArgumentException e) {
                        logger.warning("Skipping moderation row with invalid player UUID");
                        continue;
                    }
                    long duration = rs.getLong("duration_seconds");
                    if (rs.wasNull()) {
                        continue;
                    }
                    long start = rs.getLong("start_time");
                    if (rs.wasNull() || !isActive(duration, start, now)) {
                        continue;
                    }
                    boolean[] playerFlags = flags.computeIfAbsent(uuid, ignored -> new boolean[2]);
                    String type = rs.getString("type");
                    if ("BAN".equalsIgnoreCase(type)) {
                        playerFlags[0] = true;
                    } else if ("MUTE".equalsIgnoreCase(type)) {
                        playerFlags[1] = true;
                    }
                }
            }
        }
        Map<UUID, ModerationState> states = new HashMap<>();
        for (Map.Entry<UUID, boolean[]> entry : flags.entrySet()) {
            states.put(entry.getKey(), new ModerationState(entry.getValue()[0], entry.getValue()[1]));
        }
        return states;
    }

    public static boolean isActive(long durationSeconds, long startMillis, long now) {
        return ActivePunishmentPolicy.isActive(true, durationSeconds, startMillis, now);
    }

    private void saveProxyId(Path configFile, String id) {
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(configFile, StandardCharsets.UTF_8));
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("network.proxy-id")) {
                    lines.set(i, "network.proxy-id=" + id);
                    replaced = true;
                }
            }
            if (!replaced) lines.add("network.proxy-id=" + id);
            Files.write(configFile, lines, StandardCharsets.UTF_8);
            logger.info("Generated network.proxy-id=" + id + " and saved it to config.properties");
        } catch (IOException e) {
            logger.warning("Could not save network.proxy-id, a new one is used next start: " + e.getMessage());
        }
    }

    private int readInterval(String value) {
        try {
            return Math.max(1, Math.min(300, Integer.parseInt(value)));
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private void createSchemaIfNeeded() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS punishments (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "player_uuid VARCHAR(36), staff_uuid VARCHAR(36), type VARCHAR(8), reason TEXT, " +
                "start_time BIGINT, end_time BIGINT, duration_seconds BIGINT, active TINYINT(1), " +
                "KEY idx_punishments_lookup (player_uuid, type, active))");
            ensureIndex(connection, "punishments", "idx_punishments_active_type", "active, type");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS network_players (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "username VARCHAR(16) NOT NULL, " +
                "server_name VARCHAR(64) NOT NULL, " +
                "joined_at BIGINT NOT NULL, " +
                "KEY idx_network_players_username (username))");
            ensureColumn(connection, "network_players", "proxy_id", "VARCHAR(64) NOT NULL DEFAULT ''");
            ensureIndex(connection, "network_players", "idx_network_players_proxy", "proxy_id");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS network_proxies (" +
                "proxy_id VARCHAR(64) PRIMARY KEY, " +
                "last_seen BIGINT NOT NULL)");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS network_messages (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "source_proxy VARCHAR(64) NOT NULL, " +
                "channel VARCHAR(64) NOT NULL, " +
                "payload MEDIUMTEXT NOT NULL, " +
                "created_at BIGINT NOT NULL)");
            ensureIndex(connection, "network_messages", "idx_network_messages_created", "created_at");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_names (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "username VARCHAR(16) NOT NULL, " +
                "last_seen BIGINT NOT NULL, " +
                "KEY idx_player_names_username (username))");
        }
    }

    private void ensureIndex(Connection connection, String table, String indexName, String columns) {
        try (PreparedStatement check = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?")) {
            check.setString(1, table);
            check.setString(2, indexName);
            try (ResultSet rs = check.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return;
                }
            }
        } catch (SQLException e) {
            // don't swallow this: a missing privilege used to leave the index uncreated
            // and startup still reported success, turning a per-second query into a full table scan
            throw new IllegalStateException("Index check failed for " + indexName, e);
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + indexName + " ON " + table + " (" + columns + ")");
        } catch (SQLException e) {
            if (e.getErrorCode() != 1061) {
                throw new IllegalStateException("Failed to create index " + indexName, e);
            }
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String definition) {
        try (PreparedStatement check = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            check.setString(1, table);
            check.setString(2, column);
            try (ResultSet rs = check.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Column check failed for " + table + "." + column, e);
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            if (e.getErrorCode() != 1060) {
                throw new IllegalStateException("Failed to add column " + table + "." + column, e);
            }
        }
    }

    public record ModerationState(boolean banned, boolean muted) {}

    public record RemotePlayer(String uuid, String username, String serverName) {}
}
