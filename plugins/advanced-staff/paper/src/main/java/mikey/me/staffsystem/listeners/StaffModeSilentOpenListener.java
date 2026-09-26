
package mikey.me.staffsystem.listeners;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import mikey.me.staffsystem.inventory.StaffMenuHolder;
import mikey.me.staffsystem.managers.StaffModeManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StaffModeSilentOpenListener implements Listener {

    private final StaffModeManager staffModeManager;
    private final Plugin plugin;
    private final Map<UUID, SilentView> openViews;

    public StaffModeSilentOpenListener(StaffModeManager staffModeManager, Plugin plugin) {
        this.staffModeManager = staffModeManager;
        this.plugin = plugin;
        this.openViews = new ConcurrentHashMap<>();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // another listener already claimed this click, dont fight it
        if (event.useInteractedBlock() == Event.Result.DENY
                || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!staffModeManager.isInStaffMode(uuid)) {
            return;
        }
        Material type = block.getType();
        if (type == Material.ENDER_CHEST) {
            event.setCancelled(true);
            Inventory real = player.getEnderChest();
            Inventory fake = StaffMenuHolder.create(real.getSize(), "Silent Ender Chest");
            fake.setContents(real.getContents());
            stopExistingView(uuid, new SilentView(fake, real, plugin, player));
            player.openInventory(fake);
            return;
        }
        if (!(block.getState() instanceof Container)) {
            return;
        }
        Container container = (Container) block.getState();
        Inventory real = container.getInventory();
        Inventory fake = StaffMenuHolder.create(real.getSize(), "Silent Chest");
        fake.setContents(real.getContents());
        event.setCancelled(true);
        stopExistingView(uuid, new SilentView(fake, real, plugin, player));
        player.openInventory(fake);
    }

    private void stopExistingView(UUID uuid, SilentView fresh) {
        SilentView old = openViews.put(uuid, fresh);
        if (old != null) {
            old.stop();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        SilentView view = openViews.remove(event.getPlayer().getUniqueId());
        if (view != null) {
            view.stop();
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        UUID uuid = player.getUniqueId();
        SilentView view = openViews.get(uuid);
        if (view == null) {
            return;
        }

        if (event.getClickedInventory() == null) {
            return;
        }

        Inventory top = event.getView().getTopInventory();
        if (top == null || !top.equals(view.fake)) {
            return;
        }

        if (event.getClickedInventory().equals(top)) {
            event.setCancelled(true);
            return;
        }

        // double click collect pulls stacks out of the fake chest = dupe
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        if (event.getAction().name().contains("MOVE_TO_OTHER_INVENTORY")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        UUID uuid = player.getUniqueId();
        SilentView view = openViews.get(uuid);
        if (view == null) {
            return;
        }

        Inventory top = event.getView().getTopInventory();
        if (top == null || !top.equals(view.fake)) {
            return;
        }

        // dont let drags paint into the top inv
        for (int slot : event.getRawSlots()) {
            if (slot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        UUID uuid = player.getUniqueId();
        SilentView view = openViews.get(uuid);
        if (view == null) {
            return;
        }
        if (!event.getInventory().equals(view.fake)) {
            return;
        }
        view.stop();
        openViews.remove(uuid);
    }

    private static final class SilentView {
        private final Inventory fake;
        private final Inventory real;
        private final ScheduledTask task;

        private SilentView(Inventory fake, Inventory real, Plugin plugin, Player viewer) {
            this.fake = fake;
            this.real = real;
            this.task = viewer.getScheduler().runAtFixedRate(plugin, scheduled -> {
                fake.setContents(real.getContents());
            }, null, 1L, 1L);
        }

        private void stop() {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
    }
}
