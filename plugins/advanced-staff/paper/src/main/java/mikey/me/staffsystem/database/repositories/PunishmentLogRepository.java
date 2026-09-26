package mikey.me.staffsystem.database.repositories;

import mikey.me.staffsystem.database.models.PunishmentLog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PunishmentLogRepository {

    CompletableFuture<Void> insert(PunishmentLog log);

    CompletableFuture<Boolean> insertIfNoActive(PunishmentLog log);

    CompletableFuture<Optional<PunishmentLog>> findActiveByPlayerAndType(UUID playerUuid, String type);

    CompletableFuture<List<PunishmentLog>> findActiveByPlayerAndTypeAll(UUID playerUuid, String type);

    CompletableFuture<List<PunishmentLog>> findByPlayer(UUID playerUuid);

    CompletableFuture<Boolean> deactivateActiveByPlayerAndType(UUID playerUuid, String type, long endTimeMillis);
}
