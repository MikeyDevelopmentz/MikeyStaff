package mikey.me.staffsystem.database.models;

import java.util.UUID;

public class PunishmentLog {

    private final long id;
    private final UUID playerUuid;
    private final UUID staffUuid;
    private final String type; // BAN, MUTE, KICK
    private final String reason;
    private final long startTime;
    private final Long endTime; // null until an unban/unmute sets it
    private final long durationSeconds; // 0 for permanent or instant
    private final boolean active;

    public PunishmentLog(long id, UUID playerUuid, UUID staffUuid, String type, String reason,
                         long startTime, Long endTime, long durationSeconds, boolean active) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.staffUuid = staffUuid;
        this.type = type;
        this.reason = reason;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationSeconds = durationSeconds;
        this.active = active;
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

    public String getType() {
        return type;
    }

    public String getReason() {
        return reason;
    }

    public long getStartTime() {
        return startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public boolean isActive() {
        return active;
    }
}
