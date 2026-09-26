package mikey.me.staffsystem.database.mysql;

import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.staffsystem.database.models.PunishmentLog;
import mikey.me.staffsystem.database.repositories.PunishmentLogRepository;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class MysqlPunishmentLogRepository implements PunishmentLogRepository {

    private final DatabaseManager databaseManager;

    public MysqlPunishmentLogRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public CompletableFuture<Void> insert(PunishmentLog log) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO punishments (player_uuid, staff_uuid, type, reason, start_time, end_time, duration_seconds, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, log.getPlayerUuid().toString());
                statement.setString(2, log.getStaffUuid().toString());
                statement.setString(3, log.getType());
                statement.setString(4, log.getReason());
                statement.setLong(5, log.getStartTime());
                if (log.getEndTime() == null) {
                    statement.setNull(6, Types.BIGINT);
                } else {
                    statement.setLong(6, log.getEndTime());
                }
                statement.setLong(7, log.getDurationSeconds());
                statement.setInt(8, log.isActive() ? 1 : 0);
                statement.executeUpdate();
            } catch (SQLException e) {
                databaseManager.logSqlFailure("insert punishment log", e);
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<Boolean> insertIfNoActive(PunishmentLog log) {
        return CompletableFuture.supplyAsync(() -> {
            // named lock per player and type, so two staff at once cant both insert
            String lockName = "advancedstaff_" + log.getType() + "_" + log.getPlayerUuid();
            try (Connection connection = databaseManager.openConnection()) {
                try (PreparedStatement lock = connection.prepareStatement("SELECT GET_LOCK(?, 10)")) {
                    lock.setString(1, lockName);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next() || rs.getInt(1) != 1) {
                            throw new SQLException("timed out waiting for punishment lock");
                        }
                    }
                }
                try {
                    try (PreparedStatement check = connection.prepareStatement(
                             "SELECT COUNT(*) FROM punishments WHERE player_uuid = ? AND type = ? AND active = 1 " +
                             "AND (duration_seconds <= 0 OR start_time + duration_seconds * 1000 > ?)")) {
                        check.setString(1, log.getPlayerUuid().toString());
                        check.setString(2, log.getType());
                        check.setLong(3, System.currentTimeMillis());
                        try (ResultSet rs = check.executeQuery()) {
                            if (rs.next() && rs.getInt(1) > 0) {
                                return false;
                            }
                        }
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                             "INSERT INTO punishments (player_uuid, staff_uuid, type, reason, start_time, end_time, duration_seconds, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        statement.setString(1, log.getPlayerUuid().toString());
                        statement.setString(2, log.getStaffUuid().toString());
                        statement.setString(3, log.getType());
                        statement.setString(4, log.getReason());
                        statement.setLong(5, log.getStartTime());
                        if (log.getEndTime() == null) {
                            statement.setNull(6, Types.BIGINT);
                        } else {
                            statement.setLong(6, log.getEndTime());
                        }
                        statement.setLong(7, log.getDurationSeconds());
                        statement.setInt(8, log.isActive() ? 1 : 0);
                        statement.executeUpdate();
                    }
                    return true;
                } finally {
                    try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                        release.setString(1, lockName);
                        release.executeQuery().close();
                    }
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("insert punishment log", e);
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<Optional<PunishmentLog>> findActiveByPlayerAndType(UUID playerUuid, String type) {
        return findActiveByPlayerAndTypeAll(playerUuid, type)
            .thenApply(logs -> logs.stream().findFirst());
    }

    @Override
    public CompletableFuture<List<PunishmentLog>> findActiveByPlayerAndTypeAll(UUID playerUuid, String type) {
        return CompletableFuture.supplyAsync(() -> {
            List<PunishmentLog> result = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, player_uuid, staff_uuid, type, reason, start_time, end_time, duration_seconds, active " +
                     "FROM punishments WHERE player_uuid = ? AND type = ? AND active = 1 ORDER BY id ASC")) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, type);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
                return result;
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find active punishments", e);
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<List<PunishmentLog>> findByPlayer(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<PunishmentLog> result = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, player_uuid, staff_uuid, type, reason, start_time, end_time, duration_seconds, active " +
                     "FROM punishments WHERE player_uuid = ? ORDER BY id DESC")) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
                return result;
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find punishment history", e);
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<Boolean> deactivateActiveByPlayerAndType(UUID playerUuid, String type, long endTimeMillis) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE punishments SET active = 0, end_time = ? WHERE player_uuid = ? AND type = ? AND active = 1")) {
                statement.setLong(1, endTimeMillis);
                statement.setString(2, playerUuid.toString());
                statement.setString(3, type);
                return statement.executeUpdate() > 0;
            } catch (SQLException e) {
                databaseManager.logSqlFailure("deactivate punishment", e);
                // rethrow so the caller knows the unban/unmute didnt stick
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    private PunishmentLog mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        UUID playerUuid = MysqlFreezeLogRepository.parseUuid(rs.getString("player_uuid"));
        UUID staffUuid = MysqlFreezeLogRepository.parseUuid(rs.getString("staff_uuid"));
        String type = rs.getString("type");
        String reason = rs.getString("reason");
        long startTime = rs.getLong("start_time");
        long endVal = rs.getLong("end_time");
        Long endTime = rs.wasNull() ? null : endVal;
        long durationSeconds = rs.getLong("duration_seconds");
        boolean active = rs.getInt("active") == 1;
        return new PunishmentLog(id, playerUuid, staffUuid, type, reason, startTime, endTime, durationSeconds, active);
    }
}
