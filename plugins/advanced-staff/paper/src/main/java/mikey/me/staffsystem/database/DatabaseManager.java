package mikey.me.staffsystem.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import mikey.me.core.persistence.DatabaseConfig;
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
        HikariConfig config = new HikariConfig();
        // autoReconnect is broken with pools, hikari handles dead connections itself
        String jdbcUrl = "jdbc:mysql://" + settings.getDbHost() + ":" + settings.getDbPort()
                + "/" + settings.getDbDatabase()
                + "?useSSL=false&characterEncoding=utf8";
        DatabaseConfig databaseConfig = new DatabaseConfig(jdbcUrl, settings.getDbUser(), settings.getDbPassword(),
                settings.getDbPoolSize(), "AdvancedStaff-Pool");
        config.setJdbcUrl(databaseConfig.jdbcUrl());
        config.setUsername(databaseConfig.username());
        config.setPassword(databaseConfig.password());
        config.setMaximumPoolSize(databaseConfig.maximumPoolSize());
        config.setPoolName(databaseConfig.poolName());
        config.setConnectionTimeout(5_000L);
        dataSource = new HikariDataSource(config);
        createSchemaIfNeeded();
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

    // db work goes here so we never block the jvm common pool with jdbc
    public Executor getDbExecutor() {
        return dbExecutor;
    }

    public void logSqlFailure(String action, SQLException exception) {
        plugin.getLogger().warning(action + ": " + exception.getMessage());
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
                "staff_uuid VARCHAR(36) NOT NULL, start_time BIGINT, end_time BIGINT, serialized_inventory MEDIUMTEXT)");
             ensureIndex(connection, "staff_sessions", "idx_staff_sessions_staff", "staff_uuid");
             ensureIndex(connection, "staff_sessions", "idx_staff_sessions_open", "staff_uuid, end_time, id");

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
