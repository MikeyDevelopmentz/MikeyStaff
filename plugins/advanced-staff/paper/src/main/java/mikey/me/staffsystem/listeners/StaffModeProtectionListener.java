package mikey.me.staffsystem.listeners;

import io.papermc.paper.event.player.PlayerPickBlockEvent;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import mikey.me.staffsystem.inventory.StaffMenuHolder;
import mikey.me.staffsystem.managers.StaffModeManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.Locale;
import java.util.UUID;

public class StaffModeProtectionListener implements Listener {

    private final StaffModeManager staffModeManager;

    public StaffModeProtectionListener(StaffModeManager staffModeManager) {
        this.staffModeManager = staffModeManager;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (shouldBlockItemAction(isLocked(uuid), staffModeManager.isInStaffMode(uuid))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        boolean locked = isLocked(uuid);
        boolean staffMode = staffModeManager.isInStaffMode(uuid);
        if (event.isCancelled() && !locked) {
            return;
        }
        boolean topInventory = event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getTopInventory());
        boolean staffMenu = isStaffMenu(event.getView().getTopInventory().getHolder());
        boolean shiftClick = event.isShiftClick();
        boolean numberKey = event.getClick() == ClickType.NUMBER_KEY;
        boolean cursorAction = event.getClick() == ClickType.DOUBLE_CLICK
                || event.getClick() == ClickType.DROP
                || event.getClick() == ClickType.CONTROL_DROP
                || event.getClick() == ClickType.MIDDLE
                || event.getClick() == ClickType.CREATIVE
                || event.getClick() == ClickType.SWAP_OFFHAND;
        if (shouldBlockInventoryClick(locked, staffMode, topInventory, staffMenu, shiftClick, numberKey, cursorAction)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        boolean locked = isLocked(uuid);
        boolean staffMode = staffModeManager.isInStaffMode(uuid);
        int topSize = event.getView().getTopInventory().getSize();
        boolean staffMenu = isStaffMenu(event.getView().getTopInventory().getHolder());
        boolean topInventoryOnly = true;
        for (int slot : event.getRawSlots()) {
            if (slot >= topSize) {
                topInventoryOnly = false;
                break;
            }
        }
        if (shouldBlockInventoryDrag(locked, staffMode, topInventoryOnly, staffMenu)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isLocked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (shouldBlockItemAction(isLocked(event.getPlayer().getUniqueId()),
                staffModeManager.isInStaffMode(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        if (shouldBlockItemAction(isLocked(event.getPlayer().getUniqueId()),
                staffModeManager.isInStaffMode(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        if (shouldBlockItemAction(isLocked(event.getPlayer().getUniqueId()),
                staffModeManager.isInStaffMode(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (shouldBlockItemAction(isLocked(event.getPlayer().getUniqueId()),
                staffModeManager.isInStaffMode(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (isLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (shouldBlockItemAction(isLocked(event.getPlayer().getUniqueId()),
                staffModeManager.isInStaffMode(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (shouldBlockItemAction(isLocked(event.getPlayer().getUniqueId()),
                staffModeManager.isInStaffMode(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (shouldBlockItemAction(isLocked(event.getPlayer().getUniqueId()),
                staffModeManager.isInStaffMode(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (shouldBlockItemAction(isLocked(event.getPlayer().getUniqueId()),
                staffModeManager.isInStaffMode(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (shouldBlockItemAction(isLocked(event.getPlayer().getUniqueId()),
                staffModeManager.isInStaffMode(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickBlock(PlayerPickBlockEvent event) {
        if (shouldBlockItemAction(isLocked(event.getPlayer().getUniqueId()),
                staffModeManager.isInStaffMode(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickItem(PlayerPickItemEvent event) {
        if (shouldBlockItemAction(isLocked(event.getPlayer().getUniqueId()),
                staffModeManager.isInStaffMode(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        if (isLocked(uuid)) {
            event.setCancelled(true);
            return;
        }
        if (staffModeManager.hasAppliedStaffItems(uuid)) {
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            event.getDrops().clear();
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (staffModeManager.isInStaffMode(uuid) || staffModeManager.isPending(uuid)) {
            staffModeManager.disable(player);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        boolean locked = isLocked(uuid);
        boolean staffMode = staffModeManager.isInStaffMode(uuid);
        if (!locked && !staffMode) {
            return;
        }
        String commandName = commandName(event.getMessage());
        if (locked) {
            if (commandName.equals("/staffmode") && staffModeManager.isPending(uuid)) {
                return;
            }
            event.setCancelled(true);
            return;
        }
        if (commandName.equals("/clear") || commandName.equals("/give")
                || commandName.equals("/take") || commandName.equals("/item")
                || commandName.equals("/loot") || commandName.equals("/replaceitem")) {
            event.setCancelled(true);
        }
    }

    static String commandName(String message) {
        String command = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
        int separator = command.indexOf(' ');
        if (separator >= 0) command = command.substring(0, separator);
        if (command.startsWith("/")) {
            int namespaceEnd = command.indexOf(':');
            if (namespaceEnd > 1) {
                command = "/" + command.substring(namespaceEnd + 1);
            }
        }
        return command;
    }

    static boolean isStaffMenu(InventoryHolder holder) {
        return holder instanceof StaffMenuHolder;
    }

    static boolean shouldBlockInventoryClick(boolean locked, boolean staffMode, boolean topInventory,
                                               boolean staffMenu, boolean shiftClick, boolean numberKey,
                                               boolean cursorAction) {
        if (locked) {
            return true;
        }
        return staffMode && (!topInventory || !staffMenu || shiftClick || numberKey || cursorAction);
    }

    static boolean shouldBlockInventoryDrag(boolean locked, boolean staffMode, boolean topInventoryOnly,
                                             boolean staffMenu) {
        return locked || (staffMode && (!topInventoryOnly || !staffMenu));
    }

    static boolean shouldBlockItemAction(boolean locked, boolean staffMode) {
        return locked || staffMode;
    }

    private boolean isLocked(UUID uuid) {
        return staffModeManager.isRecovering(uuid);
    }
}
