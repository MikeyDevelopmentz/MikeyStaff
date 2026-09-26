package mikey.me.advancedstaff.velocity.listeners;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class ProxyStateCache {

    private ProxyStateCache() {}

    static Optional<Boolean> cachedState(Map<UUID, Boolean> states, UUID uuid) {
        if (!states.containsKey(uuid)) {
            return Optional.empty();
        }
        return Optional.ofNullable(states.get(uuid));
    }
}
