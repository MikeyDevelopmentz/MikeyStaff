package mikey.me.staffsystem.database.mysql;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MysqlFreezeLogRepositoryTest {

    @Test
    void parsesOnlyCanonicalUuidValues() {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        assertEquals(uuid, MysqlFreezeLogRepository.parseUuid(uuid.toString()));
        assertEquals(uuid, MysqlFreezeLogRepository.parseUuid(uuid.toString().toUpperCase()));
        assertNull(MysqlFreezeLogRepository.parseUuid(null));
        assertNull(MysqlFreezeLogRepository.parseUuid(""));
        assertNull(MysqlFreezeLogRepository.parseUuid("   "));
        assertNull(MysqlFreezeLogRepository.parseUuid("not-a-uuid"));
        assertNull(MysqlFreezeLogRepository.parseUuid("123e4567-e89b-12d3-a456"));
    }
}
