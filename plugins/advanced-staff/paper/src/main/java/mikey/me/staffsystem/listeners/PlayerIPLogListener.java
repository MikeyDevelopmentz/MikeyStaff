package mikey.me.staffsystem.listeners;

import mikey.me.staffsystem.managers.IPManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerIPLogListener implements Listener {

    private static final int MAX_PRE_LOGIN_ENTRIES = 1024;
    private static final long PRE_LOGIN_ENTRY_MAX_AGE_MS = 300_000L;

    private final IPManager ipManager;
    private final Map<UUID, PreLoginEntry> preLoginIps = new ConcurrentHashMap<>();

    public PlayerIPLogListener(IPManager ipManager) {
        this.ipManager = ipManager;
    }

    // real ip here, same source as the alt check
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        if (event.getAddress() == null) {
            preLoginIps.remove(uuid);
            return;
        }
        remember(uuid, new PreLoginEntry(event.getAddress().getHostAddress(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginResult(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            preLoginIps.remove(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        PreLoginEntry entry = preLoginIps.remove(event.getPlayer().getUniqueId());
        ipManager.logPlayerIP(event.getPlayer(), entry == null ? null : entry.ip());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        preLoginIps.remove(event.getPlayer().getUniqueId());
    }

    private void remember(UUID uuid, PreLoginEntry entry) {
        long now = entry.createdAt();
        for (Map.Entry<UUID, PreLoginEntry> cached : preLoginIps.entrySet()) {
            if (now - cached.getValue().createdAt() > PRE_LOGIN_ENTRY_MAX_AGE_MS) {
                preLoginIps.remove(cached.getKey(), cached.getValue());
            }
        }
        if (!preLoginIps.containsKey(uuid)) {
            while (preLoginIps.size() >= MAX_PRE_LOGIN_ENTRIES) {
                UUID oldestKey = null;
                PreLoginEntry oldestEntry = null;
                long oldest = Long.MAX_VALUE;
                for (Map.Entry<UUID, PreLoginEntry> cached : preLoginIps.entrySet()) {
                    if (cached.getValue().createdAt() < oldest) {
                        oldest = cached.getValue().createdAt();
                        oldestKey = cached.getKey();
                        oldestEntry = cached.getValue();
                    }
                }
                if (oldestKey == null || oldestEntry == null || !preLoginIps.remove(oldestKey, oldestEntry)) {
                    break;
                }
            }
        }
        preLoginIps.put(uuid, entry);
    }

    private record PreLoginEntry(String ip, long createdAt) {
    }
}
