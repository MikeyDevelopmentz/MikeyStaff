package mikey.me.staffsystem.listeners;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import mikey.me.staffsystem.managers.StaffModeManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StaffModeSilentOpenListenerTest {
    @Test
    void silentChestUsesValidSize() {
        silentContainer(Material.CHEST, 27);
    }

    @Test
    void silentFurnaceUsesValidSize() {
        silentContainer(Material.FURNACE, 3);
    }

    @Test
    void silentHopperUsesValidSize() {
        silentContainer(Material.HOPPER, 5);
    }

    private void silentContainer(Material material, int slots) {
        StaffModeManager manager = mock(StaffModeManager.class);
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getScheduler()).thenReturn(mock(EntityScheduler.class));
        when(manager.isInStaffMode(uuid)).thenReturn(true);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
        when(event.getPlayer()).thenReturn(player);
        Block block = mock(Block.class);
        when(event.getClickedBlock()).thenReturn(block);
        when(block.getType()).thenReturn(material);
        Container container = mock(Container.class);
        when(block.getState()).thenReturn(container);
        Inventory real = mock(Inventory.class);
        when(container.getInventory()).thenReturn(real);
        when(real.getSize()).thenReturn(slots);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), anyString()))
                    .thenAnswer(call -> {
                        int size = call.getArgument(1);
                        if (size % 9 != 0) throw new IllegalArgumentException("Size must be a multiple of 9, got " + size);
                        return mock(Inventory.class);
                    });
            assertDoesNotThrow(() -> new StaffModeSilentOpenListener(manager, mock(Plugin.class)).onInteract(event));
            verify(player).openInventory(any(Inventory.class));
        }
    }

}
