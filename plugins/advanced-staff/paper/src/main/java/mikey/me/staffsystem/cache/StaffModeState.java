package mikey.me.staffsystem.cache;

import java.util.UUID;

public class StaffModeState {

    private final UUID staffUuid;
    private final long since;
    private final boolean vanishedBeforeStaffMode;

    public StaffModeState(UUID staffUuid, long since) {
        this(staffUuid, since, false);
    }

    public StaffModeState(UUID staffUuid, long since, boolean vanishedBeforeStaffMode) {
        this.staffUuid = staffUuid;
        this.since = since;
        this.vanishedBeforeStaffMode = vanishedBeforeStaffMode;
    }

    public UUID getStaffUuid() {
        return staffUuid;
    }

    public long getSince() {
        return since;
    }

    public boolean wasVanishedBeforeStaffMode() {
        return vanishedBeforeStaffMode;
    }
}
