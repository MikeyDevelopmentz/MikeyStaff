package mikey.me.staffsystem.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.models.FreezeLog;
import mikey.me.staffsystem.database.repositories.FreezeLogRepository;
import mikey.me.staffsystem.messaging.VelocityMessenger;
import mikey.me.staffsystem.utils.SchedulerProvider;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FreezeSyncTest {

    @Test
    void echoedFreezeKeepsItsExpiry() {
        checkEcho(false);
    }

    @Test
    void databaseResolvedEchoKeepsItsExpiry() {
        checkEcho(true);
    }

    private void checkEcho(boolean resolveFromDatabase) {
        ConfigurationManager config = mock(ConfigurationManager.class);
        when(config.getSettings()).thenReturn(new SettingsConfig(new YamlConfiguration()));
        SchedulerProvider scheduler = mock(SchedulerProvider.class);
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return mock(ScheduledTask.class);
        }).when(scheduler).runSync(any());
        List<Runnable> expiries = new ArrayList<>();
        ScheduledTask timer = mock(ScheduledTask.class);
        when(scheduler.runSyncLater(any(), anyLong())).thenAnswer(call -> {
            expiries.add(call.getArgument(0));
            return timer;
        });
        FreezeLogRepository repository = mock(FreezeLogRepository.class);
        when(repository.findActive()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(repository.markInactive(anyLong(), anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        UUID uuid = UUID.randomUUID();
        UUID staffUuid = UUID.randomUUID();
        Player target = mock(Player.class);
        Player staff = mock(Player.class);
        when(target.getUniqueId()).thenReturn(uuid);
        when(staff.getUniqueId()).thenReturn(staffUuid);
        FreezeLog saved = new FreezeLog(5L, uuid, staffUuid, "", System.currentTimeMillis(), null, 60L, true);
        when(repository.insertAndReturn(any())).thenReturn(CompletableFuture.completedFuture(saved));
        when(repository.findActiveByPlayer(uuid)).thenReturn(CompletableFuture.completedFuture(Optional.of(saved)));
        VelocityMessenger messenger = mock(VelocityMessenger.class);

        try (MockedStatic<Bukkit> ignored = mockStatic(Bukkit.class)) {
            FreezeManager manager = new FreezeManager(config, scheduler, repository, mock(TextUtil.class), messenger, null);
            manager.initialize().join();
            assertTrue(manager.freeze(staff, target, 60L, "").join());

            ArgumentCaptor<Consumer<String>> handler = ArgumentCaptor.forClass(Consumer.class);
            verify(messenger).onFreezeSync(handler.capture());
            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(messenger).send(eq(VelocityMessenger.CH_FREEZE), payload.capture(), eq(uuid));
            String echo = resolveFromDatabase ? "{\"target_uuid\":\"" + uuid + "\",\"frozen\":true}" : payload.getValue();
            handler.getValue().accept(echo);
            handler.getValue().accept(echo);

            assertEquals(1, expiries.size());
            verify(timer, never()).cancel();
            expiries.getFirst().run();
            assertFalse(manager.isFrozen(uuid));
            verify(repository).markInactive(eq(5L), anyLong());
        }
    }
}
