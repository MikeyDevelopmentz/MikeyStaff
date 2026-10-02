package mikey.me.staffsystem.database.mysql;

import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.staffsystem.database.models.LoginLog;
import mikey.me.staffsystem.database.repositories.LoginLogRepository;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class MysqlLoginLogRepository implements LoginLogRepository {

    private final DatabaseManager databaseManager;

    public MysqlLoginLogRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public void logLogin(UUID playerUuid, String ipAddress, long loginTime) {
        CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO login_logs (player_uuid, ip_address, login_time) VALUES (?, ?, ?)")) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, ipAddress);
                statement.setLong(3, loginTime);
                statement.executeUpdate();
            } catch (SQLException e) {
                // rethrow so caller sees failure; swallowing made logs silently disappear
                databaseManager.logSqlFailure("insert login log", e);
                throw new java.util.concurrent.CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<List<LoginLog>> getLoginLogs(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<LoginLog> logs = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, player_uuid, ip_address, login_time FROM login_logs " +
                     "WHERE player_uuid = ? ORDER BY login_time DESC")) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        logs.add(new LoginLog(
                            rs.getLong("id"),
                            MysqlFreezeLogRepository.parseUuid(rs.getString("player_uuid")),
                            rs.getString("ip_address"),
                            rs.getLong("login_time")));
                    }
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find login logs", e);
                throw new java.util.concurrent.CompletionException(e);
            }
            return logs;
        }, databaseManager.getDbExecutor());
    }
}
