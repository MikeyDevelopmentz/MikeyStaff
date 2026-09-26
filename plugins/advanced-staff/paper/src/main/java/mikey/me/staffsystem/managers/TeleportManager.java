package mikey.me.staffsystem.managers;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

public class TeleportManager {

    private final SettingsConfig settings;

    public TeleportManager(ConfigurationManager configurationManager) {
        this.settings = configurationManager.getSettings();
    }

    public boolean teleportTo(Player sender, Player target) {
        if (!canTeleport(sender, target)) {
            return false;
        }
        return teleport(sender, target.getLocation());
    }

    // folia only allows async teleports, on paper a loaded chunk finishes right away
    private boolean teleport(Player moving, Location destination) {
        CompletableFuture<Boolean> result = moving.teleportAsync(destination);
        return !result.isDone() || Boolean.TRUE.equals(result.getNow(false));
    }

    private boolean canTeleport(Player moving, Player destination) {
        if (moving == null || destination == null) {
            return false;
        }
        if (!settings.isTeleportSafetyChecks()) {
            return true;
        }
        return moving.isOnline() && destination.isOnline()
                && destination.getLocation() != null && destination.getWorld() != null;
    }

    public void shutdown() {
    }
}
