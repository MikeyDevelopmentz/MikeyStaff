package mikey.me.staffsystem.inventory;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class StaffMenuHolder implements InventoryHolder {

    private Inventory inventory;

    public static Inventory create(int size, String title) {
        // bukkit needs a multiple of 9 and >= 9; container inventories (hopper is 5, furnace 3) broke staff mode. round like InspectManager does
        int slots = Math.min(54, Math.max(9, ((size + 8) / 9) * 9));
        StaffMenuHolder holder = new StaffMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, slots, title);
        holder.inventory = inventory;
        return inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
