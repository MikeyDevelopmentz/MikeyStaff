package mikey.me.staffsystem.database.models;

import java.util.UUID;

public class PlayerNote {

    private final long id;
    private final UUID playerUuid;
    private final UUID staffUuid;
    private final String text;
    private final long createdAt;

    public PlayerNote(long id, UUID playerUuid, UUID staffUuid, String text, long createdAt) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.staffUuid = staffUuid;
        this.text = text;
        this.createdAt = createdAt;
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

    public String getText() {
        return text;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
