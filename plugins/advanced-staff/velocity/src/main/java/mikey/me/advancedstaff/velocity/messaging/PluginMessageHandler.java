package mikey.me.advancedstaff.velocity.messaging;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import mikey.me.advancedstaff.velocity.database.VelocityDatabaseManager;
import mikey.me.advancedstaff.velocity.listeners.NetworkPlayerTracker;
import mikey.me.advancedstaff.velocity.listeners.VersionTracker;
import mikey.me.advancedstaff.velocity.util.JsonUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public class PluginMessageHandler {

    private final ProxyServer server;
    private final VelocityDatabaseManager databaseManager;
    private final NetworkPlayerTracker tracker;
    private final VersionTracker versionTracker;
    private final Logger logger;
    private final String sharedSecret;
    private final NetworkRelay relay;

    public PluginMessageHandler(ProxyServer server, VelocityDatabaseManager databaseManager,
                                 NetworkPlayerTracker tracker, VersionTracker versionTracker, Logger logger,
                                 String sharedSecret, NetworkRelay relay) {
        this.server = server;
        this.databaseManager = databaseManager;
        this.tracker = tracker;
        this.versionTracker = versionTracker;
        this.logger = logger;
        this.sharedSecret = sharedSecret;
        this.relay = relay;
        relay.onReceive((channel, json) -> dispatch(channel, json, null, true));
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        String channel = event.getIdentifier().getId();
        // only our channels, and clients never get to inject on them
        if (!PluginProtocol.ALL_CHANNELS.contains(channel)) {
            return;
        }
        if (!(event.getSource() instanceof ServerConnection source)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());
        String envelope = new String(event.getData(), StandardCharsets.UTF_8);
        String json = PluginProtocol.unwrap(channel, envelope, sharedSecret).orElse(null);
        if (json == null) return;
        if (NetworkRelay.RELAYED_CHANNELS.contains(channel)) {
            json = relay.withMessageId(json);
        }

        dispatch(channel, json, source, false);

        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    private void dispatch(String channel, String json, ServerConnection source, boolean relayed) {
        switch (channel) {
            case PluginProtocol.CH_HELLO        -> { if (source != null) versionTracker.handleHello(source, json); }
            case PluginProtocol.CH_STAFFCHAT    -> handleStaffChat(json, relayed);
            case PluginProtocol.CH_KICK         -> handleKick(json, relayed);
            case PluginProtocol.CH_BAN_NOTIFY   -> handleBanNotify(json, relayed);
            case PluginProtocol.CH_MUTE         -> handleMuteSync(json, relayed);
            case PluginProtocol.CH_VANISH       -> handleVanishSync(json, relayed);
            case PluginProtocol.CH_FREEZE       -> handleFreezeSync(json, relayed);
            case PluginProtocol.CH_PLAYER_LIST  -> { if (source != null) handlePlayerListRequest(source); }
            default -> {}
        }
    }

    private void handleStaffChat(String json, boolean relayed) {
        broadcast(PluginProtocol.CH_STAFFCHAT, json, relayed);
    }

    private void broadcast(String channel, String json, boolean relayed) {
        PluginProtocol.wrap(channel, json, sharedSecret).ifPresentOrElse(payload -> {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            for (RegisteredServer s : server.getAllServers()) {
                s.sendPluginMessage(MinecraftChannelIdentifier.from(channel), data);
            }
        }, () -> logger.warning("Failed to sign moderation broadcast on " + channel
                + ", remote server state may be stale."));
        if (!relayed) relay.publish(channel, json);
    }

    private void handleKick(String json, boolean relayed) {
        String uuidStr = JsonUtil.extractString(json, "target_uuid");
        String reason = JsonUtil.extractString(json, "reason");
        if (uuidStr.isEmpty()) return;
        try {
            UUID uuid = UUID.fromString(uuidStr);
            Optional<Player> player = server.getPlayer(uuid);
            // paper already converted its color codes to section signs
            Component component = LegacyComponentSerializer.legacySection().deserialize(reason);
            player.ifPresent(p -> p.disconnect(component));
            if (player.isEmpty() && !relayed) relay.publish(PluginProtocol.CH_KICK, json);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void handleBanNotify(String json, boolean relayed) {
        String uuidStr = JsonUtil.extractString(json, "target_uuid");
        if (uuidStr.isEmpty() || !JsonUtil.hasKey(json, "banned")) return;
        boolean banned = JsonUtil.extractBool(json, "banned", false);
        try {
            UUID uuid = UUID.fromString(uuidStr);
            tracker.setBan(uuid, banned);
            broadcast(PluginProtocol.CH_BAN_NOTIFY, json, relayed);
            if (!banned) return;
            BanQueryResult result = queryActiveBan(uuid);
            if (result.failed()) {
                server.getPlayer(uuid).ifPresent(player -> player.disconnect(
                        LegacyComponentSerializer.legacyAmpersand().deserialize(
                                "&cCan't check bans right now, try again in a sec.")));
                return;
            }
            if (result.message() != null) {
                Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(result.message());
                server.getPlayer(uuid).ifPresent(player -> player.disconnect(component));
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void handleMuteSync(String json, boolean relayed) {
        String uuidStr = JsonUtil.extractString(json, "target_uuid");
        if (uuidStr.isEmpty() || !JsonUtil.hasKey(json, "muted")) return;
        boolean mute = JsonUtil.extractBool(json, "muted", false);
        try {
            tracker.setMute(UUID.fromString(uuidStr), mute);
            broadcast(PluginProtocol.CH_MUTE, json, relayed);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void handleVanishSync(String json, boolean relayed) {
        String uuidStr = JsonUtil.extractString(json, "target_uuid");
        if (uuidStr.isEmpty() || !JsonUtil.hasKey(json, "vanished")) return;
        boolean vanish = JsonUtil.extractBool(json, "vanished", false);
        try {
            tracker.setVanish(UUID.fromString(uuidStr), vanish);
            broadcast(PluginProtocol.CH_VANISH, json, relayed);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void handleFreezeSync(String json, boolean relayed) {
        String uuidStr = JsonUtil.extractString(json, "target_uuid");
        if (uuidStr.isEmpty() || !JsonUtil.hasKey(json, "frozen")) return;
        boolean freeze = JsonUtil.extractBool(json, "frozen", false);
        try {
            tracker.setFreeze(UUID.fromString(uuidStr), freeze);
            broadcast(PluginProtocol.CH_FREEZE, json, relayed);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void handlePlayerListRequest(ServerConnection requester) {
        StringBuilder sb = new StringBuilder("{\"players\":[");
        boolean first = true;
        Set<String> listed = new HashSet<>();
        for (Player player : server.getAllPlayers()) {
            listed.add(player.getUniqueId().toString());
            if (!first) sb.append(",");
            first = false;
            String serverName = player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse("unknown");
            sb.append("{\"uuid\":\"").append(player.getUniqueId())
              .append("\",\"username\":\"").append(JsonUtil.escape(player.getUsername()))
              .append("\",\"server\":\"").append(JsonUtil.escape(serverName))
              .append("\"}");
        }
        for (VelocityDatabaseManager.RemotePlayer remote : loadRemotePlayers()) {
            if (remote.uuid() == null || remote.username() == null || !listed.add(remote.uuid())) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"uuid\":\"").append(JsonUtil.escape(remote.uuid()))
              .append("\",\"username\":\"").append(JsonUtil.escape(remote.username()))
              .append("\",\"server\":\"").append(JsonUtil.escape(remote.serverName() == null ? "unknown" : remote.serverName()))
              .append("\"}");
        }
        sb.append("]}");
        PluginProtocol.wrap(PluginProtocol.CH_PLAYER_LIST, sb.toString(), sharedSecret).ifPresent(payload ->
            requester.getServer().sendPluginMessage(
                MinecraftChannelIdentifier.from(PluginProtocol.CH_PLAYER_LIST),
                payload.getBytes(StandardCharsets.UTF_8)));
    }

    // other proxies players, for tab complete
    private List<VelocityDatabaseManager.RemotePlayer> loadRemotePlayers() {
        try {
            return databaseManager.loadRemotePlayers();
        } catch (SQLException e) {
            logger.warning("Failed to load players from other proxies: " + e.getMessage());
            return List.of();
        }
    }

    private BanQueryResult queryActiveBan(UUID uuid) {
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT reason, duration_seconds, start_time " +
                 "FROM punishments " +
                 "WHERE player_uuid = ? AND type = 'BAN' AND active = 1 " +
                 "ORDER BY id ASC")) {
            statement.setString(1, uuid.toString());
            BanRow activeBan = null;
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String reason = rs.getString("reason");
                    long duration = rs.getLong("duration_seconds");
                    long start = rs.getLong("start_time");
                    if (activeBan == null && VelocityDatabaseManager.isActive(duration, start, System.currentTimeMillis())) {
                        activeBan = new BanRow(reason, duration, start);
                    }
                }
            }
            if (activeBan == null) return new BanQueryResult(false, null);
            String expiry = activeBan.durationSeconds() <= 0 ? "Permanent"
                : formatRemaining(Math.max(0, activeBan.durationSeconds()
                    - (System.currentTimeMillis() - activeBan.startMillis()) / 1000L));
            return new BanQueryResult(false, "&cYou are banned.\n&7Reason: &f"
                + (activeBan.reason() == null || activeBan.reason().isBlank() ? "None" : activeBan.reason())
                + "\n&7Expires: &f" + expiry);
        } catch (SQLException e) {
            logger.warning("Failed to query active ban for disconnect: " + e.getMessage());
            return new BanQueryResult(true, null);
        }
    }

    private String formatRemaining(long seconds) {
        if (seconds <= 0) return "Expired";
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + secs + "s";
        return secs + "s";
    }

    private record BanQueryResult(boolean failed, String message) {}

    private record BanRow(String reason, long durationSeconds, long startMillis) {}
}
