package mikey.me.staffsystem.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.models.FreezeLog;
import mikey.me.staffsystem.database.models.PunishmentLog;
import mikey.me.staffsystem.database.repositories.FreezeLogRepository;
import mikey.me.staffsystem.database.repositories.PunishmentLogRepository;
import mikey.me.staffsystem.messaging.VelocityMessenger;
import mikey.me.staffsystem.utils.SchedulerProvider;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseRecoveryTest {

    private final UUID uuid = UUID.randomUUID();
    private final UUID staffId = UUID.randomUUID();
    private final Player target = mock(Player.class);
    private final Player staff = mock(Player.class);
    private final Queue<Runnable> timers = new ArrayDeque<>();
    private final FreezeLogRepository freezes = mock(FreezeLogRepository.class);
    private final PunishmentLogRepository punishments = mock(PunishmentLogRepository.class);
    private FreezeManager freezeManager;
    private PunishmentManager punishmentManager;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getLogger).thenReturn(Logger.getLogger("recovery-test"));
        when(target.getUniqueId()).thenReturn(uuid);
        when(staff.getUniqueId()).thenReturn(staffId);
        ConfigurationManager config = mock(ConfigurationManager.class);
        when(config.getSettings()).thenReturn(new SettingsConfig(new YamlConfiguration()));
        SchedulerProvider scheduler = mock(SchedulerProvider.class);
        when(scheduler.runSync(any())).thenAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return mock(ScheduledTask.class);
        });
        when(scheduler.runSyncLater(any(), anyLong())).thenAnswer(call -> {
            assertTrue(call.<Long>getArgument(1) > 0L);
            timers.add(call.getArgument(0));
            return mock(ScheduledTask.class);
        });
        when(freezes.findActive()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(freezes.markInactive(anyLong(), anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        AtomicLong ids = new AtomicLong();
        when(freezes.insertAndReturn(any())).thenAnswer(call -> {
            FreezeLog submitted = call.getArgument(0);
            return CompletableFuture.completedFuture(new FreezeLog(ids.incrementAndGet(), uuid, staffId, "",
                    submitted.getStartTime(), null, submitted.getDurationSeconds(), true));
        });
        freezeManager = new FreezeManager(config, scheduler, freezes, mock(TextUtil.class),
                mock(VelocityMessenger.class), null);
        freezeManager.initialize().join();
        punishmentManager = new PunishmentManager(punishments, scheduler, null,
                mock(VelocityMessenger.class), null, null);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void freezeExpiresWhenDatabaseIsHealthy() {
        startFreeze();
        timers.remove().run();
        assertFalse(freezeManager.isFrozen(uuid));
        assertTrue(timers.isEmpty());
    }

    @Test
    void freezeExpiryRetriesUntilDatabaseRecovers() {
        when(freezes.markInactive(anyLong(), anyLong()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db unavailable")))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db still unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));
        startFreeze();
        timers.remove().run();
        assertTrue(freezeManager.isFrozen(uuid));
        assertEquals(1, timers.size());
        timers.remove().run();
        assertTrue(freezeManager.isFrozen(uuid));
        assertEquals(1, timers.size());
        timers.remove().run();
        assertFalse(freezeManager.isFrozen(uuid));
        assertTrue(timers.isEmpty());
    }

    @Test
    void freezeExpiryRetriesSynchronousDatabaseFailure() {
        when(freezes.markInactive(anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("db unavailable"))
                .thenReturn(CompletableFuture.completedFuture(null));
        startFreeze();
        timers.remove().run();
        assertTrue(freezeManager.isFrozen(uuid));
        timers.remove().run();
        assertFalse(freezeManager.isFrozen(uuid));
    }

    @Test
    void oldExpiryRetryLeavesNewFreezeAlone() {
        when(freezes.markInactive(anyLong(), anyLong()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));
        startFreeze();
        timers.remove().run();
        Runnable oldRetry = timers.remove();
        assertTrue(freezeManager.unfreeze(uuid).join());
        assertTrue(freezeManager.freeze(staff, target, 0L, "new freeze").join());
        oldRetry.run();
        assertTrue(freezeManager.isFrozen(uuid));
        verify(freezes, times(2)).markInactive(eq(1L), anyLong());
        verify(freezes, never()).markInactive(eq(2L), anyLong());
    }

    @Test
    void expiryRetriesAfterPendingUnfreezeFails() {
        CompletableFuture<Void> unfreeze = new CompletableFuture<>();
        when(freezes.markInactive(anyLong(), anyLong()))
                .thenReturn(unfreeze).thenReturn(CompletableFuture.completedFuture(null));
        startFreeze();
        freezeManager.unfreeze(uuid);
        timers.remove().run();
        verify(freezes, times(1)).markInactive(anyLong(), anyLong());
        unfreeze.completeExceptionally(new IllegalStateException("db unavailable"));
        timers.remove().run();
        assertFalse(freezeManager.isFrozen(uuid));
    }

    @Test
    void lateExpiryFailureDoesNotRetryAfterShutdown() {
        CompletableFuture<Void> expiry = new CompletableFuture<>();
        when(freezes.markInactive(anyLong(), anyLong())).thenReturn(expiry);
        startFreeze();
        timers.remove().run();
        freezeManager.shutdown();
        expiry.completeExceptionally(new IllegalStateException("db unavailable"));
        assertTrue(timers.isEmpty());
    }

    @Test
    void unmutedPlayerCanChatAfterHealthyLookup() {
        when(punishments.findActiveByPlayerAndTypeAll(uuid, "MUTE"))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        punishmentManager.loadMute(target);
        assertFalse(punishmentManager.isMutedCached(uuid));
        assertTrue(timers.isEmpty());
    }

    @Test
    void muteLookupRetriesUntilDatabaseRecovers() {
        when(punishments.findActiveByPlayerAndTypeAll(uuid, "MUTE"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db unavailable")))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db still unavailable")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        punishmentManager.loadMute(target);
        assertTrue(punishmentManager.isMutedCached(uuid));
        timers.remove().run();
        assertTrue(punishmentManager.isMutedCached(uuid));
        timers.remove().run();
        assertFalse(punishmentManager.isMutedCached(uuid));
        assertTrue(timers.isEmpty());
    }

    @Test
    void muteLookupRetriesSynchronousDatabaseFailure() {
        when(punishments.findActiveByPlayerAndTypeAll(uuid, "MUTE"))
                .thenThrow(new IllegalStateException("db unavailable"))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        punishmentManager.loadMute(target);
        assertTrue(punishmentManager.isMutedCached(uuid));
        timers.remove().run();
        assertFalse(punishmentManager.isMutedCached(uuid));
    }

    @Test
    void oldMuteRetryDoesNotReplaceNewerResult() {
        PunishmentLog mute = new PunishmentLog(1L, uuid, staffId, "MUTE", "", System.currentTimeMillis(), null, 0L, true);
        when(punishments.findActiveByPlayerAndTypeAll(uuid, "MUTE"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db unavailable")))
                .thenReturn(CompletableFuture.completedFuture(List.of(mute)))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        punishmentManager.loadMute(target);
        punishmentManager.loadMute(target);
        timers.remove().run();
        assertTrue(punishmentManager.hasActiveMuteCached(uuid));
        verify(punishments, times(2)).findActiveByPlayerAndTypeAll(uuid, "MUTE");
    }

    @Test
    void muteRetryWaitsForPendingUnmute() {
        when(punishments.findActiveByPlayerAndTypeAll(uuid, "MUTE"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db unavailable")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        CompletableFuture<Boolean> unmute = new CompletableFuture<>();
        when(punishments.deactivateActiveByPlayerAndType(eq(uuid), eq("MUTE"), anyLong())).thenReturn(unmute);
        punishmentManager.loadMute(target);
        punishmentManager.unmute(staff, target);
        timers.remove().run();
        verify(punishments, times(1)).findActiveByPlayerAndTypeAll(uuid, "MUTE");
        unmute.completeExceptionally(new IllegalStateException("db unavailable"));
        timers.remove().run();
        assertFalse(punishmentManager.isMutedCached(uuid));
    }

    @Test
    void muteRetryStopsAfterShutdown() {
        when(punishments.findActiveByPlayerAndTypeAll(uuid, "MUTE"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db unavailable")));
        punishmentManager.loadMute(target);
        punishmentManager.shutdown();
        timers.remove().run();
        assertTrue(timers.isEmpty());
        verify(punishments, times(1)).findActiveByPlayerAndTypeAll(uuid, "MUTE");
    }

    private void startFreeze() {
        assertTrue(freezeManager.freeze(staff, target, 60L, "").join());
        assertEquals(1, timers.size());
    }
}
