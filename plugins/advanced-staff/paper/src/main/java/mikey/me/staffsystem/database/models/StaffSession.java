package mikey.me.staffsystem.database.models;

import java.util.UUID;

public class StaffSession {

    private final long id;
    private final UUID staffUuid;
    private final long startTime;
    private final Long endTime;
    private final String serializedInventory;

    public StaffSession(long id, UUID staffUuid, long startTime, Long endTime, String serializedInventory) {
        this.id = id;
        this.staffUuid = staffUuid;
        this.startTime = startTime;
        this.endTime = endTime;
        this.serializedInventory = serializedInventory;
    }

    public long getId() {
        return id;
    }

    public UUID getStaffUuid() {
        return staffUuid;
    }

    public long getStartTime() {
        return startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public String getSerializedInventory() {
        return serializedInventory;
    }
}
