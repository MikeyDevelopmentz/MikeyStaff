package mikey.me.staffsystem.listeners;

import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.inventory.StaffMenuHolder;
import mikey.me.staffsystem.managers.FreezeManager;
import mikey.me.staffsystem.managers.InspectManager;
import mikey.me.staffsystem.managers.StaffModeManager;
import mikey.me.staffsystem.managers.TeleportManager;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StaffModeToolPermissionTest {

    private final Player staff = mock(Player.class);
    private final Player target = mock(Player.class);
    private final StaffModeManager staffMode = mock(StaffModeManager.class);
    private final FreezeManager freezes = mock(FreezeManager.class);
    private final TeleportManager teleports = mock(TeleportManager.class);
    private final InspectManager inspect = mock(InspectManager.class);
    private final YamlConfiguration config = new YamlConfiguration();
    private StaffModeToolListener listener;

    @BeforeEach
    void setUp() {
        when(staff.getUniqueId()).thenReturn(UUID.randomUUID());
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        when(staff.getName()).thenReturn("staff");
        when(target.getName()).thenReturn("target");
        when(staff.getInventory()).thenReturn(mock(PlayerInventory.class));
        when(staffMode.isInStaffMode(staff.getUniqueId())).thenReturn(true);
        when(staff.hasPermission("staff.staffmode.use")).thenReturn(true);
        TextUtil text = mock(TextUtil.class);
        when(text.prefixed(anyString())).thenReturn("denied");
        listener = new StaffModeToolListener(staffMode, teleports, freezes, inspect, new SettingsConfig(config), text);
    }

    private EntityDamageByEntityEvent hit() {
        when(staffMode.getActionForSlot(0)).thenReturn("FREEZE");
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(staff);
        when(event.getEntity()).thenReturn(target);
        return event;
    }

    @Test
    void staffModeAloneCannotFreezeOrUnfreeze() {
        listener.onHitEntity(hit());
        when(freezes.isFrozen(target.getUniqueId())).thenReturn(true);
        listener.onHitEntity(hit());
        verify(freezes, never()).freeze(any(), any(), anyLong(), anyString());
        verify(freezes, never()).unfreeze(any(), any());
    }

    @Test
    void customFreezePermissionAllowsTheTool() {
        config.set("permissions.freeze.use", "custom.freeze");
        when(staff.hasPermission("custom.freeze")).thenReturn(true);
        when(freezes.freeze(eq(staff), eq(target), anyLong(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        listener.onHitEntity(hit());
        // 600 is the SettingsConfig fallback: an unset key must not mean a permanent freeze.
        verify(freezes).freeze(staff, target, 600L, "");
    }

    @Test
    void revokedStaffModePermissionBlocksTheTool() {
        when(staff.hasPermission("staff.freeze.use")).thenReturn(true);
        when(staff.hasPermission("staff.staffmode.use")).thenReturn(false);
        listener.onHitEntity(hit());
        verify(freezes, never()).freeze(any(), any(), anyLong(), anyString());
    }

    @Test
    void staffModeAloneCannotTeleportOrInspectByClickingPlayers() {
        PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
        when(event.getPlayer()).thenReturn(staff);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getRightClicked()).thenReturn(target);
        when(staffMode.getActionForSlot(0)).thenReturn("TELEPORT");
        listener.onInteractEntity(event);
        when(staffMode.getActionForSlot(0)).thenReturn("INSPECT");
        listener.onInteractEntity(event);
        verifyNoInteractions(teleports, inspect);
    }

    @Test
    void staffModeAloneCannotOpenThePlayerMenus() {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(staff);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
        when(staffMode.getActionForSlot(0)).thenReturn("TELEPORT");
        listener.onInteract(event);
        when(staffMode.getActionForSlot(0)).thenReturn("INSPECT");
        listener.onInteract(event);
        verify(staff, never()).openInventory(any(Inventory.class));
    }

    @Test
    void menuClicksRecheckPermissions() {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(event.getWhoClicked()).thenReturn(staff);
        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getTitle()).thenReturn(ChatColor.DARK_AQUA + "Staff Teleport (1/1)");
        when(top.getHolder()).thenReturn(mock(StaffMenuHolder.class));
        listener.onInventoryClick(event);
        verify(event).setCancelled(true);
        verify(staff).closeInventory();
        verifyNoInteractions(teleports);
    }
}
