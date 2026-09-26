package mikey.me.staffsystem.managers;

import mikey.me.staffsystem.cache.StaffModeState;
import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.ItemsConfig;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.models.StaffSession;
import mikey.me.staffsystem.database.repositories.StaffSessionRepository;
import mikey.me.staffsystem.utils.InventoryUtil;
import mikey.me.staffsystem.utils.SchedulerProvider;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class StaffModeManager {

    private final SettingsConfig settings;
    private final ItemsConfig itemsConfig;
    private final StaffSessionRepository staffSessionRepository;
    private final InventoryUtil inventoryUtil;
    private final SchedulerProvider schedulerProvider;
    private final Map<UUID, StaffModeState> active;
    private final Map<UUID, StaffSession> activeSessions;
    private final Map<UUID, String> activeSerializedInventories;
    private final Map<UUID, Boolean> activeItemsApplied;
    private final Map<UUID, PendingEnable> pendingEnables;
    private final Set<UUID> recovering;
    private final Set<UUID> recoveryRequired;
    private final Set<UUID> closingSessions;
    private final Map<UUID, Player> recoveryPlayers;

    public StaffModeManager(ConfigurationManager configurationManager, SchedulerProvider schedulerProvider,
                             StaffSessionRepository staffSessionRepository) {
        this.settings = configurationManager.getSettings();
        this.itemsConfig = configurationManager.getItems();
        this.staffSessionRepository = staffSessionRepository;
        this.inventoryUtil = new InventoryUtil();
        this.schedulerProvider = schedulerProvider;
        this.active = new ConcurrentHashMap<>();
        this.activeSessions = new ConcurrentHashMap<>();
        this.activeSerializedInventories = new ConcurrentHashMap<>();
        this.activeItemsApplied = new ConcurrentHashMap<>();
        this.pendingEnables = new ConcurrentHashMap<>();
        this.recovering = ConcurrentHashMap.newKeySet();
        this.recoveryRequired = ConcurrentHashMap.newKeySet();
        this.closingSessions = ConcurrentHashMap.newKeySet();
        this.recoveryPlayers = new ConcurrentHashMap<>();
    }

    public boolean isInStaffMode(UUID uuid) {
        return active.containsKey(uuid);
    }

    public boolean isPending(UUID uuid) {
        return pendingEnables.containsKey(uuid);
    }

    public boolean isRecovering(UUID uuid) {
        return recovering.contains(uuid) || recoveryRequired.contains(uuid)
                || pendingEnables.containsKey(uuid) || closingSessions.contains(uuid);
    }

    public boolean hasAppliedStaffItems(UUID uuid) {
        return activeItemsApplied.getOrDefault(uuid, false);
    }

    public StaffModeState getState(UUID uuid) {
        return active.get(uuid);
    }

    public CompletableFuture<Boolean> enableAsync(Player player, boolean vanishedBeforeStaffMode) {
        UUID uuid = player.getUniqueId();
        if (isInStaffMode(uuid)) {
            return CompletableFuture.completedFuture(true);
        }
        if (pendingEnables.containsKey(uuid) || isRecovering(uuid) || closingSessions.contains(uuid)) {
            return CompletableFuture.completedFuture(false);
        }

        boolean saveInventory = settings.isStaffModeSaveInventory();
        boolean itemsEnabled = itemsConfig.isStaffModeEnabled();
        if (!canReplaceInventory(itemsEnabled, saveInventory)) {
            Bukkit.getLogger().warning("[Staff] staff mode was not enabled for " + uuid
                    + ": inventory saving must be enabled before staff items can replace it");
            return CompletableFuture.completedFuture(false);
        }

        if (!itemsEnabled || configuredItems(player).isEmpty()) {
            long now = System.currentTimeMillis();
            active.put(uuid, new StaffModeState(uuid, now, vanishedBeforeStaffMode));
            return CompletableFuture.completedFuture(true);
        }

        String serialized = inventoryUtil.serialize(player.getInventory().getContents());
        if (serialized == null) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<StaffSession> persistence;
        long now = System.currentTimeMillis();
        try {
            persistence = staffSessionRepository.startSession(uuid, serialized, now);
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] failed to start inventory session for " + uuid + ": " + e.getMessage());
            return CompletableFuture.completedFuture(false);
        }

        PendingEnable request = new PendingEnable(
                new StaffModeState(uuid, now, vanishedBeforeStaffMode), serialized);
        pendingEnables.put(uuid, request);
        persistence.whenComplete((session, error) -> handleSessionStart(player, request, session, error));
        return request.result;
    }

    public boolean enable(Player player, boolean vanishedBeforeStaffMode) {
        try {
            CompletableFuture<Boolean> result = enableAsync(player, vanishedBeforeStaffMode);
            return result.isDone() && Boolean.TRUE.equals(result.join());
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning(
                    "[Staff] staff mode enable failed for " + player.getUniqueId() + ": " + e.getMessage());
            return false;
        }
    }

    public boolean enable(Player player) {
        return enable(player, false);
    }

    public boolean disable(Player player) {
        UUID uuid = player.getUniqueId();
        PendingEnable pending = pendingEnables.remove(uuid);
        if (pending != null) {
            pending.result.complete(false);
            return true;
        }

        StaffModeState state = active.get(uuid);
        if (state == null) {
            return true;
        }
        boolean itemsApplied = activeItemsApplied.getOrDefault(uuid, false);
        String serialized = activeSerializedInventories.get(uuid);
        if (itemsApplied && !restoreSerializedInventory(player, serialized)) {
            recoveryRequired.add(uuid);
            Bukkit.getLogger().warning("[Staff] inventory restore failed for " + uuid
                    + ", staff mode stays on and the recovery session stays open");
            return false;
        }

        StaffSession session = activeSessions.remove(uuid);
        active.remove(uuid, state);
        activeSerializedInventories.remove(uuid);
        activeItemsApplied.remove(uuid);
        if (session != null && session.getId() > 0) {
            closeSession(uuid, session.getId());
        }
        return true;
    }

    public void recover(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        recoveryPlayers.put(uuid, player);
        if (!recovering.add(uuid)) {
            return;
        }
        CompletableFuture<Optional<StaffSession>> lookup;
        try {
            lookup = staffSessionRepository.findOpenByStaff(uuid);
        } catch (RuntimeException e) {
            recoveryPlayers.remove(uuid, player);
            recovering.remove(uuid);
            recoveryRequired.add(uuid);
            Bukkit.getLogger().warning("[Staff] failed to look up open inventory session for " + uuid + ": " + e.getMessage());
            return;
        }
        lookup.whenComplete((optional, error) -> {
            if (error != null) {
                recoveryPlayers.remove(uuid, player);
                recovering.remove(uuid);
                recoveryRequired.add(uuid);
                Bukkit.getLogger().warning("[Staff] failed to recover inventory session for " + uuid + ": " + error.getMessage());
                return;
            }
            if (optional.isEmpty()) {
                recoveryPlayers.remove(uuid, player);
                recovering.remove(uuid);
                recoveryRequired.remove(uuid);
                return;
            }
            StaffSession session = optional.get();
            Player currentPlayer = recoveryPlayers.get(uuid);
            if (currentPlayer == null) {
                recovering.remove(uuid);
                recoveryRequired.add(uuid);
                return;
            }
            try {
                if (schedulerProvider.runFor(currentPlayer, () -> finishRecovery(currentPlayer, uuid, session)) == null) {
                    recovering.remove(uuid);
                    recoveryRequired.add(uuid);
                }
            } catch (RuntimeException e) {
                recovering.remove(uuid);
                recoveryRequired.add(uuid);
                Bukkit.getLogger().warning("[Staff] could not schedule inventory recovery for " + uuid + ": " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        for (PendingEnable pending : pendingEnables.values()) {
            pending.result.complete(false);
        }
        pendingEnables.clear();
        for (UUID uuid : active.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                disable(player);
            }
        }
        active.clear();
        activeSessions.clear();
        activeSerializedInventories.clear();
        activeItemsApplied.clear();
        recovering.clear();
        recoveryRequired.clear();
        closingSessions.clear();
        recoveryPlayers.clear();
    }

    public String getActionForSlot(int slot) {
        if (!itemsConfig.isStaffModeEnabled()) {
            return null;
        }
        FileConfiguration config = itemsConfig.getConfig();
        ConfigurationSection section = config.getConfigurationSection("staffmode.hotbar");
        if (section == null) {
            return null;
        }
        String key = "slot-" + slot;
        ConfigurationSection itemSection = section.getConfigurationSection(key);
        if (itemSection == null) {
            return null;
        }
        return itemSection.getString("action");
    }

    static boolean canReplaceInventory(boolean itemsEnabled, boolean saveInventory) {
        return !itemsEnabled || saveInventory;
    }

    private void handleSessionStart(Player player, PendingEnable request, StaffSession session, Throwable error) {
        UUID uuid = request.state.getStaffUuid();
        if (pendingEnables.get(uuid) != request) {
            if (session != null && session.getId() > 0) {
                closeSession(uuid, session.getId());
            }
            return;
        }
        if (error != null || session == null || session.getId() <= 0) {
            try {
                if (schedulerProvider.runFor(player, () -> failPendingEnable(player, request, error)) == null) {
                    pendingEnables.remove(uuid, request);
                    request.result.complete(false);
                }
            } catch (RuntimeException e) {
                pendingEnables.remove(uuid, request);
                request.result.complete(false);
            }
            return;
        }
        try {
            if (schedulerProvider.runFor(player, () -> applyPendingEnable(player, request, session)) == null) {
                pendingEnables.remove(uuid, request);
                request.result.complete(false);
            }
        } catch (RuntimeException e) {
            pendingEnables.remove(uuid, request);
            request.result.complete(false);
            closeSession(uuid, session.getId());
        }
    }

    private void failPendingEnable(Player player, PendingEnable request, Throwable error) {
        UUID uuid = request.state.getStaffUuid();
        if (!pendingEnables.remove(uuid, request)) {
            return;
        }
        request.result.complete(false);
        if (error != null) {
            Bukkit.getLogger().warning("[Staff] inventory session start failed for " + uuid + ": " + error.getMessage());
        }
    }

    private void applyPendingEnable(Player player, PendingEnable request, StaffSession session) {
        UUID uuid = request.state.getStaffUuid();
        if (pendingEnables.get(uuid) != request) {
            closeSession(uuid, session.getId());
            return;
        }
        if (!player.isOnline()) {
            pendingEnables.remove(uuid, request);
            request.result.complete(false);
            closeSession(uuid, session.getId());
            return;
        }

        boolean itemsApplied;
        try {
            itemsApplied = giveStaffItems(player);
        } catch (RuntimeException e) {
            pendingEnables.remove(uuid, request);
            request.result.complete(false);
            boolean restored = restoreSerializedInventory(player, request.serialized);
            if (restored) {
                closeSession(uuid, session.getId());
            } else {
                recoveryRequired.add(uuid);
            }
            Bukkit.getLogger().warning("[Staff] failed to apply staff items for " + uuid + ": " + e.getMessage());
            return;
        }

        pendingEnables.remove(uuid, request);
        active.put(uuid, request.state);
        if (itemsApplied) {
            activeSerializedInventories.put(uuid, request.serialized);
            activeSessions.put(uuid, session);
            activeItemsApplied.put(uuid, true);
        } else {
            closeSession(uuid, session.getId());
        }
        request.result.complete(true);
    }

    private void finishRecovery(Player player, UUID uuid, StaffSession session) {
        try {
            if (pendingEnables.containsKey(uuid)) {
                return;
            }
            StaffSession activeSession = activeSessions.get(uuid);
            if (isInStaffMode(uuid) && (activeSession == null || activeSession.getId() != session.getId())) {
                return;
            }
            if (!player.isOnline()) {
                recoveryRequired.add(uuid);
                return;
            }
            if (session.getId() > 0 && restoreSerializedInventory(player, session.getSerializedInventory())) {
                active.remove(uuid);
                activeSessions.remove(uuid);
                activeSerializedInventories.remove(uuid);
                activeItemsApplied.remove(uuid);
                closeSession(uuid, session.getId());
                recoveryRequired.remove(uuid);
            } else {
                recoveryRequired.add(uuid);
                Bukkit.getLogger().warning("[Staff] could not recover inventory session " + session.getId()
                        + " for " + uuid + ", session stays open");
            }
        } finally {
            recoveryPlayers.remove(uuid, player);
            recovering.remove(uuid);
        }
    }

    private void closeSession(UUID uuid, long id) {
        closingSessions.add(uuid);
        closeSession(uuid, id, 0);
    }

    private void closeSession(UUID uuid, long id, int attempt) {
        try {
            staffSessionRepository.endSession(id, System.currentTimeMillis()).whenComplete((ignored, error) -> {
                if (error == null) {
                    closingSessions.remove(uuid);
                    return;
                }
                if (attempt < 3) {
                    try {
                        schedulerProvider.runSyncLater(() -> closeSession(uuid, id, attempt + 1), 20L * (attempt + 1));
                        return;
                    } catch (RuntimeException schedulingError) {
                        markCloseFailure(uuid, id, schedulingError);
                        return;
                    }
                }
                markCloseFailure(uuid, id, error);
            });
        } catch (RuntimeException e) {
            markCloseFailure(uuid, id, e);
        }
    }

    private void markCloseFailure(UUID uuid, long id, Throwable error) {
        closingSessions.remove(uuid);
        recoveryRequired.add(uuid);
        Bukkit.getLogger().warning("[Staff] failed to close inventory session " + id + ": " + error.getMessage());
    }

    private Map<Integer, ItemStack> configuredItems(Player player) {
        if (!itemsConfig.isStaffModeEnabled()) {
            return Map.of();
        }
        PlayerInventory inventory = player.getInventory();
        ConfigurationSection section = itemsConfig.getConfig().getConfigurationSection("staffmode.hotbar");
        if (section == null) {
            return Map.of();
        }
        Map<Integer, ItemStack> configuredItems = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (!key.matches("slot-\\d+")) {
                continue;
            }
            ConfigurationSection itemSection = section.getConfigurationSection(key);
            if (itemSection == null) {
                continue;
            }
            int slot;
            try {
                slot = Integer.parseInt(key.replace("slot-", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            String materialName = itemSection.getString("material");
            if (materialName == null) {
                continue;
            }
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                continue;
            }
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                String displayName = itemSection.getString("name");
                if (displayName != null) {
                    meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));
                }
                List<String> lore = itemSection.getStringList("lore");
                if (lore != null && !lore.isEmpty()) {
                    List<String> coloredLore = lore.stream()
                            .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                            .collect(Collectors.toList());
                    meta.setLore(coloredLore);
                }
                item.setItemMeta(meta);
            }
            configuredItems.put(slot, item);
        }
        return configuredItems;
    }

    private boolean giveStaffItems(Player player) {
        Map<Integer, ItemStack> configuredItems = configuredItems(player);
        if (configuredItems.isEmpty()) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        for (Map.Entry<Integer, ItemStack> entry : configuredItems.entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue());
        }
        player.updateInventory();
        return true;
    }

    private boolean restoreSerializedInventory(Player player, String serialized) {
        if (serialized == null || serialized.isEmpty()) {
            Bukkit.getLogger().warning("[Staff] no inventory snapshot is available for " + player.getUniqueId());
            return false;
        }
        ItemStack[] contents = inventoryUtil.deserialize(serialized);
        if (contents == null) {
            return false;
        }
        try {
            player.getInventory().setContents(contents);
            player.updateInventory();
            return true;
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] failed to restore inventory for " + player.getUniqueId() + ": " + e.getMessage());
            return false;
        }
    }

    private static final class PendingEnable {
        private final StaffModeState state;
        private final String serialized;
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();

        private PendingEnable(StaffModeState state, String serialized) {
            this.state = state;
            this.serialized = serialized;
        }
    }
}
