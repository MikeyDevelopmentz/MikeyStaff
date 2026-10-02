package mikey.me.staffsystem.commands;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.PunishmentManager;
import mikey.me.staffsystem.utils.NetworkPlayerResolver;
import mikey.me.staffsystem.utils.SchedulerProvider;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnbanLookupTest {
    private final Player staff = mock(Player.class);
    private final OfflinePlayer target = mock(OfflinePlayer.class);
    private final UUID staffId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final PunishmentManager punishments = mock(PunishmentManager.class);
    private final NetworkPlayerResolver resolver = mock(NetworkPlayerResolver.class);
    private final CompletableFuture<UUID> lookup = new CompletableFuture<>();
    private UnbanCommand command;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(staffId)).thenReturn(staff);
        when(staff.getUniqueId()).thenReturn(staffId);
        when(staff.getName()).thenReturn("Staff");
        when(staff.isOnline()).thenReturn(true);
        when(staff.hasPermission(anyString())).thenReturn(true);
        when(resolver.resolvePlayerId("Target")).thenReturn(lookup);
        when(resolver.getOfflinePlayer(targetId)).thenReturn(target);
        when(target.getName()).thenReturn("Target");
        when(punishments.unban(staff, target)).thenReturn(CompletableFuture.completedFuture(true));
        SchedulerProvider scheduler = mock(SchedulerProvider.class);
        when(scheduler.runSync(any())).thenAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return mock(ScheduledTask.class);
        });
        ConfigurationManager config = mock(ConfigurationManager.class);
        when(config.getSettings()).thenReturn(new SettingsConfig(new YamlConfiguration()));
        TextUtil text = mock(TextUtil.class);
        when(text.prefixed(anyString())).thenAnswer(call -> call.getArgument(0));
        when(text.format(anyString(), anyMap())).thenAnswer(call -> call.getArgument(0));
        command = new UnbanCommand(config, punishments, text, resolver, scheduler);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private void unban() {
        command.onCommand(staff, null, "unban", new String[]{"Target"});
    }

    @Test
    void waitsForLookupThenUnbansResolvedTarget() {
        unban();
        verifyNoInteractions(punishments);
        lookup.complete(targetId);
        verify(punishments).unban(staff, target);
        verify(staff).sendMessage("punishments.unban-issued");
    }

    @Test
    void failedLookupDoesNotUnban() {
        unban();
        lookup.completeExceptionally(new IllegalStateException("offline"));
        verifyNoInteractions(punishments);
        verify(staff).sendMessage("errors.database-error");
    }

    @Test
    void revokedPermissionStopsPendingUnban() {
        unban();
        when(staff.hasPermission(anyString())).thenReturn(false);
        lookup.complete(targetId);
        verifyNoInteractions(punishments);
        verify(staff).sendMessage("errors.no-permission");
    }

    @Test
    void disconnectedStaffDoesNotUnbanAfterLookup() {
        unban();
        when(staff.isOnline()).thenReturn(false);
        lookup.complete(targetId);
        verifyNoInteractions(punishments);
    }
}
