package mikey.me.staffsystem.database.models;

import java.util.UUID;

public class LoginLog {
    private final long id;
    private final UUID playerUuid;
    private final String ipAddress;
    private final long loginTime;

    public LoginLog(long id, UUID playerUuid, String ipAddress, long loginTime) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.ipAddress = ipAddress;
        this.loginTime = loginTime;
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

    public long getLoginTime() {
        return loginTime;
    }
}
