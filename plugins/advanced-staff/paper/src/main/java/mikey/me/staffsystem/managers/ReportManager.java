package mikey.me.staffsystem.managers;

import mikey.me.staffsystem.database.models.PlayerReport;
import mikey.me.staffsystem.database.repositories.PlayerReportRepository;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class ReportManager {

    private final PlayerReportRepository repository;
    private final AtomicLong cacheGeneration = new AtomicLong();
    private volatile Set<UUID> cachedReporters = Collections.emptySet();

    public ReportManager(PlayerReportRepository repository) {
        this.repository = repository;
        refreshReporters();
    }

    public CompletableFuture<PlayerReport> createReport(Player reporter, OfflinePlayer target, String reason) {
        return createReport(reporter.getUniqueId(), target.getUniqueId(), reason);
    }

    public CompletableFuture<PlayerReport> createReport(UUID reporterId, UUID reportedId, String reason) {
        long now = System.currentTimeMillis();
        PlayerReport report = new PlayerReport(0, reportedId, reporterId, reason, now, false, null, 0L);
        return repository.insert(report).thenApply(inserted -> {
            cacheReporter(reporterId);
            return inserted;
        });
    }

    public CompletableFuture<PlayerReport> getReport(long id) {
        return repository.findById(id);
    }

    public CompletableFuture<List<PlayerReport>> getReports(UUID reported) {
        return repository.findByReported(reported);
    }

    public CompletableFuture<List<PlayerReport>> getAllReports() {
        return repository.findAll();
    }

    public CompletableFuture<Void> solveReport(long id, Player staff) {
        return repository.markSolved(id, staff.getUniqueId(), System.currentTimeMillis());
    }

    public CompletableFuture<Void> clearReportsByReporter(UUID reporterUuid) {
        return repository.deleteByReporter(reporterUuid)
            .thenCompose(ignored -> refreshReporters())
            .thenApply(ignored -> (Void) null)
            .whenComplete((ignored, error) -> {
                if (error != null) clearCache();
            });
    }

    public CompletableFuture<List<UUID>> getReporters() {
        return refreshReporters();
    }

    private CompletableFuture<List<UUID>> refreshReporters() {
        long generation = cacheGeneration.incrementAndGet();
        return repository.findDistinctReporters().thenApply(list -> {
            synchronized (this) {
                if (generation == cacheGeneration.get()) {
                    cachedReporters = Collections.unmodifiableSet(new HashSet<>(list));
                }
            }
            return list;
        });
    }

    private synchronized void cacheReporter(UUID reporterId) {
        cacheGeneration.incrementAndGet();
        Set<UUID> updated = new HashSet<>(cachedReporters);
        updated.add(reporterId);
        cachedReporters = Collections.unmodifiableSet(updated);
    }

    private synchronized void clearCache() {
        cacheGeneration.incrementAndGet();
        cachedReporters = Collections.emptySet();
    }

    // for tab completion, dont block the main thread on a db query
    public List<UUID> getCachedReporters() {
        return new ArrayList<>(cachedReporters);
    }
}
