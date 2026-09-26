package mikey.me.staffsystem.database.mysql;

import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.staffsystem.database.models.StaffSession;
import mikey.me.staffsystem.database.repositories.StaffSessionRepository;

import java.sql.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class MysqlStaffSessionRepository implements StaffSessionRepository {

    private final DatabaseManager databaseManager;

    public MysqlStaffSessionRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public CompletableFuture<StaffSession> startSession(UUID staffUuid, String serializedInventory, long startTime) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO staff_sessions (staff_uuid, start_time, end_time, serialized_inventory) VALUES (?, ?, NULL, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, staffUuid.toString());
                statement.setLong(2, startTime);
                statement.setString(3, serializedInventory);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        if (id > 0) {
                            return new StaffSession(id, staffUuid, startTime, null, serializedInventory);
                        }
                    }
                }
                throw new SQLException("staff session insert did not return a generated id");
            } catch (SQLException e) {
                databaseManager.logSqlFailure("start staff session", e);
                throw new java.util.concurrent.CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<Void> endSession(long id, long endTime) {
        if (id <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE staff_sessions SET end_time = ? WHERE id = ? AND end_time IS NULL")) {
                statement.setLong(1, endTime);
                statement.setLong(2, id);
                statement.executeUpdate();
            } catch (SQLException e) {
                databaseManager.logSqlFailure("end staff session id=" + id, e);
                throw new java.util.concurrent.CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<Optional<StaffSession>> findLatestByStaff(UUID staffUuid) {
        return findSession(staffUuid, false);
    }

    @Override
    public CompletableFuture<Optional<StaffSession>> findOpenByStaff(UUID staffUuid) {
        return findSession(staffUuid, true);
    }

    private CompletableFuture<Optional<StaffSession>> findSession(UUID staffUuid, boolean openOnly) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT id, staff_uuid, start_time, end_time, serialized_inventory " +
                    "FROM staff_sessions WHERE staff_uuid = ?" +
                    (openOnly ? " AND end_time IS NULL" : "") +
                    " ORDER BY id DESC LIMIT 1";
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, staffUuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong("id");
                        long startTime = rs.getLong("start_time");
                        long endVal = rs.getLong("end_time");
                        Long endTime = rs.wasNull() ? null : endVal;
                        String serializedInventory = rs.getString("serialized_inventory");
                        return Optional.of(new StaffSession(id, staffUuid, startTime, endTime, serializedInventory));
                    }
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure(openOnly ? "find open staff session" : "find latest staff session", e);
                throw new java.util.concurrent.CompletionException(e);
            }
            return Optional.empty();
        }, databaseManager.getDbExecutor());
    }
}
