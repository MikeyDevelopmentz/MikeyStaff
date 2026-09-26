package mikey.me.advancedstaff.velocity.listeners;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPlayerTrackerTest {

    @Test
    void distinguishesUnknownStateFromExplicitFalse() {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Map<UUID, Boolean> states = new HashMap<>();

        assertTrue(ProxyStateCache.cachedState(states, uuid).isEmpty());

        states.put(uuid, false);
        assertEquals(Optional.of(false), ProxyStateCache.cachedState(states, uuid));

        states.put(uuid, true);
        assertEquals(Optional.of(true), ProxyStateCache.cachedState(states, uuid));
    }
}
