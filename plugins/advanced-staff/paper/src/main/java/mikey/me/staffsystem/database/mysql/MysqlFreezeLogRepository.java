package mikey.me.staffsystem.database.mysql;

import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.staffsystem.database.models.FreezeLog;
import mikey.me.staffsystem.database.repositories.FreezeLogRepository;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class MysqlFreezeLogRepository implements FreezeLogRepository {

    private final DatabaseManager databaseManager;

    public MysqlFreezeLogRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public CompletableFuture<Void> insert(FreezeLog log) {
        return insertAndReturn(log).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<FreezeLog> insertAndReturn(FreezeLog log) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO freeze_logs (player_uuid, staff_uuid, reason, start_time, end_time, duration_seconds, active) VALUES (?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, log.getPlayerUuid().toString());
                statement.setString(2, log.getStaffUuid().toString());
                statement.setString(3, log.getReason());
                statement.setLong(4, log.getStartTime());
                if (log.getEndTime() == null) {
                    statement.setNull(5, Types.BIGINT);
                } else {
                    statement.setLong(5, log.getEndTime());
                }
                statement.setLong(6, log.getDurationSeconds());
                statement.setInt(7, log.isActive() ? 1 : 0);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        return new FreezeLog(id, log.getPlayerUuid(), log.getStaffUuid(), log.getReason(),
                                log.getStartTime(), log.getEndTime(), log.getDurationSeconds(), log.isActive());
                    }
                }
                throw new SQLException("Insert did not return a generated freeze id");
            } catch (SQLException e) {
                databaseManager.logSqlFailure("insert freeze log", e);
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<Optional<FreezeLog>> findActiveByPlayer(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, player_uuid, staff_uuid, reason, start_time, end_time, duration_seconds, active " +
                     "FROM freeze_logs WHERE player_uuid = ? AND active = 1 ORDER BY id DESC LIMIT 1")) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) return Optional.of(mapRow(rs));
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find active freeze log", e);
                throw new CompletionException(e);
            }
            return Optional.empty();
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<List<FreezeLog>> findActive() {
        return CompletableFuture.supplyAsync(() -> {
            List<FreezeLog> result = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, player_uuid, staff_uuid, reason, start_time, end_time, duration_seconds, active " +
                     "FROM freeze_logs WHERE active = 1 ORDER BY id DESC")) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find active freeze logs", e);
                throw new CompletionException(e);
            }
            return result;
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<List<FreezeLog>> findByPlayer(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<FreezeLog> result = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, player_uuid, staff_uuid, reason, start_time, end_time, duration_seconds, active " +
                     "FROM freeze_logs WHERE player_uuid = ? ORDER BY id DESC")) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find freeze logs by player", e);
                throw new CompletionException(e);
            }
            return result;
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<Void> markInactive(long id, long endTime) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE freeze_logs SET active = 0, end_time = ? WHERE id = ?")) {
                statement.setLong(1, endTime);
                statement.setLong(2, id);
                statement.executeUpdate();
            } catch (SQLException e) {
                databaseManager.logSqlFailure("mark freeze log inactive id=" + id, e);
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    private FreezeLog mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        UUID playerUuid = parseUuid(rs.getString("player_uuid"));
        UUID staffUuid = parseUuid(rs.getString("staff_uuid"));
        String reason = rs.getString("reason");
        long startTime = rs.getLong("start_time");
        long endVal = rs.getLong("end_time");
        Long endTime = rs.wasNull() ? null : endVal;
        long durationSeconds = rs.getLong("duration_seconds");
        boolean active = rs.getInt("active") == 1;
        return new FreezeLog(id, playerUuid, staffUuid, reason, startTime, endTime, durationSeconds, active);
    }

    static UUID parseUuid(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
