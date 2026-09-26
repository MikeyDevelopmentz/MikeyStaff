package mikey.me.staffsystem.database.mysql;

import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.staffsystem.database.models.PlayerReport;
import mikey.me.staffsystem.database.repositories.PlayerReportRepository;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class MysqlPlayerReportRepository implements PlayerReportRepository {

    private final DatabaseManager databaseManager;

    public MysqlPlayerReportRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public CompletableFuture<PlayerReport> insert(PlayerReport report) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO reports (reported_uuid, reporter_uuid, reason, created_at, solved, solved_by_uuid, solved_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, report.getReportedUuid().toString());
                statement.setString(2, report.getReporterUuid().toString());
                statement.setString(3, report.getReason());
                statement.setLong(4, report.getCreatedAt());
                statement.setInt(5, report.isSolved() ? 1 : 0);
                if (report.getSolvedByUuid() != null) {
                    statement.setString(6, report.getSolvedByUuid().toString());
                } else {
                    statement.setNull(6, Types.VARCHAR);
                }
                if (report.getSolvedAt() > 0L) {
                    statement.setLong(7, report.getSolvedAt());
                } else {
                    statement.setNull(7, Types.BIGINT);
                }
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        return new PlayerReport(id, report.getReportedUuid(), report.getReporterUuid(),
                            report.getReason(), report.getCreatedAt(), report.isSolved(),
                            report.getSolvedByUuid(), report.getSolvedAt());
                    }
                }
                throw new IllegalStateException("Insert report did not return a generated id");
            } catch (SQLException e) {
                databaseManager.logSqlFailure("insert report", e);
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<PlayerReport> findById(long id) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, reported_uuid, reporter_uuid, reason, created_at, solved, solved_by_uuid, solved_at " +
                     "FROM reports WHERE id = ?")) {
                statement.setLong(1, id);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) return mapRow(rs);
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find report id=" + id, e);
                throw new CompletionException(e);
            }
            return null;
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<List<PlayerReport>> findByReported(UUID reportedUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerReport> result = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, reported_uuid, reporter_uuid, reason, created_at, solved, solved_by_uuid, solved_at " +
                     "FROM reports WHERE reported_uuid = ? ORDER BY created_at DESC")) {
                statement.setString(1, reportedUuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find reports by reported", e);
                throw new CompletionException(e);
            }
            return result;
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<List<PlayerReport>> findAll() {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerReport> result = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, reported_uuid, reporter_uuid, reason, created_at, solved, solved_by_uuid, solved_at " +
                     "FROM reports ORDER BY created_at DESC")) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find all reports", e);
                throw new CompletionException(e);
            }
            return result;
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<Void> markSolved(long id, UUID staffUuid, long solvedAt) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE reports SET solved = 1, solved_by_uuid = ?, solved_at = ? WHERE id = ?")) {
                statement.setString(1, staffUuid.toString());
                statement.setLong(2, solvedAt);
                statement.setLong(3, id);
                if (statement.executeUpdate() == 0) {
                    throw new SQLException("Report not found: " + id);
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("mark report solved id=" + id, e);
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<Void> deleteByReporter(UUID reporterUuid) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM reports WHERE reporter_uuid = ?")) {
                statement.setString(1, reporterUuid.toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                databaseManager.logSqlFailure("delete reports by reporter", e);
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<List<UUID>> findDistinctReporters() {
        return CompletableFuture.supplyAsync(() -> {
            List<UUID> result = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT DISTINCT reporter_uuid FROM reports")) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = MysqlFreezeLogRepository.parseUuid(rs.getString("reporter_uuid"));
                        if (uuid != null) result.add(uuid);
                    }
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find distinct reporters", e);
                throw new CompletionException(e);
            }
            return result;
        }, databaseManager.getDbExecutor());
    }

    private PlayerReport mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        UUID repUuid = MysqlFreezeLogRepository.parseUuid(rs.getString("reported_uuid"));
        UUID reporterUuid = MysqlFreezeLogRepository.parseUuid(rs.getString("reporter_uuid"));
        String reason = rs.getString("reason");
        long createdAt = rs.getLong("created_at");
        boolean solved = rs.getInt("solved") == 1;
        String solvedByStr = rs.getString("solved_by_uuid");
        UUID solvedByUuid = MysqlFreezeLogRepository.parseUuid(solvedByStr);
        long solvedAt = rs.getLong("solved_at");
        if (rs.wasNull()) solvedAt = 0L;
        return new PlayerReport(id, repUuid, reporterUuid, reason, createdAt, solved, solvedByUuid, solvedAt);
    }
}
