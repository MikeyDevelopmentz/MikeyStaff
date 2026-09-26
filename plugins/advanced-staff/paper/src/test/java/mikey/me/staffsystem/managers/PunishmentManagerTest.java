package mikey.me.staffsystem.managers;

import mikey.me.staffsystem.database.models.PunishmentLog;
import mikey.me.staffsystem.database.repositories.PunishmentLogRepository;
import mikey.me.staffsystem.messaging.VelocityMessenger;
import mikey.me.staffsystem.utils.SchedulerProvider;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PunishmentManagerTest {

    private final UUID uuid = UUID.randomUUID();
    private final PunishmentLogRepository repository = mock(PunishmentLogRepository.class);
    private final Player player = mock(Player.class);
    private final AtomicLong start = new AtomicLong(System.currentTimeMillis());
    private PunishmentManager manager;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(uuid);
        manager = new PunishmentManager(repository, mock(SchedulerProvider.class), null,
                mock(VelocityMessenger.class), null, null);
    }

    private void loadMute(long duration) {
        PunishmentLog log = mock(PunishmentLog.class);
        when(log.isActive()).thenReturn(true);
        when(log.getStartTime()).thenAnswer(ignored -> start.get());
        when(log.getDurationSeconds()).thenReturn(duration);
        when(repository.findActiveByPlayerAndTypeAll(uuid, "MUTE"))
                .thenReturn(CompletableFuture.completedFuture(List.of(log)));
        manager.loadMute(player);
    }

    @Test
    void temporaryMuteExpiresWithoutRejoining() {
        loadMute(60L);
        assertTrue(manager.isMutedCached(uuid));
        start.addAndGet(-120_000L);
        assertFalse(manager.isMutedCached(uuid));
        assertFalse(manager.isMutedCached(uuid));
    }

    @Test
    void checkingActiveMuteDoesNotLoseTheExpiredResult() {
        loadMute(60L);
        start.addAndGet(-120_000L);
        assertFalse(manager.hasActiveMuteCached(uuid));
        assertFalse(manager.isMutedCached(uuid));
    }

    @Test
    void permanentMuteDoesNotExpire() {
        loadMute(0L);
        start.set(1L);
        assertTrue(manager.isMutedCached(uuid));
    }

    @Test
    void unknownAndRefreshingPlayersStayBlocked() {
        assertTrue(manager.isMutedCached(uuid));
        loadMute(60L);
        start.addAndGet(-120_000L);
        assertFalse(manager.isMutedCached(uuid));

        CompletableFuture<List<PunishmentLog>> refresh = new CompletableFuture<>();
        when(repository.findActiveByPlayerAndTypeAll(uuid, "MUTE")).thenReturn(refresh);
        manager.loadMute(player);
        assertTrue(manager.isMutedCached(uuid));
        refresh.complete(List.of());
        assertFalse(manager.isMutedCached(uuid));
    }
}
