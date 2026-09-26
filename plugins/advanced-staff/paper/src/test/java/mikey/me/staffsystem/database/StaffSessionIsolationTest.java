package mikey.me.staffsystem.database;

import mikey.me.staffsystem.database.models.StaffSession;
import mikey.me.staffsystem.database.mysql.MysqlStaffSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StaffSessionIsolationTest {

    private String url;
    private Connection connection;
    private final UUID staff = UUID.randomUUID();
    private final String serverA = UUID.randomUUID().toString();
    private final String serverB = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE staff_sessions (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "staff_uuid VARCHAR(36) NOT NULL, start_time BIGINT, end_time BIGINT, serialized_inventory MEDIUMTEXT)");
        }
        DatabaseManager.ensureStaffSessionServerId(connection);
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
    }

    private MysqlStaffSessionRepository repository(String serverId) throws Exception {
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.getServerId()).thenReturn(serverId);
        when(database.getDbExecutor()).thenReturn(Runnable::run);
        when(database.openConnection()).thenAnswer(ignored -> DriverManager.getConnection(url));
        return new MysqlStaffSessionRepository(database);
    }

    @Test
    void anotherBackendCannotReadOrCloseTheBackup() throws Exception {
        MysqlStaffSessionRepository a = repository(serverA);
        MysqlStaffSessionRepository b = repository(serverB);
        StaffSession saved = a.startSession(staff, "inventory-a", 100L).join();
        assertTrue(b.findOpenByStaff(staff).join().isEmpty());
        assertTrue(b.findLatestByStaff(staff).join().isEmpty());
        b.endSession(saved.getId(), 200L).join();
        assertEquals(saved.getId(), a.findOpenByStaff(staff).join().orElseThrow().getId());
        assertEquals("inventory-a", repository(serverA).findOpenByStaff(staff).join().orElseThrow().getSerializedInventory());
    }

    @Test
    void eachBackendRestoresItsOwnInventory() throws Exception {
        MysqlStaffSessionRepository a = repository(serverA);
        MysqlStaffSessionRepository b = repository(serverB);
        a.startSession(staff, "inventory-a", 100L).join();
        StaffSession savedB = b.startSession(staff, "inventory-b", 200L).join();
        assertEquals("inventory-a", a.findOpenByStaff(staff).join().orElseThrow().getSerializedInventory());
        assertEquals("inventory-b", b.findOpenByStaff(staff).join().orElseThrow().getSerializedInventory());
        b.endSession(savedB.getId(), 300L).join();
        assertTrue(b.findOpenByStaff(staff).join().isEmpty());
        assertTrue(a.findOpenByStaff(staff).join().isPresent());
    }

    @Test
    void legacyBackupNeedsAnExplicitOwner() throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO staff_sessions (staff_uuid, start_time, serialized_inventory) VALUES (?, 100, 'old')")) {
            statement.setString(1, staff.toString());
            statement.executeUpdate();
        }
        DatabaseManager.ensureStaffSessionServerId(connection);
        MysqlStaffSessionRepository a = repository(serverA);
        MysqlStaffSessionRepository b = repository(serverB);
        assertThrows(CompletionException.class, () -> a.findOpenByStaff(staff).join());
        assertThrows(CompletionException.class, () -> b.findOpenByStaff(staff).join());
        try (var statement = connection.prepareStatement("UPDATE staff_sessions SET server_id = ?")) {
            statement.setString(1, serverA);
            statement.executeUpdate();
        }
        assertEquals("old", a.findOpenByStaff(staff).join().orElseThrow().getSerializedInventory());
        assertTrue(b.findOpenByStaff(staff).join().isEmpty());
    }
}
