package mikey.me.staffsystem.utils;

import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.staffsystem.messaging.VelocityMessenger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NetworkPlayerResolverTest {
    @Test
    void storedPlayerLookupWaitsForDatabaseWorker() throws Exception {
        DatabaseManager database = mock(DatabaseManager.class);
        Queue<Runnable> work = new ArrayDeque<>();
        when(database.getDbExecutor()).thenReturn(work::add);
        try (MockedStatic<Bukkit> ignored = mockStatic(Bukkit.class)) {
            NetworkPlayerResolver resolver = new NetworkPlayerResolver(database, mock(VelocityMessenger.class));
            work.clear();
            UUID uuid = UUID.randomUUID();
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet rows = mock(ResultSet.class);
            when(database.openConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(rows);
            when(rows.next()).thenReturn(true);
            when(rows.getString("uuid")).thenReturn(uuid.toString());

            CompletableFuture<UUID> result = resolver.resolvePlayerId("RemotePlayer");
            assertFalse(result.isDone());
            verify(database, never()).openConnection();
            work.remove().run();
            assertEquals(uuid, result.join());
            verify(statement).setString(1, "RemotePlayer");
        }
    }

    @Test
    void onlinePlayerNeedsNoDatabaseLookup() throws Exception {
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.getDbExecutor()).thenReturn(mock(Executor.class));
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer("LocalPlayer")).thenReturn(player);
            NetworkPlayerResolver resolver = new NetworkPlayerResolver(database, mock(VelocityMessenger.class));
            assertEquals(uuid, resolver.resolvePlayerId("LocalPlayer").join());
            verify(database, never()).openConnection();
        }
    }

    @Test
    void databaseFailureIsNotReportedAsAnUnknownPlayer() throws Exception {
        DatabaseManager database = mock(DatabaseManager.class);
        Queue<Runnable> work = new ArrayDeque<>();
        when(database.getDbExecutor()).thenReturn(work::add);
        when(database.openConnection()).thenThrow(new SQLException("offline"));
        try (MockedStatic<Bukkit> ignored = mockStatic(Bukkit.class)) {
            NetworkPlayerResolver resolver = new NetworkPlayerResolver(database, mock(VelocityMessenger.class));
            work.clear();
            CompletableFuture<UUID> result = resolver.resolvePlayerId("RemotePlayer");
            work.remove().run();
            assertThrows(CompletionException.class, result::join);
        }
    }

    @Test
    void displayNameIsLoadedInBackgroundAndCached() throws Exception {
        DatabaseManager database = mock(DatabaseManager.class);
        Queue<Runnable> work = new ArrayDeque<>();
        when(database.getDbExecutor()).thenReturn(work::add);
        UUID uuid = UUID.randomUUID();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(uuid)).thenReturn(mock(OfflinePlayer.class));
            NetworkPlayerResolver resolver = new NetworkPlayerResolver(database, mock(VelocityMessenger.class));
            work.clear();
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet rows = mock(ResultSet.class);
            when(database.openConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(rows);
            when(rows.next()).thenReturn(true);
            when(rows.getString(1)).thenReturn("RemoteStaff");
            assertEquals("Unknown", resolver.resolveName(uuid, "Unknown"));
            assertEquals("Unknown", resolver.resolveName(uuid, "Unknown"));
            assertEquals(1, work.size());
            verify(database, never()).openConnection();
            work.remove().run();
            assertEquals("RemoteStaff", resolver.resolveName(uuid, "Unknown"));
            assertTrue(work.isEmpty());
        }
    }
}
