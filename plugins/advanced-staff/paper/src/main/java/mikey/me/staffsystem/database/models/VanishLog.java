package mikey.me.staffsystem.database.models;

import java.util.UUID;

public class VanishLog {

    private final long id;
    private final UUID playerUuid;
    private final UUID staffUuid;
    private final String action;
    private final long timestamp;

    public VanishLog(long id, UUID playerUuid, UUID staffUuid, String action, long timestamp) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.staffUuid = staffUuid;
        this.action = action;
        this.timestamp = timestamp;
    }

    public long getId() {
        return id;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public UUID getStaffUuid() {
        return staffUuid;
    }

    public String getAction() {
        return action;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
