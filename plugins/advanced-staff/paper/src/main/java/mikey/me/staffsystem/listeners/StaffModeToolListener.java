package mikey.me.staffsystem.listeners;

import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.inventory.StaffMenuHolder;
import mikey.me.staffsystem.managers.FreezeManager;
import mikey.me.staffsystem.managers.InspectManager;
import mikey.me.staffsystem.managers.StaffModeManager;
import mikey.me.staffsystem.managers.TeleportManager;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StaffModeToolListener implements Listener {

    private static final String TELEPORT_GUI_TITLE = ChatColor.DARK_AQUA + "Staff Teleport";
    private static final String INSPECT_GUI_TITLE = ChatColor.GOLD + "Staff Inspect";

    private final StaffModeManager staffModeManager;
    private final TeleportManager teleportManager;
    private final FreezeManager freezeManager;
    private final InspectManager inspectManager;
    private final SettingsConfig settings;
    private final TextUtil textUtil;

    private final Map<UUID, Integer> teleportPages = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> inspectPages = new ConcurrentHashMap<>();

    public StaffModeToolListener(StaffModeManager staffModeManager, TeleportManager teleportManager,
            FreezeManager freezeManager, InspectManager inspectManager, SettingsConfig settings, TextUtil textUtil) {
        this.staffModeManager = staffModeManager;
        this.teleportManager = teleportManager;
        this.freezeManager = freezeManager;
        this.inspectManager = inspectManager;
        this.settings = settings;
        this.textUtil = textUtil;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        org.bukkit.event.block.Action interactAction = event.getAction();
        if (interactAction != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && interactAction != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Player staff = event.getPlayer();
        if (staffModeManager.isRecovering(staff.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        // air clicks always report cancelled, check the item result instead
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY || freezeManager.isFrozen(staff.getUniqueId())) {
            return;
        }
        if (!staffModeManager.isInStaffMode(staff.getUniqueId())) {
            return;
        }
        int slot = staff.getInventory().getHeldItemSlot();
        String action = staffModeManager.getActionForSlot(slot);
        if (action == null) {
            return;
        }
        String upper = action.toUpperCase();
        if (upper.equals("TELEPORT")) {
            event.setCancelled(true);
            openTeleportMenu(staff, 0);
            return;
        }
        if (upper.equals("INSPECT")) {
            event.setCancelled(true);
            openInspectMenu(staff, 0);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Player staff = event.getPlayer();
        if (staffModeManager.isRecovering(staff.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (event.isCancelled() || freezeManager.isFrozen(staff.getUniqueId())) {
            return;
        }
        Entity entity = event.getRightClicked();
        if (!(entity instanceof Player)) {
            return;
        }
        if (!staffModeManager.isInStaffMode(staff.getUniqueId())) {
            return;
        }
        int slot = staff.getInventory().getHeldItemSlot();
        String action = staffModeManager.getActionForSlot(slot);
        if (action == null) {
            return;
        }
        Player target = (Player) entity;
        String upper = action.toUpperCase();
        if (upper.equals("TELEPORT")) {
            if (teleportManager.teleportTo(staff, target)) {
                staff.sendMessage(textUtil.papi(target, "teleport.tp", Map.of("%staff_target_name%", target.getName())));
            } else {
                staff.sendMessage(textUtil.prefixed("teleport.failed"));
            }
            return;
        }
        if (upper.equals("INSPECT")) {
            inspectManager.openMainMenu(staff, target);
            staff.sendMessage(textUtil.papi(target, "inspect.opened", Map.of("%staff_target_name%", target.getName())));
        }
    }

    @EventHandler
    public void onHitEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) {
            return;
        }
        Player staff = (Player) event.getDamager();
        Player target = (Player) event.getEntity();
        if (staffModeManager.isRecovering(staff.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        // freeze listener already cancels hits on frozen players, still let the tool unfreeze them
        if ((event.isCancelled() && !freezeManager.isFrozen(target.getUniqueId())) || freezeManager.isFrozen(staff.getUniqueId())) {
            return;
        }
        if (!staffModeManager.isInStaffMode(staff.getUniqueId())) {
            return;
        }
        int slot = staff.getInventory().getHeldItemSlot();
        String action = staffModeManager.getActionForSlot(slot);
        if (action == null || !action.equalsIgnoreCase("FREEZE")) {
            return;
        }
        event.setCancelled(true);
        if (freezeManager.isFrozen(target.getUniqueId())) {
            freezeManager.unfreeze(staff, target).whenComplete((unfrozen, error) -> {
                if (error != null) {
                    staff.sendMessage(textUtil.prefixed("errors.database-error"));
                    return;
                }
                if (!unfrozen) {
                    staff.sendMessage(textUtil.prefixed("freeze.not-frozen"));
                    return;
                }
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("%frozen_player%", target.getName());
                placeholders.put("%frozen_staff%", staff.getName());
                target.sendMessage(textUtil.format(textUtil.prefixed("freeze.unfrozen"), placeholders));
                staff.sendMessage(textUtil.format(textUtil.prefixed("freeze.staff-unfreeze"), placeholders));
            });
            return;
        }
        long durationSeconds = settings.getFreezeDefaultDurationSeconds();
        String reason = "";
        Map<String, String> placeholders = Map.of(
                "%staff_frozen_player%", target.getName(),
                "%staff_frozen_staff%", staff.getName(),
                "%staff_freeze_reason%", reason);
        freezeManager.freeze(staff, target, durationSeconds, reason).whenComplete((frozen, error) -> {
            if (error != null) {
                staff.sendMessage(textUtil.prefixed("errors.database-error"));
                return;
            }
            if (!frozen) {
                staff.sendMessage(textUtil.prefixed("errors.invalid-usage"));
                return;
            }
            String frozenTemplate = textUtil.papi(target, "freeze.frozen", placeholders);
            String notifyTemplate = textUtil.papi(target, "freeze.staff-freeze", placeholders);
            target.sendMessage(frozenTemplate);
            staff.sendMessage(notifyTemplate);
        });
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        teleportPages.remove(event.getPlayer().getUniqueId());
        inspectPages.remove(event.getPlayer().getUniqueId());
    }

    private void openTeleportMenu(Player staff, int page) {
        openPlayerMenu(staff, page, TELEPORT_GUI_TITLE);
    }

    private void openInspectMenu(Player staff, int page) {
        openPlayerMenu(staff, page, INSPECT_GUI_TITLE);
    }

    private void openPlayerMenu(Player staff, int page, String title) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.remove(staff);
        players.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int pageSize = 28;
        int maxPage = players.isEmpty() ? 0 : (players.size() - 1) / pageSize;
        if (page < 0) {
            page = 0;
        }
        if (page > maxPage) {
            page = maxPage;
        }
        if (title.equals(TELEPORT_GUI_TITLE)) {
            teleportPages.put(staff.getUniqueId(), page);
        } else if (title.equals(INSPECT_GUI_TITLE)) {
            inspectPages.put(staff.getUniqueId(), page);
        }

        Inventory inv = StaffMenuHolder.create(54,
                title + ChatColor.GRAY + " (" + (page + 1) + "/" + (maxPage + 1) + ")");

        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + "", null);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        int[] slots = { 10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43 };

        int start = page * pageSize;
        for (int i = 0; i < pageSize; i++) {
            int index = start + i;
            if (index >= players.size()) {
                break;
            }
            Player target = players.get(index);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = head.getItemMeta();
            if (meta instanceof SkullMeta) {
                SkullMeta skullMeta = (SkullMeta) meta;
                skullMeta.setOwningPlayer(target);
                skullMeta.setDisplayName(ChatColor.AQUA + target.getName());
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Click to " + (title.equals(TELEPORT_GUI_TITLE) ? "teleport" : "inspect"));
                meta.setLore(lore);
            }
            head.setItemMeta(meta);
            inv.setItem(slots[i], head);
        }

        if (page > 0) {
            inv.setItem(45, createItem(Material.ARROW, ChatColor.YELLOW + "Previous Page", null));
        }
        if (page < maxPage) {
            inv.setItem(53, createItem(Material.ARROW, ChatColor.YELLOW + "Next Page", null));
        }
        inv.setItem(49, createItem(Material.BARRIER, ChatColor.RED + "Close", null));

        staff.openInventory(inv);
    }

    private ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.setDisplayName(name);
            }
            if (lore != null) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player staff = (Player) event.getWhoClicked();
        if (staffModeManager.isRecovering(staff.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (event.isCancelled() || freezeManager.isFrozen(staff.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof StaffMenuHolder)) {
            return;
        }
        String title = event.getView().getTitle();
        if (title == null || (!title.startsWith(TELEPORT_GUI_TITLE) && !title.startsWith(INSPECT_GUI_TITLE))) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        int slot = event.getRawSlot();
        UUID uuid = staff.getUniqueId();

        if (slot == 49) {
            staff.closeInventory();
            return;
        }
        if (slot == 45 && clicked.getType() == Material.ARROW) {
            int current = title.startsWith(TELEPORT_GUI_TITLE) ? teleportPages.getOrDefault(uuid, 0) : inspectPages.getOrDefault(uuid, 0);
            if (current > 0) {
                if (title.startsWith(TELEPORT_GUI_TITLE)) {
                    openTeleportMenu(staff, current - 1);
                } else {
                    openInspectMenu(staff, current - 1);
                }
            }
            return;
        }
        if (slot == 53 && clicked.getType() == Material.ARROW) {
            int current = title.startsWith(TELEPORT_GUI_TITLE) ? teleportPages.getOrDefault(uuid, 0) : inspectPages.getOrDefault(uuid, 0);
            if (title.startsWith(TELEPORT_GUI_TITLE)) {
                openTeleportMenu(staff, current + 1);
            } else {
                openInspectMenu(staff, current + 1);
            }
            return;
        }

        if (clicked.getType() == Material.PLAYER_HEAD) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta instanceof SkullMeta) {
                SkullMeta skullMeta = (SkullMeta) meta;
                Player target = skullMeta.getOwningPlayer() instanceof Player ? (Player) skullMeta.getOwningPlayer() : null;
                if (target == null || !target.isOnline()) {
                    staff.sendMessage(textUtil.prefixed("errors.player-not-found"));
                    return;
                }
                if (title.startsWith(TELEPORT_GUI_TITLE)) {
                    if (teleportManager.teleportTo(staff, target)) {
                        staff.sendMessage(textUtil.papi(target, "teleport.tp", Map.of("%staff_target_name%", target.getName())));
                    } else {
                        staff.sendMessage(textUtil.prefixed("teleport.failed"));
                    }
                    staff.closeInventory();
                } else if (title.startsWith(INSPECT_GUI_TITLE)) {
                    inspectManager.openMainMenu(staff, target);
                    staff.sendMessage(textUtil.papi(target, "inspect.opened", Map.of("%staff_target_name%", target.getName())));
                }
            }
        }
    }
}
