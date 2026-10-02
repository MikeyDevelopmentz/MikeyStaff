package mikey.me.staffsystem.listeners;

import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.FreezeManager;
import mikey.me.staffsystem.managers.InspectManager;
import mikey.me.staffsystem.managers.PunishmentManager;
import mikey.me.staffsystem.managers.ReportsGuiManager;
import mikey.me.staffsystem.managers.StaffChatManager;
import mikey.me.staffsystem.managers.StaffModeManager;
import mikey.me.staffsystem.managers.VanishManager;
import mikey.me.staffsystem.cache.StaffModeState;
import mikey.me.staffsystem.messaging.VelocityMessenger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class JoinQuitListener implements Listener {

    private final VanishManager vanishManager;
    private final FreezeManager freezeManager;
    private final SettingsConfig settings;
    private final PunishmentManager punishmentManager;
    private final StaffModeManager staffModeManager;
    private final VelocityMessenger velocityMessenger;
    private final InspectManager inspectManager;
    private final ReportsGuiManager reportsGuiManager;
    private final StaffChatManager staffChatManager;

    public JoinQuitListener(VanishManager vanishManager, FreezeManager freezeManager,
            SettingsConfig settings, PunishmentManager punishmentManager,
            StaffModeManager staffModeManager, VelocityMessenger velocityMessenger,
            InspectManager inspectManager, ReportsGuiManager reportsGuiManager,
            StaffChatManager staffChatManager) {
        this.vanishManager = vanishManager;
        this.freezeManager = freezeManager;
        this.settings = settings;
        this.punishmentManager = punishmentManager;
        this.staffModeManager = staffModeManager;
        this.velocityMessenger = velocityMessenger;
        this.inspectManager = inspectManager;
        this.reportsGuiManager = reportsGuiManager;
        this.staffChatManager = staffChatManager;
    }

    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!freezeManager.isAvailable()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Can't check freezes right now, try again in a sec.");
            return;
        }
        punishmentManager.handlePreLogin(event);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        staffModeManager.recover(event.getPlayer());
        vanishManager.applyVisibilityForJoin(event.getPlayer());
        if (vanishManager.isVanished(event.getPlayer().getUniqueId()) && settings.isVanishSilentJoinLeave()) {
            event.setJoinMessage(null);
        }
        punishmentManager.loadMute(event.getPlayer());
        freezeManager.reapplyOnJoin(event.getPlayer());
        velocityMessenger.flush();
        velocityMessenger.sendHello();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (vanishManager.isVanished(event.getPlayer().getUniqueId()) && settings.isVanishSilentJoinLeave()) {
            event.setQuitMessage(null);
        }
        StaffModeState staffModeState = staffModeManager.getState(event.getPlayer().getUniqueId());
        boolean disabled = staffModeManager.disable(event.getPlayer());
        if (disabled
                && settings.isStaffModeAutoVanish()
                && staffModeState != null
                && !staffModeState.wasVanishedBeforeStaffMode()) {
            vanishManager.setVanished(event.getPlayer(), event.getPlayer(), false);
        }
        freezeManager.handleLogout(event.getPlayer());

        // drop per player state so the maps dont grow forever
        java.util.UUID quittingId = event.getPlayer().getUniqueId();
        if (inspectManager != null) {
            inspectManager.handleQuit(quittingId);
        }
        if (reportsGuiManager != null) {
            reportsGuiManager.handleQuit(quittingId);
        }
        if (staffChatManager != null) {
            staffChatManager.handleQuit(quittingId);
        }
    }

    // blaze rod chat would out vanished staff
    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        if (staffModeManager.isInStaffMode(uuid) || vanishManager.isVanished(uuid)) {
            event.message(null);
        }
    }
}
