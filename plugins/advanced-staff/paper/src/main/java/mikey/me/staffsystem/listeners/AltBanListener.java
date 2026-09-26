package mikey.me.staffsystem.listeners;

import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.repositories.PlayerIPLogRepository;
import mikey.me.staffsystem.managers.PunishmentManager;
import mikey.me.staffsystem.utils.NetworkPlayerResolver;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class AltBanListener implements Listener {

    private final PunishmentManager punishmentManager;
    private final PlayerIPLogRepository ipLogRepository;
    private final TextUtil textUtil;
    private final SettingsConfig settings;
    private final Plugin plugin;
    private final NetworkPlayerResolver networkPlayerResolver;

    public AltBanListener(PunishmentManager punishmentManager, PlayerIPLogRepository ipLogRepository,
                          TextUtil textUtil, Plugin plugin) {
        this(punishmentManager, ipLogRepository, textUtil,
                new SettingsConfig(new YamlConfiguration()), plugin, null);
    }

    public AltBanListener(PunishmentManager punishmentManager, PlayerIPLogRepository ipLogRepository,
                          TextUtil textUtil, SettingsConfig settings, Plugin plugin,
                          NetworkPlayerResolver networkPlayerResolver) {
        this.punishmentManager = punishmentManager;
        this.ipLogRepository = ipLogRepository;
        this.textUtil = textUtil;
        this.settings = settings;
        this.plugin = plugin;
        this.networkPlayerResolver = networkPlayerResolver;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !settings.isAltBanEnabled()) {
            return;
        }
        InetAddress address = event.getAddress();
        if (address == null) {
            Bukkit.getLogger().warning("[AltBan] login had no address, blocking it since IP protection is on");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Alt-ban check is down right now, try again in a moment.");
            return;
        }
        if (settings.isAltBanRequireForwarding() && address.equals(event.getRawAddress())) {
            Bukkit.getLogger().warning("[AltBan] proxy forwarding is required but the effective and raw addresses match, skipping IP check");
            return;
        }

        String actionValue = settings.getAltBanAction();
        String action = actionValue == null ? "kick" : actionValue.trim().toLowerCase(Locale.ROOT);
        if (!action.equals("kick") && !action.equals("ban")) {
            Bukkit.getLogger().warning("[AltBan] invalid features.alt-ban.action, using kick");
            action = "kick";
        }

        String ip = address.getHostAddress();
        UUID joiningUuid = event.getUniqueId();
        List<UUID> bannedOnIp;
        try {
            bannedOnIp = ipLogRepository.getBannedPlayersByIP(ip, joiningUuid)
                    .orTimeout(5, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            Bukkit.getLogger().warning("[AltBan] ban lookup failed for " + joiningUuid + ": " + e.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Can't check bans right now, try again in a sec.");
            return;
        }
        if (bannedOnIp.isEmpty()) {
            return;
        }

        UUID linkedUuid = bannedOnIp.get(0);
        String linkedName = resolveOfflineName(linkedUuid);
        String joiningName = event.getName() == null ? joiningUuid.toString() : event.getName();
        String reason = "Joined on an account linked to banned user: " + linkedName;
        try {
            ipLogRepository.logIP(joiningUuid, ip, System.currentTimeMillis());
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[AltBan] failed to record login IP for " + joiningUuid + ": " + e.getMessage());
        }

        String kickMessage = textUtil.prefixed("punishments.alt-ban-kick")
                .replace("%linked_name%", linkedName)
                .replace("%reason%", reason);
        if (action.equals("kick")) {
            Bukkit.getLogger().info("[AltBan] Blocking " + joiningName + " (" + joiningUuid + ") as possible alt of "
                    + linkedName + " on IP " + ip);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
            return;
        }

        long duration = settings.getAltBanDurationSeconds();
        if (duration <= 0) {
            Bukkit.getLogger().warning("[AltBan] ban-duration-seconds has to be positive, using kick");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
            return;
        }

        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> persistAltBan(joiningUuid, joiningName, linkedUuid, duration, reason));
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[AltBan] could not schedule alt-ban persistence for " + joiningUuid + ": " + e.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Alt-ban check is down right now, try again in a moment.");
            return;
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
    }

    private void persistAltBan(UUID joiningUuid, String joiningName, UUID linkedUuid,
                               long duration, String reason) {
        try {
            OfflinePlayer altPlayer = Bukkit.getOfflinePlayer(joiningUuid);
            punishmentManager.ban(Bukkit.getConsoleSender(), altPlayer, duration, reason, true)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            Bukkit.getLogger().warning("[AltBan] failed to persist alt ban for "
                                    + joiningUuid + ": " + error.getMessage());
                            return;
                        }
                        String linkedName = resolveOfflineName(linkedUuid);
                        punishmentManager.broadcastAltBan(joiningName, linkedName);
                    });
        } catch (RuntimeException e) {
            Bukkit.getLogger().warning("[AltBan] failed to start alt-ban persistence for "
                    + joiningUuid + ": " + e.getMessage());
        }
    }

    private String resolveOfflineName(UUID uuid) {
        if (networkPlayerResolver != null) return networkPlayerResolver.resolveName(uuid, uuid.toString());
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString() : name;
    }
}
