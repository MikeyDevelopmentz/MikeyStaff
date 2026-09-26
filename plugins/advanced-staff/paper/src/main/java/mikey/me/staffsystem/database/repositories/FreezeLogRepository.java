package mikey.me.staffsystem.database.repositories;

import mikey.me.staffsystem.database.models.FreezeLog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface FreezeLogRepository {

    CompletableFuture<Void> insert(FreezeLog log);

    default CompletableFuture<FreezeLog> insertAndReturn(FreezeLog log) {
        return insert(log).thenApply(ignored -> log);
    }

    CompletableFuture<Optional<FreezeLog>> findActiveByPlayer(UUID playerUuid);

    CompletableFuture<List<FreezeLog>> findActive();

    CompletableFuture<List<FreezeLog>> findByPlayer(UUID playerUuid);

    CompletableFuture<Void> markInactive(long id, long endTime);
}
