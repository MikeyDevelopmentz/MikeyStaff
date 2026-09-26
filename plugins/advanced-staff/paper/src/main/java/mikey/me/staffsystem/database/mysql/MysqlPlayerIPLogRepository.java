package mikey.me.staffsystem.database.mysql;

import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.staffsystem.database.models.PlayerIPLog;
import mikey.me.staffsystem.database.repositories.PlayerIPLogRepository;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class MysqlPlayerIPLogRepository implements PlayerIPLogRepository {

    private final DatabaseManager databaseManager;

    public MysqlPlayerIPLogRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public CompletableFuture<Void> logIP(UUID playerUuid, String ipAddress, long timestamp) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO player_ip_logs (player_uuid, ip_address, first_seen, last_seen) VALUES (?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen)")) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, ipAddress);
                statement.setLong(3, timestamp);
                statement.setLong(4, timestamp);
                statement.executeUpdate();
            } catch (SQLException e) {
                databaseManager.logSqlFailure("log player ip", e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<List<PlayerIPLog>> getIPsByPlayer(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerIPLog> logs = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, player_uuid, ip_address, first_seen, last_seen FROM player_ip_logs " +
                     "WHERE player_uuid = ? ORDER BY last_seen DESC")) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        logs.add(new PlayerIPLog(
                            rs.getLong("id"),
                            MysqlFreezeLogRepository.parseUuid(rs.getString("player_uuid")),
                            rs.getString("ip_address"),
                            rs.getLong("first_seen"),
                            rs.getLong("last_seen")));
                    }
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find ips by player", e);
                throw new java.util.concurrent.CompletionException(e);
            }
            return logs;
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<List<UUID>> getAltAccounts(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<UUID> alts = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT DISTINCT player_uuid FROM player_ip_logs " +
                     "WHERE ip_address IN (SELECT ip_address FROM player_ip_logs WHERE player_uuid = ?) " +
                     "AND player_uuid != ?")) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, playerUuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = MysqlFreezeLogRepository.parseUuid(rs.getString("player_uuid"));
                        if (uuid != null) alts.add(uuid);
                    }
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find alt accounts", e);
                throw new java.util.concurrent.CompletionException(e);
            }
            return alts;
        }, databaseManager.getDbExecutor());
    }

    // one joined query instead of a lookup per linked account
    @Override
    public CompletableFuture<List<UUID>> getBannedPlayersByIP(String ipAddress, UUID excludeUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<UUID> players = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT l.player_uuid FROM player_ip_logs l " +
                     "JOIN punishments p ON p.player_uuid = l.player_uuid " +
                     "WHERE l.ip_address = ? AND l.player_uuid != ? " +
                     "AND p.type = 'BAN' AND p.active = 1 " +
                     "AND (p.duration_seconds <= 0 OR p.start_time + p.duration_seconds * 1000 > ?) " +
                     "GROUP BY l.player_uuid " +
                     "ORDER BY MAX(l.last_seen) DESC, MAX(l.id) DESC LIMIT 1")) {
                statement.setString(1, ipAddress);
                statement.setString(2, excludeUuid.toString());
                statement.setLong(3, System.currentTimeMillis());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = MysqlFreezeLogRepository.parseUuid(rs.getString("player_uuid"));
                        if (uuid != null) players.add(uuid);
                    }
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("getBannedPlayersByIP ip=" + ipAddress, e);
                throw new java.util.concurrent.CompletionException(e);
            }
            return players;
        }, databaseManager.getDbExecutor());
    }
}
