package mikey.me.staffsystem.inventory;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class StaffMenuHolder implements InventoryHolder {

    private Inventory inventory;

    public static Inventory create(int size, String title) {
        StaffMenuHolder holder = new StaffMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        return inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
