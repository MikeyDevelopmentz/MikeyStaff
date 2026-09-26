package mikey.me.staffsystem.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNull;

class InventoryUtilTest {

    @Test
    void deserializeRejectsMissingDataBeforeBukkitInitialization() {
        InventoryUtil inventory = new InventoryUtil();

        assertNull(inventory.deserialize(null));
        assertNull(inventory.deserialize(""));
    }

    @Test
    void deserializeRejectsInvalidInventoryLengths() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(257);
        }

        InventoryUtil inventory = new InventoryUtil();

        assertNull(inventory.deserialize(Base64.getEncoder().encodeToString(bytes.toByteArray())));
    }
}
