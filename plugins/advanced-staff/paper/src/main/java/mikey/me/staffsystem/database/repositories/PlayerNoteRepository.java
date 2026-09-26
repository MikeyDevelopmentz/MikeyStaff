package mikey.me.staffsystem.database.repositories;

import mikey.me.staffsystem.database.models.PlayerNote;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PlayerNoteRepository {

    CompletableFuture<PlayerNote> insert(PlayerNote note);

    CompletableFuture<Boolean> deleteById(long id);

    CompletableFuture<Optional<PlayerNote>> findById(long id);

    CompletableFuture<List<PlayerNote>> findByPlayer(UUID playerUuid);
}
