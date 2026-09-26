package mikey.me.staffsystem.cache;

import java.util.UUID;

public class VanishState {

    private final UUID playerUuid;
    private final UUID staffUuid;
    private final long since;

    public VanishState(UUID playerUuid, UUID staffUuid, long since) {
        this.playerUuid = playerUuid;
        this.staffUuid = staffUuid;
        this.since = since;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public UUID getStaffUuid() {
        return staffUuid;
    }

    public long getSince() {
        return since;
    }
}
