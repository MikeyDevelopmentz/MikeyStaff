package mikey.me.advancedstaff.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import mikey.me.advancedstaff.velocity.database.VelocityDatabaseManager;
import mikey.me.advancedstaff.velocity.messaging.PluginProtocol;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class NetworkPlayerTracker {

    private final ProxyServer server;
    private final VelocityDatabaseManager databaseManager;
    private final Logger logger;
    private final String sharedSecret;

    private final Map<UUID, Boolean> vanishedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> frozenPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> mutedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> bannedPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> connectedPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicLong stateVersion = new AtomicLong();
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private final Object stateLock = new Object();

    public NetworkPlayerTracker(ProxyServer server, VelocityDatabaseManager databaseManager,
                                Logger logger, String sharedSecret) {
        this.server = server;
        this.databaseManager = databaseManager;
        this.logger = logger;
        this.sharedSecret = sharedSecret;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        if (!player.isActive()) {
            return;
        }
        String serverName = event.getServer().getServerInfo().getName();
        upsertNetworkPlayer(player.getUniqueId(), player.getUsername(), serverName);

        UUID uuid = player.getUniqueId();
        connectedPlayers.add(uuid);
    }

    // the players own connection is up now, so this reaches an empty server too
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ServerConnection connection = player.getCurrentServer().orElse(null);
        if (!player.isActive() || connection == null || !connectedPlayers.contains(uuid)) {
            return;
        }
        synchronized (stateLock) {
            ProxyStateCache.cachedState(vanishedPlayers, uuid).ifPresent(vanished ->
                    sendToServer(connection, PluginProtocol.CH_VANISH,
                            "{\"target_uuid\":\"" + uuid + "\",\"vanished\":" + vanished + "}"));
            ProxyStateCache.cachedState(frozenPlayers, uuid).ifPresent(frozen ->
                    sendToServer(connection, PluginProtocol.CH_FREEZE,
                            "{\"target_uuid\":\"" + uuid + "\",\"frozen\":" + frozen + "}"));
        }

        synchronized (stateLock) {
            Boolean knownMute = mutedPlayers.putIfAbsent(uuid, false);
            if (knownMute == null) {
                stateVersion.incrementAndGet();
            }
            boolean muted = knownMute != null && knownMute;
            sendToServer(connection, PluginProtocol.CH_MUTE,
                    "{\"target_uuid\":\"" + uuid + "\",\"muted\":" + muted + "}");
            Boolean knownBan = bannedPlayers.putIfAbsent(uuid, false);
            if (knownBan == null) {
                stateVersion.incrementAndGet();
            }
            boolean banned = knownBan != null && knownBan;
            sendToServer(connection, PluginProtocol.CH_BAN_NOTIFY,
                    "{\"target_uuid\":\"" + uuid + "\",\"banned\":" + banned + "}");
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        deleteNetworkPlayer(uuid);
        connectedPlayers.remove(uuid);
        synchronized (stateLock) {
            vanishedPlayers.remove(uuid);
            frozenPlayers.remove(uuid);
            mutedPlayers.remove(uuid);
            bannedPlayers.remove(uuid);
            stateVersion.incrementAndGet();
        }
    }

    public void enforceOnlinePlayers() {
        if (!refreshRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            List<UUID> onlineIds = new ArrayList<>();
            long versionAtStart;
            synchronized (stateLock) {
                versionAtStart = stateVersion.get();
            }
            for (Player player : server.getAllPlayers()) {
                if (!player.isActive()) {
                    continue;
                }
                onlineIds.add(player.getUniqueId());
            }
            if (onlineIds.isEmpty()) {
                return;
            }

            Map<UUID, VelocityDatabaseManager.ModerationState> states;
            try {
                states = databaseManager.loadModerationStates(onlineIds);
            } catch (Exception e) {
                logger.warning("Failed to refresh online moderation state: " + e.getMessage());
                disconnectAllForDatabaseFailure();
                return;
            }

            for (Player player : server.getAllPlayers()) {
                if (!player.isActive()) {
                    continue;
                }
                UUID uuid = player.getUniqueId();
                synchronized (stateLock) {
                    if (stateVersion.get() != versionAtStart) {
                        continue;
                    }
                    VelocityDatabaseManager.ModerationState state = states.getOrDefault(uuid,
                            new VelocityDatabaseManager.ModerationState(false, false));
                    boolean banned = state.banned();
                    Boolean previousBan = bannedPlayers.put(uuid, banned);
                    if (banned) {
                        if (previousBan == null || !previousBan) {
                            sendStateToCurrentServer(player, PluginProtocol.CH_BAN_NOTIFY, uuid, true);
                        }
                        player.disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(
                                "&cYou are banned. Try again later."));
                        continue;
                    }
                    if (previousBan == null || previousBan) {
                        sendStateToCurrentServer(player, PluginProtocol.CH_BAN_NOTIFY, uuid, false);
                    }

                    boolean muted = state.muted();
                    Boolean previousMute = mutedPlayers.put(uuid, muted);
                    if (previousMute == null || previousMute != muted) {
                        sendStateToCurrentServer(player, PluginProtocol.CH_MUTE, uuid, muted);
                    }
                }
            }
        } finally {
            refreshRunning.set(false);
        }
    }

    public void setVanish(UUID uuid, boolean vanish) {
        synchronized (stateLock) {
            if (!connectedPlayers.contains(uuid)) {
                return;
            }
            vanishedPlayers.put(uuid, vanish);
        }
    }

    public void setFreeze(UUID uuid, boolean freeze) {
        synchronized (stateLock) {
            if (!connectedPlayers.contains(uuid)) {
                return;
            }
            frozenPlayers.put(uuid, freeze);
        }
    }

    public void setMute(UUID uuid, boolean mute) {
        synchronized (stateLock) {
            if (!connectedPlayers.contains(uuid)) {
                return;
            }
            mutedPlayers.put(uuid, mute);
            stateVersion.incrementAndGet();
        }
    }

    public void setBan(UUID uuid, boolean ban) {
        synchronized (stateLock) {
            if (!connectedPlayers.contains(uuid)) {
                return;
            }
            bannedPlayers.put(uuid, ban);
            stateVersion.incrementAndGet();
        }
    }

    private void disconnectAllForDatabaseFailure() {
        for (Player player : server.getAllPlayers()) {
            if (player.isActive()) {
                player.disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&cCan't check bans right now, try again in a moment."));
            }
        }
    }

    private void sendStateToCurrentServer(Player player, String channel, UUID uuid, boolean state) {
        player.getCurrentServer().ifPresent(connection -> sendToServer(connection.getServer(), channel,
                "{\"target_uuid\":\"" + uuid + "\",\"" + stateKey(channel) + "\":" + state + "}"));
    }

    private String stateKey(String channel) {
        if (PluginProtocol.CH_MUTE.equals(channel)) {
            return "muted";
        }
        return "banned";
    }

    private void sendToServer(RegisteredServer server, String channel, String json) {
        PluginProtocol.wrap(channel, json, sharedSecret).ifPresent(payload ->
            server.sendPluginMessage(
                MinecraftChannelIdentifier.from(channel),
                payload.getBytes(StandardCharsets.UTF_8)));
    }

    private void sendToServer(ServerConnection connection, String channel, String json) {
        PluginProtocol.wrap(channel, json, sharedSecret).ifPresent(payload ->
            connection.sendPluginMessage(
                MinecraftChannelIdentifier.from(channel),
                payload.getBytes(StandardCharsets.UTF_8)));
    }

    public void resyncNetworkPlayers() {
        for (Player player : server.getAllPlayers()) {
            if (!player.isActive()) continue;
            player.getCurrentServer().ifPresent(connection -> upsertNetworkPlayer(
                    player.getUniqueId(), player.getUsername(), connection.getServerInfo().getName()));
        }
    }

    private void upsertNetworkPlayer(UUID uuid, String username, String serverName) {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO network_players (uuid, username, server_name, joined_at, proxy_id) VALUES (?, ?, ?, ?, ?) " +
                 "ON DUPLICATE KEY UPDATE username = VALUES(username), server_name = VALUES(server_name), " +
                 "proxy_id = VALUES(proxy_id)");
             // unlike network_players this one stays after disconnect
             PreparedStatement nameStatement = connection.prepareStatement(
                 "INSERT INTO player_names (uuid, username, last_seen) VALUES (?, ?, ?) " +
                 "ON DUPLICATE KEY UPDATE username = VALUES(username), last_seen = VALUES(last_seen)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, username);
            statement.setString(3, serverName);
            statement.setLong(4, System.currentTimeMillis());
            statement.setString(5, databaseManager.getProxyId());
            statement.executeUpdate();
            nameStatement.setString(1, uuid.toString());
            nameStatement.setString(2, username);
            nameStatement.setLong(3, System.currentTimeMillis());
            nameStatement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Failed to upsert network player: " + e.getMessage());
        }
    }

    private void deleteNetworkPlayer(UUID uuid) {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM network_players WHERE uuid = ? AND proxy_id = ?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, databaseManager.getProxyId());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Failed to delete network player: " + e.getMessage());
        }
    }
}
