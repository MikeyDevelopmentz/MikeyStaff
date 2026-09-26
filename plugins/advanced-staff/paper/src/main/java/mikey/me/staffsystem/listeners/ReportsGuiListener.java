package mikey.me.staffsystem.listeners;

import mikey.me.staffsystem.inventory.StaffMenuHolder;
import mikey.me.staffsystem.managers.ReportsGuiManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

public class ReportsGuiListener implements Listener {

    private final ReportsGuiManager guiManager;

    public ReportsGuiListener(ReportsGuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player staff = (Player) event.getWhoClicked();
        if (!(event.getView().getTopInventory().getHolder() instanceof StaffMenuHolder)) {
            return;
        }
        String title = event.getView().getTitle();
        ItemStack clicked = event.getCurrentItem();

        // cancel before anything else, the confirm gui has empty slots
        // and an uncancelled click dumps the cursor item into a throwaway inv
        if (title.startsWith(org.bukkit.ChatColor.DARK_AQUA + "Reports")) {
            event.setCancelled(true);
            if (clicked != null) {
                guiManager.handleMainClick(staff, title, event.getRawSlot(), clicked, event.getClick());
            }
        } else if (title.startsWith(org.bukkit.ChatColor.RED + "Confirm Resolve")) {
            event.setCancelled(true);
            if (clicked != null) {
                guiManager.handleConfirmClick(staff, title, event.getRawSlot(), clicked);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof StaffMenuHolder)) {
            return;
        }
        String title = event.getView().getTitle();
        if (title.startsWith(org.bukkit.ChatColor.DARK_AQUA + "Reports")
                || title.startsWith(org.bukkit.ChatColor.RED + "Confirm Resolve")) {
            event.setCancelled(true);
        }
    }
}
