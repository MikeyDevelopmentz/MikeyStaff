package mikey.me.staffsystem.utils;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.logging.Logger;

public class InventoryUtil {

    public String serialize(ItemStack[] contents) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeInt(contents.length);
            for (ItemStack item : contents) {
                dataOutput.writeObject(item);
            }
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException e) {
            log("[Staff] failed to serialize inventory: " + e.getMessage());
        }
        return null;
    }

    public ItemStack[] deserialize(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            int size = dataInput.readInt();
            if (size < 0 || size > 256) {
                throw new IllegalArgumentException("invalid inventory size " + size);
            }
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                Object value = dataInput.readObject();
                if (value != null && !(value instanceof ItemStack)) {
                    throw new ClassCastException("inventory entry is not an ItemStack");
                }
                items[i] = (ItemStack) value;
            }
            return items;
        } catch (IOException | IllegalArgumentException | ClassCastException | ClassNotFoundException e) {
            log("[Staff] failed to deserialize inventory: " + e.getMessage());
            return null;
        }
    }

    private void log(String message) {
        if (Bukkit.getServer() == null) {
            Logger.getLogger("AdvancedStaff").warning(message);
        } else {
            Bukkit.getLogger().warning(message);
        }
    }
}
