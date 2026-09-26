package mikey.me.staffsystem.database.mysql;

import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.staffsystem.database.models.PlayerNote;
import mikey.me.staffsystem.database.repositories.PlayerNoteRepository;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class MysqlPlayerNoteRepository implements PlayerNoteRepository {

    private final DatabaseManager databaseManager;

    public MysqlPlayerNoteRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public CompletableFuture<PlayerNote> insert(PlayerNote note) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO notes (player_uuid, staff_uuid, text, created_at) VALUES (?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, note.getPlayerUuid().toString());
                statement.setString(2, note.getStaffUuid().toString());
                statement.setString(3, note.getText());
                statement.setLong(4, note.getCreatedAt());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        return new PlayerNote(id, note.getPlayerUuid(), note.getStaffUuid(), note.getText(), note.getCreatedAt());
                    }
                }
                throw new SQLException("Insert did not return a generated note id");
            } catch (SQLException e) {
                databaseManager.logSqlFailure("insert note", e);
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<Boolean> deleteById(long id) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM notes WHERE id = ?")) {
                statement.setLong(1, id);
                return statement.executeUpdate() > 0;
            } catch (SQLException e) {
                databaseManager.logSqlFailure("delete note id=" + id, e);
                throw new CompletionException(e);
            }
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<Optional<PlayerNote>> findById(long id) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, player_uuid, staff_uuid, text, created_at FROM notes WHERE id = ?")) {
                statement.setLong(1, id);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) return Optional.of(mapRow(rs));
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find note id=" + id, e);
                throw new CompletionException(e);
            }
            return Optional.empty();
        }, databaseManager.getDbExecutor());
    }

    @Override
    public CompletableFuture<List<PlayerNote>> findByPlayer(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerNote> result = new ArrayList<>();
            try (Connection connection = databaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, player_uuid, staff_uuid, text, created_at FROM notes WHERE player_uuid = ? ORDER BY id DESC")) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            } catch (SQLException e) {
                databaseManager.logSqlFailure("find notes by player", e);
                throw new CompletionException(e);
            }
            return result;
        }, databaseManager.getDbExecutor());
    }

    private PlayerNote mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        UUID playerUuid = MysqlFreezeLogRepository.parseUuid(rs.getString("player_uuid"));
        UUID staffUuid = MysqlFreezeLogRepository.parseUuid(rs.getString("staff_uuid"));
        String text = rs.getString("text");
        long createdAt = rs.getLong("created_at");
        return new PlayerNote(id, playerUuid, staffUuid, text, createdAt);
    }
}
