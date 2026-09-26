package mikey.me.staffsystem.database.repositories;

import mikey.me.staffsystem.database.models.StaffSession;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface StaffSessionRepository {

    CompletableFuture<StaffSession> startSession(UUID staffUuid, String serializedInventory, long startTime);

    CompletableFuture<Void> endSession(long id, long endTime);

    CompletableFuture<Optional<StaffSession>> findLatestByStaff(UUID staffUuid);

    default CompletableFuture<Optional<StaffSession>> findOpenByStaff(UUID staffUuid) {
        return findLatestByStaff(staffUuid)
                .thenApply(session -> session.filter(value -> value.getEndTime() == null));
    }
}
