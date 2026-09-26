package mikey.me.staffsystem.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

final class ServerIdentity {

    private ServerIdentity() {}

    static String load(Path dataFolder) {
        Path file = dataFolder.resolve("server-id.txt");
        try {
            Files.createDirectories(dataFolder);
            if (!Files.exists(file)) {
                Files.writeString(file, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
            }
            return UUID.fromString(Files.readString(file).trim()).toString();
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Couldn't read or save server-id.txt", e);
        }
    }
}
