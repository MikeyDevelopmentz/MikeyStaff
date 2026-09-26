package mikey.me.staffsystem.database.repositories;

import mikey.me.staffsystem.database.models.LoginLog;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface LoginLogRepository {
    void logLogin(UUID playerUuid, String ipAddress, long loginTime);

    CompletableFuture<List<LoginLog>> getLoginLogs(UUID playerUuid);
}
