package mikey.me.staffsystem.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServerIdentityTest {

    @TempDir
    Path folder;

    @Test
    void identitySurvivesRestartAndDiffersBetweenServers() {
        String first = ServerIdentity.load(folder.resolve("first"));
        assertEquals(first, UUID.fromString(first).toString());
        assertEquals(first, ServerIdentity.load(folder.resolve("first")));
        assertNotEquals(first, ServerIdentity.load(folder.resolve("second")));
    }

    @Test
    void invalidIdentityIsNotSilentlyReplaced() throws Exception {
        Path file = folder.resolve("server-id.txt");
        Files.writeString(file, "broken");
        assertThrows(IllegalStateException.class, () -> ServerIdentity.load(folder));
        assertEquals("broken", Files.readString(file));
    }
}
