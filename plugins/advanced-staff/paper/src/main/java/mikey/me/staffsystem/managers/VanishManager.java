package mikey.me.staffsystem.managers;

import mikey.me.staffsystem.cache.VanishState;
import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.models.VanishLog;
import mikey.me.staffsystem.database.repositories.VanishLogRepository;
import mikey.me.staffsystem.messaging.VelocityMessenger;
import mikey.me.staffsystem.packets.PacketService;
import mikey.me.staffsystem.utils.JsonUtil;
import mikey.me.staffsystem.utils.SchedulerProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VanishManager {

    private final SettingsConfig settings;
    private final SchedulerProvider schedulerProvider;
    private final PacketService packetService;
    private final VanishLogRepository vanishLogRepository;
    private final VelocityMessenger velocityMessenger;
    private final Map<UUID, VanishState> vanished;

    public VanishManager(ConfigurationManager configurationManager, SchedulerProvider schedulerProvider,
            PacketService packetService, VanishLogRepository vanishLogRepository,
            VelocityMessenger velocityMessenger) {
        this.settings = configurationManager.getSettings();
        this.schedulerProvider = schedulerProvider;
        this.packetService = packetService;
        this.vanishLogRepository = vanishLogRepository;
        this.velocityMessenger = velocityMessenger;
        this.vanished = new ConcurrentHashMap<>();
        velocityMessenger.onVanishSync(this::handleVanishSync);
    }

    public boolean isVanished(UUID uuid) {
        return vanished.containsKey(uuid);
    }

    public Set<UUID> getVanished() {
        return Collections.unmodifiableSet(vanished.keySet());
    }

    public VanishState getVanishState(UUID uuid) {
        return vanished.get(uuid);
    }

    public boolean toggle(Player staff, Player target) {
        UUID targetId = target.getUniqueId();
        if (isVanished(targetId)) {
            setVanishState(staff, target, false);
            return false;
        }
        setVanishState(staff, target, true);
        return true;
    }

    public void setVanished(Player staff, Player target, boolean vanish) {
        if (isVanished(target.getUniqueId()) == vanish) return;
        setVanishState(staff, target, vanish);
    }

    public void applyVisibilityForJoin(Player joined) {
        for (UUID uuid : vanished.keySet()) {
            Player vanishedPlayer = Bukkit.getPlayer(uuid);
            if (vanishedPlayer != null && !vanishedPlayer.equals(joined)) {
                packetService.hidePlayer(vanishedPlayer, joined);
            }
        }
        if (vanished.containsKey(joined.getUniqueId())) {
            schedulerProvider.runSync(() -> applyPackets(joined, true));
        }
    }

    public void applyVanishState(UUID uuid, boolean vanish) {
        if (vanish) {
            vanished.put(uuid, new VanishState(uuid, uuid, System.currentTimeMillis()));
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) schedulerProvider.runSync(() -> applyPackets(player, true));
        } else {
            vanished.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) schedulerProvider.runSync(() -> applyPackets(player, false));
        }
    }

    public void shutdown() {
        vanished.clear();
    }

    private void setVanishState(Player staff, Player target, boolean vanish) {
        UUID uuid = target.getUniqueId();
        UUID staffId = staff.getUniqueId();
        if (vanish) {
            vanished.put(uuid, new VanishState(uuid, staffId, System.currentTimeMillis()));
        } else {
            vanished.remove(uuid);
        }
        // apply now if we can, the 1 tick delay causes a visible flash on join
        if (Bukkit.isPrimaryThread()) {
            applyPackets(target, vanish);
        } else {
            schedulerProvider.runSync(() -> applyPackets(target, vanish));
        }
        logChange(staff, target, vanish);
        String json = "{\"target_uuid\":\"" + uuid + "\",\"vanished\":" + vanish + "}";
        velocityMessenger.send(VelocityMessenger.CH_VANISH, json, uuid);
    }

    private void applyPackets(Player target, boolean vanish) {
        if (!target.isOnline()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) continue;
            if (vanish) {
                schedulerProvider.runOn(viewer, () -> packetService.hidePlayer(target, viewer));
            } else {
                schedulerProvider.runOn(viewer, () -> packetService.showPlayer(target, viewer));
            }
        }
    }

    private void logChange(Player staff, Player target, boolean vanish) {
        UUID playerId = target.getUniqueId();
        UUID staffId = staff.getUniqueId();
        String action = vanish ? "ENABLE" : "DISABLE";
        VanishLog log = new VanishLog(0, playerId, staffId, action, System.currentTimeMillis());
        vanishLogRepository.insert(log).whenComplete((unused, error) -> {
            if (error != null) {
                Bukkit.getLogger().warning("[Staff] vanish log insert failed for " + playerId + ": " + error.getMessage());
            }
        });
    }

    private void handleVanishSync(String json) {
        try {
            String uuidStr = JsonUtil.extractString(json, "target_uuid");
            boolean vanishState = JsonUtil.extractBool(json, "vanished", false);
            UUID uuid = UUID.fromString(uuidStr);
            applyVanishState(uuid, vanishState);
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[Staff] ignoring malformed vanish sync payload: " + e.getMessage());
        }
    }
}
