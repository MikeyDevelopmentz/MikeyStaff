package mikey.me.staffsystem.database.repositories;

import mikey.me.staffsystem.database.models.PlayerIPLog;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PlayerIPLogRepository {
    CompletableFuture<Void> logIP(UUID playerUuid, String ipAddress, long timestamp);

    CompletableFuture<List<PlayerIPLog>> getIPsByPlayer(UUID playerUuid);

    CompletableFuture<List<UUID>> getAltAccounts(UUID playerUuid);

    CompletableFuture<List<UUID>> getBannedPlayersByIP(String ipAddress, UUID excludeUuid);
}
