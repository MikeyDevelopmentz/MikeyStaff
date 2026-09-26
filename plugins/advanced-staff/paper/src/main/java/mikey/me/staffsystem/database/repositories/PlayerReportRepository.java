package mikey.me.staffsystem.database.repositories;

import mikey.me.staffsystem.database.models.PlayerReport;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PlayerReportRepository {

    CompletableFuture<PlayerReport> insert(PlayerReport report);

    CompletableFuture<PlayerReport> findById(long id);

    CompletableFuture<List<PlayerReport>> findByReported(UUID reportedUuid);

    CompletableFuture<List<PlayerReport>> findAll();

    CompletableFuture<Void> markSolved(long id, UUID staffUuid, long solvedAt);

    CompletableFuture<Void> deleteByReporter(UUID reporterUuid);

    CompletableFuture<List<UUID>> findDistinctReporters();
}
