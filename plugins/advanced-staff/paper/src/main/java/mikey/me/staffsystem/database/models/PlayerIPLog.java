package mikey.me.staffsystem.database.models;

import java.util.UUID;

public class PlayerIPLog {
    private final long id;
    private final UUID playerUuid;
    private final String ipAddress;
    private final long firstSeen;
    private final long lastSeen;

    public PlayerIPLog(long id, UUID playerUuid, String ipAddress, long firstSeen, long lastSeen) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.ipAddress = ipAddress;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
    }

    public long getId() {
        return id;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public long getFirstSeen() {
        return firstSeen;
    }

    public long getLastSeen() {
        return lastSeen;
    }
}
