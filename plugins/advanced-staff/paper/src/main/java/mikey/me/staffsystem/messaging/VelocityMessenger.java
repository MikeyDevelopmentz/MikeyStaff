package mikey.me.staffsystem.messaging;

import mikey.me.core.communication.CommunicationApi;
import mikey.me.core.communication.CommunicationMode;
import mikey.me.core.communication.LocalCommunication;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.DatabaseManager;
import mikey.me.core.json.JsonUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class VelocityMessenger implements PluginMessageListener {

    public static final String CH_STAFFCHAT   = PluginProtocol.CH_STAFFCHAT;
    public static final String CH_KICK        = PluginProtocol.CH_KICK;
    public static final String CH_BAN_NOTIFY  = PluginProtocol.CH_BAN_NOTIFY;
    public static final String CH_MUTE        = PluginProtocol.CH_MUTE;
    public static final String CH_VANISH      = PluginProtocol.CH_VANISH;
    public static final String CH_FREEZE      = PluginProtocol.CH_FREEZE;
    public static final String CH_PLAYER_LIST = PluginProtocol.CH_PLAYER_LIST;
    public static final String CH_HELLO       = PluginProtocol.CH_HELLO;

    private static final int MAX_QUEUE_SIZE = 256;
    private static final Set<String> RELAYED_CHANNELS = Set.of(
        CH_STAFFCHAT, CH_KICK, CH_BAN_NOTIFY, CH_MUTE, CH_VANISH, CH_FREEZE);

    private final Plugin plugin;
    private final SettingsConfig settings;
    private final CommunicationMode mode;
    private final CommunicationApi localCommunication;
    private final Deque<Pending> queue = new ArrayDeque<>();
    // with 2+ proxies the same message can reach us through each of them
    private final Set<String> seenMessageIds = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 2048;
        }
    });
    private volatile DatabaseManager relayDatabase;
    private volatile int reportedProxyVersion = -1;
    private volatile long lastProxyMismatchLog = 0L;
    private volatile long lastQueueLossLog = 0L;

    private Consumer<String> onStaffChat          = msg -> {};
    private Consumer<String> onBanNotify          = msg -> {};
    private Consumer<String> onMuteSync           = msg -> {};
    private Consumer<String> onVanishSync         = msg -> {};
    private Consumer<String> onFreezeSync         = msg -> {};
    private Consumer<String> onPlayerListResponse = msg -> {};

    public VelocityMessenger(Plugin plugin, SettingsConfig settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.mode = settings.getCommunicationMode();
        this.localCommunication = new LocalCommunication();
        if (mode == CommunicationMode.PAPER) {
            localCommunication.start();
        }
    }

    public void register() {
        if (mode == CommunicationMode.PAPER) {
            return;
        }
        for (String ch : PluginProtocol.ALL_CHANNELS) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, ch);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, ch, this);
        }
    }

    public void unregister() {
        if (mode == CommunicationMode.PAPER) {
            localCommunication.close();
            return;
        }
        for (String ch : PluginProtocol.ALL_CHANNELS) {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, ch);
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, ch);
        }
    }

    public void onStaffChat(Consumer<String> handler)         {
        this.onStaffChat = handler;
        if (mode == CommunicationMode.PAPER) {
            localCommunication.subscribe(CH_STAFFCHAT, event -> handler.accept(event.payload()));
        }
    }
    public void onBanNotify(Consumer<String> handler)         { this.onBanNotify = handler; }
    public void onMuteSync(Consumer<String> handler)          { this.onMuteSync = handler; }
    public void onVanishSync(Consumer<String> handler)        { this.onVanishSync = handler; }
    public void onFreezeSync(Consumer<String> handler)        { this.onFreezeSync = handler; }
    public void onPlayerListResponse(Consumer<String> handler){ this.onPlayerListResponse = handler; }

    public void send(String channel, String json) {
        send(channel, json, null);
    }

    public void send(String channel, String json, UUID excludeUuid) {
        // pickCarrier walks the online player view, dont do that off-main
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> send(channel, json, excludeUuid));
            return;
        }
        if (mode == CommunicationMode.PAPER) {
            if (CH_STAFFCHAT.equals(channel)) {
                localCommunication.publish(channel, json);
            }
            return;
        }
        String secret = settings.getVelocitySharedSecret();
        if (secret == null || secret.isBlank()) {
            logQueueLoss(channel, "was not sent because network.shared-secret is blank");
            return;
        }
        Player carrier = pickCarrier(excludeUuid);
        // the excluded player is usually on their way out, the db relay is safer then
        if (carrier == null || (excludeUuid != null && carrier.getUniqueId().equals(excludeUuid))) {
            if (relayThroughDatabase(channel, json)) return;
        }
        if (carrier == null) {
            enqueue(channel, json, excludeUuid);
            return;
        }
        if (!sendPayload(carrier, channel, json, secret)) {
            enqueue(channel, json, excludeUuid);
        }
    }

    public void requestPlayerList() {
        if (mode == CommunicationMode.PAPER) {
            return;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        send(CH_PLAYER_LIST, "{}");
    }

    public void sendHello() {
        if (mode == CommunicationMode.PAPER) {
            return;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        String pluginVersion = plugin.getDescription() != null
            ? plugin.getDescription().getVersion() : "unknown";
        String json = "{\"protocol\":" + PluginProtocol.VERSION
            + ",\"plugin_version\":\"" + JsonUtil.escape(pluginVersion) + "\""
            + ",\"side\":\"paper\"}";
        send(CH_HELLO, json);
    }

    public void flush() {
        // queue.isEmpty() touches ArrayDeque internals and enqueue can run from the db pool, so lock it too
        if (mode == CommunicationMode.PAPER) return;
        synchronized (queue) {
            if (queue.isEmpty()) return;
        }
        String secret = settings.getVelocitySharedSecret();
        if (secret == null || secret.isBlank()) {
            logQueueLoss("queued", "remains queued because network.shared-secret is blank");
            return;
        }
        Player carrier = pickCarrier(null);
        if (carrier == null) return;
        synchronized (queue) {
            while (!queue.isEmpty()) {
                Pending p = queue.peek();
                Player chosen = (p.excludeUuid != null && carrier.getUniqueId().equals(p.excludeUuid))
                    ? pickCarrier(p.excludeUuid) : carrier;
                if (chosen == null) break;
                if (!sendPayload(chosen, p.channel, p.payload, secret)) {
                    break;
                }
                queue.poll();
            }
        }
    }

    public void setRelayDatabase(DatabaseManager databaseManager) {
        this.relayDatabase = databaseManager;
    }

    // no player to carry it, send it through the db relay
    private boolean relayThroughDatabase(String channel, String json) {
        DatabaseManager database = relayDatabase;
        if (database == null || !RELAYED_CHANNELS.contains(channel) || !json.startsWith("{")) return false;
        String rest = json.substring(1).trim();
        String withId = "{\"mid\":\"b-" + UUID.randomUUID() + "\"" + (rest.equals("}") ? "}" : "," + json.substring(1));
        try {
            database.getDbExecutor().execute(() -> relayInsert(database, channel, json, withId));
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }

    private void relayInsert(DatabaseManager database, String channel, String json, String withId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO network_messages (source_proxy, channel, payload, created_at) " +
                 "VALUES ('backend', ?, ?, ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000))")) {
            statement.setString(1, channel);
            statement.setString(2, withId);
            statement.executeUpdate();
        } catch (SQLException e) {
            logQueueLoss(channel, "could not be relayed through the database (" + e.getMessage() + "), queued instead");
            enqueue(channel, json, null);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        String envelope = new String(message, StandardCharsets.UTF_8);
        String json = PluginProtocol.unwrap(channel, envelope, settings.getVelocitySharedSecret()).orElse(null);
        if (json == null) return;
        String messageId = JsonUtil.extractString(json, "mid");
        if (!messageId.isEmpty()) {
            synchronized (seenMessageIds) {
                if (!seenMessageIds.add(messageId)) return;
            }
        }
        switch (channel) {
            case CH_STAFFCHAT    -> onStaffChat.accept(json);
            case CH_BAN_NOTIFY   -> onBanNotify.accept(json);
            case CH_MUTE         -> onMuteSync.accept(json);
            case CH_VANISH       -> onVanishSync.accept(json);
            case CH_FREEZE       -> onFreezeSync.accept(json);
            case CH_PLAYER_LIST  -> onPlayerListResponse.accept(json);
            case CH_HELLO        -> handleHello(json);
            default              -> {}
        }
    }

    private void handleHello(String json) {
        int proxyVersion = (int) JsonUtil.extractLong(json, "protocol", -1L);
        String proxyPluginVersion = JsonUtil.extractString(json, "plugin_version");
        reportedProxyVersion = proxyVersion;
        if (proxyVersion != PluginProtocol.VERSION) {
            long now = System.currentTimeMillis();
            if (now - lastProxyMismatchLog > 60_000L) {
                lastProxyMismatchLog = now;
                if (proxyVersion < PluginProtocol.VERSION) {
                    plugin.getLogger().warning("[advancedstaff] Velocity proxy reports protocol v"
                        + proxyVersion + " (plugin " + proxyPluginVersion
                        + ") but this Paper backend uses protocol v" + PluginProtocol.VERSION
                        + ". UPDATE THE PROXY.");
                } else {
                    plugin.getLogger().warning("[advancedstaff] Velocity proxy reports protocol v"
                        + proxyVersion + " (plugin " + proxyPluginVersion
                        + ") but this Paper backend uses protocol v" + PluginProtocol.VERSION
                        + ". UPDATE THIS BACKEND.");
                }
            }
        }
    }

    private Player pickCarrier(UUID excludeUuid) {
        Player fallback = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (excludeUuid != null && p.getUniqueId().equals(excludeUuid)) {
                if (fallback == null) fallback = p;
                continue;
            }
            return p;
        }
        return fallback;
    }

    private boolean sendPayload(Player carrier, String channel, String json, String secret) {
        var wrapped = PluginProtocol.wrap(channel, json, secret);
        if (wrapped.isEmpty()) {
            logQueueLoss(channel, "could not be signed");
            return false;
        }
        try {
            carrier.sendPluginMessage(plugin, channel, wrapped.get().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (RuntimeException e) {
            logQueueLoss(channel, "could not be sent: " + e.getMessage());
            return false;
        }
    }

    private void enqueue(String channel, String json, UUID excludeUuid) {
        synchronized (queue) {
            if (queue.size() >= MAX_QUEUE_SIZE) {
                logQueueLossNow(channel, "was rejected because the outbound queue is full");
                return;
            }
            queue.offer(new Pending(channel, json, excludeUuid));
        }
    }

    private void logQueueLoss(String channel, String reason) {
        long now = System.currentTimeMillis();
        if (now - lastQueueLossLog < 60_000L) return;
        logQueueLossAt(now, channel, reason);
    }

    private void logQueueLossNow(String channel, String reason) {
        logQueueLossAt(System.currentTimeMillis(), channel, reason);
    }

    private void logQueueLossAt(long now, String channel, String reason) {
        lastQueueLossLog = now;
        plugin.getLogger().warning("[advancedstaff] Velocity message on " + channel + " " + reason
                + ", remote moderation state may be stale.");
    }

    public boolean isProxyless() {
        return mode == CommunicationMode.PAPER;
    }

    public int getReportedProxyVersion() {
        return reportedProxyVersion;
    }

    private record Pending(String channel, String payload, UUID excludeUuid) {}
}
