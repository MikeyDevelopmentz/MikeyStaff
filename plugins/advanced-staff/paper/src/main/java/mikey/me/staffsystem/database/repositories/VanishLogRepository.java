package mikey.me.staffsystem.database.repositories;

import mikey.me.staffsystem.database.models.VanishLog;

import java.util.concurrent.CompletableFuture;

public interface VanishLogRepository {

    CompletableFuture<Void> insert(VanishLog log);
}
