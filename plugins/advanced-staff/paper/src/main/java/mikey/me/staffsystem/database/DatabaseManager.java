package mikey.me.staffsystem.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import mikey.me.core.persistence.DatabaseConfig;
import mikey.me.core.persistence.YamlFile;
import mikey.me.core.registry.CoreRegistry;
import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.mysql.*;
import mikey.me.staffsystem.database.repositories.*;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DatabaseManager {

    private final Plugin plugin;
    private final SettingsConfig settings;
    private final ExecutorService dbExecutor;
    private HikariDataSource dataSource;
    private String serverId;

    public DatabaseManager(Plugin plugin, ConfigurationManager configurationManager) {
        this.plugin = plugin;
        this.settings = configurationManager.getSettings();
        int threads = Math.max(2, settings.getDbPoolSize());
        this.dbExecutor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "AdvancedStaff-DB");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void initialize() {
        CoreRegistry.PluginData data = CoreRegistry.register("staff");
        try {
            data.copyIfAbsent("server-id.txt", plugin.getDataFolder().toPath().resolve("server-id.txt"));
            seedDatabase(data);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("couldnt set up staff files", e);
        }
        serverId = ServerIdentity.load(data.folder());
        HikariConfig config = new HikariConfig();
        // autoReconnect is broken with pools, hikari handles dead connections itself
        DatabaseConfig databaseConfig;
        try {
            databaseConfig = data.database("AdvancedStaff-Pool");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("couldnt read database.yml", e);
        }
        config.setJdbcUrl(databaseConfig.jdbcUrl());
        config.setUsername(databaseConfig.username());
        config.setPassword(databaseConfig.password());
        config.setMaximumPoolSize(databaseConfig.maximumPoolSize());
        config.setPoolName(databaseConfig.poolName());
        config.setConnectionTimeout(5_000L);
        dataSource = new HikariDataSource(config);
        createSchemaIfNeeded();
    }

    private void seedDatabase(CoreRegistry.PluginData data) throws java.io.IOException {
        if (java.nio.file.Files.exists(data.file("database.yml"))) {
            return;
        }
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("host", settings.getDbHost());
        map.put("port", settings.getDbPort());
        map.put("database", settings.getDbDatabase());
        map.put("user", settings.getDbUser());
        map.put("password", settings.getDbPassword());
        map.put("pool-size", settings.getDbPoolSize());
        map.put("jdbc-url", "");
        YamlFile.write(data.file("database.yml"), map);
    }

    public void shutdown() {
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("db executor did not drain in time, some writes may be lost");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public Connection openConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public String getServerId() {
        return serverId;
    }

    // db work goes here so we never block the jvm common pool with jdbc
    public Executor getDbExecutor() {
        return dbExecutor;
    }

    public void logSqlFailure(String action, SQLException exception) {
        // pass the throwable so the stack trace survives, getMessage() alone made sql failures undiagnosable
        plugin.getLogger().log(java.util.logging.Level.WARNING, action + " failed", exception);
    }

    public FreezeLogRepository createFreezeLogRepository() {
        return new MysqlFreezeLogRepository(this);
    }

    public VanishLogRepository createVanishLogRepository() {
        return new MysqlVanishLogRepository(this);
    }

    public PlayerNoteRepository createPlayerNoteRepository() {
        return new MysqlPlayerNoteRepository(this);
    }

    public PunishmentLogRepository createPunishmentLogRepository() {
        return new MysqlPunishmentLogRepository(this);
    }

    public PlayerReportRepository createPlayerReportRepository() {
        return new MysqlPlayerReportRepository(this);
    }

    public StaffSessionRepository createStaffSessionRepository() {
        return new MysqlStaffSessionRepository(this);
    }

    public PlayerIPLogRepository createPlayerIPLogRepository() {
        return new MysqlPlayerIPLogRepository(this);
    }

    public LoginLogRepository createLoginLogRepository() {
        return new MysqlLoginLogRepository(this);
    }

    private void createSchemaIfNeeded() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS freeze_logs (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "player_uuid VARCHAR(36) NOT NULL, staff_uuid VARCHAR(36) NOT NULL, reason TEXT, " +
                "start_time BIGINT, end_time BIGINT, duration_seconds BIGINT, active TINYINT(1))");
            ensureIndex(connection, "freeze_logs", "idx_freeze_logs_player", "player_uuid");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS vanish_logs (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "player_uuid VARCHAR(36) NOT NULL, staff_uuid VARCHAR(36) NOT NULL, action VARCHAR(16), timestamp BIGINT)");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS notes (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "player_uuid VARCHAR(36) NOT NULL, staff_uuid VARCHAR(36) NOT NULL, text TEXT, created_at BIGINT)");
            ensureIndex(connection, "notes", "idx_notes_player", "player_uuid");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS reports (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "reported_uuid VARCHAR(36) NOT NULL, reporter_uuid VARCHAR(36) NOT NULL, reason TEXT, created_at BIGINT, " +
                "solved TINYINT(1) DEFAULT 0, solved_by_uuid VARCHAR(36), solved_at BIGINT)");
            ensureIndex(connection, "reports", "idx_reports_reported", "reported_uuid");
            ensureIndex(connection, "reports", "idx_reports_reporter", "reporter_uuid");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS punishments (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "player_uuid VARCHAR(36) NOT NULL, staff_uuid VARCHAR(36) NOT NULL, type VARCHAR(8), reason TEXT, " +
                "start_time BIGINT, end_time BIGINT, duration_seconds BIGINT, active TINYINT(1))");
            ensureIndex(connection, "punishments", "idx_punishments_lookup", "player_uuid, type, active");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS staff_sessions (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "staff_uuid VARCHAR(36) NOT NULL, server_id VARCHAR(36), " +
                "start_time BIGINT, end_time BIGINT, serialized_inventory MEDIUMTEXT)");
            ensureStaffSessionServerId(connection);
            ensureIndex(connection, "staff_sessions", "idx_staff_sessions_staff", "staff_uuid");
            ensureIndex(connection, "staff_sessions", "idx_staff_sessions_open", "staff_uuid, end_time, id");
            ensureIndex(connection, "staff_sessions", "idx_staff_sessions_server", "staff_uuid, server_id, end_time, id");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_ip_logs (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "player_uuid VARCHAR(36) NOT NULL, ip_address VARCHAR(45) NOT NULL, " +
                "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, " +
                "UNIQUE KEY uq_player_ip (player_uuid, ip_address))");
            ensureIndex(connection, "player_ip_logs", "idx_player_ip_logs_ip_address", "ip_address");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS login_logs (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "player_uuid VARCHAR(36) NOT NULL, ip_address VARCHAR(45) NOT NULL, login_time BIGINT NOT NULL)");
            ensureIndex(connection, "login_logs", "idx_login_logs_player", "player_uuid");

            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS network_players (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "username VARCHAR(16) NOT NULL, " +
                "server_name VARCHAR(64) NOT NULL, " +
                "joined_at BIGINT NOT NULL)");
            ensureIndex(connection, "network_players", "idx_network_players_username", "username");
            // must match velocity's definition or its "proxy_id = ''" cleanup matches nothing
            ensureColumn(connection, "network_players", "proxy_id", "VARCHAR(64) NOT NULL DEFAULT ''");

            // created by the velocity module but used by the relay, without it the relay silently degrades to in-memory and isOnNetwork() always reports false
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
                "last_seen BIGINT NOT NULL)");
            ensureIndex(connection, "player_names", "idx_player_names_username", "username");

        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create database schema: " + e.getMessage());
            throw new RuntimeException("Database schema creation failed", e);
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String definition) {
        // information_schema instead of DatabaseMetaData.getColumns: null schema matches everything and "_" acts as a wildcard there
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
            throw new RuntimeException("Column check failed for " + table + "." + column, e);
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            // 1060 = duplicate column, which is fine when two backends boot together
            if (e.getErrorCode() != 1060) {
                throw new RuntimeException("Failed to add column " + table + "." + column, e);
            }
        }
    }

    static void ensureStaffSessionServerId(Connection connection) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, "staff_sessions", "server_id")) {
            if (columns.next()) return;
        }
        try (Statement statement = connection.createStatement()) {
            // old backups stay unassigned, we dont know which server they came from
            statement.executeUpdate("ALTER TABLE staff_sessions ADD COLUMN server_id VARCHAR(36)");
        } catch (SQLException e) {
            if (e.getErrorCode() != 1060) throw e;
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
            plugin.getLogger().warning("Index check failed for " + indexName + ": " + e.getMessage());
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + indexName + " ON " + table + " (" + columns + ")");
        } catch (SQLException e) {
            // two backends booting at the same time can race, duplicate index is fine
            if (e.getErrorCode() != 1061) {
                plugin.getLogger().warning("Failed to create index " + indexName + ": " + e.getMessage());
            }
        }
    }
}
