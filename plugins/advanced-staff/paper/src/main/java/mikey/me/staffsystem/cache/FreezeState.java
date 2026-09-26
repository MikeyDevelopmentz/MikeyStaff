package mikey.me.staffsystem.cache;

import java.util.UUID;

public class FreezeState {

    private final UUID playerUuid;
    private final UUID staffUuid;
    private final String reason;
    private final long startTime;
    private final long durationSeconds;

    public FreezeState(UUID playerUuid, UUID staffUuid, String reason, long startTime, long durationSeconds) {
        this.playerUuid = playerUuid;
        this.staffUuid = staffUuid;
        this.reason = reason;
        this.startTime = startTime;
        this.durationSeconds = durationSeconds;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public UUID getStaffUuid() {
        return staffUuid;
    }

    public String getReason() {
        return reason;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }
}
