package mikey.me.staffsystem.commands;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.models.PlayerReport;
import mikey.me.staffsystem.managers.ReportManager;
import mikey.me.staffsystem.utils.NetworkPlayerResolver;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportLookupTest {
    private final Player reporter = mock(Player.class);
    private final UUID reporterId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final NetworkPlayerResolver resolver = mock(NetworkPlayerResolver.class);
    private final ReportManager reports = mock(ReportManager.class);
    private final CompletableFuture<UUID> lookup = new CompletableFuture<>();
    private ReportCommand command;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
        doAnswer(call -> { call.<Runnable>getArgument(1).run(); return null; })
                .when(scheduler).execute(any(), any());
        when(reporter.getUniqueId()).thenReturn(reporterId);
        when(reporter.isOnline()).thenReturn(true);
        when(reporter.hasPermission(anyString())).thenReturn(true);
        bukkit.when(() -> Bukkit.getPlayer(reporterId)).thenReturn(reporter);
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("Target");
        when(resolver.resolvePlayerId("Target")).thenReturn(lookup);
        when(resolver.getOfflinePlayer(targetId)).thenReturn(target);
        when(reports.createReport(eq(reporterId), eq(targetId), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(PlayerReport.class)));
        ConfigurationManager config = mock(ConfigurationManager.class);
        when(config.getSettings()).thenReturn(new SettingsConfig(new YamlConfiguration()));
        TextUtil text = mock(TextUtil.class);
        when(text.prefixed(anyString())).thenAnswer(call -> call.getArgument(0));
        when(text.format(anyString(), anyMap())).thenAnswer(call -> call.getArgument(0));
        command = new ReportCommand(config, reports, text, mock(Plugin.class), resolver);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private void report() {
        command.onCommand(reporter, null, "report", new String[]{"Target", "reason"});
    }

    @Test
    void oneLookupAtATimeThenCooldownBeforeAnotherLookup() {
        report();
        report();
        verify(resolver, times(1)).resolvePlayerId("Target");
        verifyNoInteractions(reports);
        lookup.complete(targetId);
        verify(reports).createReport(reporterId, targetId, "reason");
        report();
        verify(resolver, times(1)).resolvePlayerId("Target");
        verify(reporter).sendMessage("reports.cooldown");
    }

    @Test
    void failedLookupAllowsAnotherAttempt() {
        report();
        lookup.completeExceptionally(new IllegalStateException("db unavailable"));
        verify(reporter).sendMessage("errors.database-error");
        when(resolver.resolvePlayerId("Target")).thenReturn(CompletableFuture.completedFuture(targetId));
        report();
        verify(reports).createReport(reporterId, targetId, "reason");
    }

    @Test
    void disconnectedReporterDoesNotSubmitLateResult() {
        report();
        when(reporter.isOnline()).thenReturn(false);
        lookup.complete(targetId);
        verifyNoInteractions(reports);
    }

    @Test
    void revokedPermissionStopsPendingReport() {
        report();
        when(reporter.hasPermission(anyString())).thenReturn(false);
        lookup.complete(targetId);
        verifyNoInteractions(reports);
        verify(reporter).sendMessage("errors.no-permission");
    }
}
