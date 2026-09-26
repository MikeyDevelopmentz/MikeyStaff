package mikey.me.staffsystem.listeners;

import mikey.me.staffsystem.managers.FreezeManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.UUID;

public class FreezeDamageListener implements Listener {

    private final FreezeManager freezeManager;

    public FreezeDamageListener(FreezeManager freezeManager) {
        this.freezeManager = freezeManager;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        UUID uuid = player.getUniqueId();
        if (freezeManager.isFrozen(uuid)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player damager = (Player) event.getDamager();
            if (freezeManager.isFrozen(damager.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            if (freezeManager.isFrozen(victim.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }
}
