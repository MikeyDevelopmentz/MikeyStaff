package mikey.me.staffsystem.listeners;

import mikey.me.staffsystem.inventory.StaffMenuHolder;
import mikey.me.staffsystem.managers.InspectManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class PlayerInfoListener implements Listener {

    private final InspectManager inspectManager;

    public PlayerInfoListener(InspectManager inspectManager) {
        this.inspectManager = inspectManager;
    }

    // these are all read only guis
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player staff = (Player) event.getWhoClicked();
        if (inspectManager.isFrozen(staff.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (inspectManager.isReadOnlyInventory(top)) {
            event.setCancelled(true);
            return;
        }
        String title = event.getView().getTitle();
        if (!isInspectGui(top, title)) {
            return;
        }
        // always cancel, empty slots would eat items otherwise
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }

        boolean handled = inspectManager.handleMainMenuClick(staff, title, event.getRawSlot());
        if (!handled) {
            handled = inspectManager.handleIPHistoryClick(staff, title, event.getRawSlot(), clicked);
        }
        if (!handled) {
            handled = inspectManager.handleLoginLogsClick(staff, title, event.getRawSlot(), clicked);
        }
        if (!handled) {
            handled = inspectManager.handleAltAccountsClick(staff, title, event.getRawSlot(), clicked);
        }
        if (!handled) {
            handled = inspectManager.handleInfoClick(staff, title, event.getRawSlot(), clicked);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (inspectManager.isFrozen(((Player) event.getWhoClicked()).getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (inspectManager.isReadOnlyInventory(event.getView().getTopInventory())
                || isInspectGui(event.getView().getTopInventory(), event.getView().getTitle())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        inspectManager.handleReadOnlyClose(event.getInventory());
    }

    private boolean isInspectGui(Inventory top, String title) {
        if (top == null || !(top.getHolder() instanceof StaffMenuHolder) || title == null) {
            return false;
        }
        return title.startsWith(ChatColor.DARK_AQUA + "Inspect: ")
                || title.startsWith(ChatColor.DARK_AQUA + "IP History: ")
                || title.startsWith(ChatColor.DARK_AQUA + "Login Logs: ")
                || title.startsWith(ChatColor.DARK_AQUA + "Alt Accounts: ")
                || title.startsWith(ChatColor.DARK_AQUA + "Player Info: ");
    }
}
