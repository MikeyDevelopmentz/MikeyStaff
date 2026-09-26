package mikey.me.staffsystem.database.mysql;

import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.staffsystem.database.models.VanishLog;
import mikey.me.staffsystem.database.repositories.VanishLogRepository;

import java.sql.*;
import java.util.concurrent.CompletableFuture;

public class MysqlVanishLogRepository implements VanishLogRepository {

    private final DatabaseManager databaseManager;

    public MysqlVanishLogRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public CompletableFuture<Void> insert(VanishLog log) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO vanish_logs (player_uuid, staff_uuid, action, timestamp) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, log.getPlayerUuid().toString());
                statement.setString(2, log.getStaffUuid().toString());
                statement.setString(3, log.getAction());
                statement.setLong(4, log.getTimestamp());
                statement.executeUpdate();
            } catch (SQLException e) {
                databaseManager.logSqlFailure("insert vanish log", e);
            }
        }, databaseManager.getDbExecutor());
    }
}
