package mikey.me.advancedstaff.velocity.listeners;

import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import mikey.me.advancedstaff.velocity.messaging.PluginProtocol;
import mikey.me.core.json.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class VersionTracker {

    private final Logger logger;
    private final String proxyVersion;
    private final String sharedSecret;
    private final Map<String, ServerProtocol> seen = new ConcurrentHashMap<>();

    public VersionTracker(Logger logger, String proxyVersion, String sharedSecret) {
        this.logger = logger;
        this.proxyVersion = proxyVersion;
        this.sharedSecret = sharedSecret;
    }

    public void handleHello(ServerConnection source, String json) {
        String serverName = source.getServerInfo().getName();
        int reportedProtocol = (int) JsonUtil.extractLong(json, "protocol", -1L);
        String pluginVersion = JsonUtil.extractString(json, "plugin_version");
        if (pluginVersion.isEmpty()) pluginVersion = "unknown";

        ServerProtocol prev = seen.put(serverName, new ServerProtocol(reportedProtocol, pluginVersion));
        boolean changed = prev == null
            || prev.protocol != reportedProtocol
            || !Objects.equals(prev.pluginVersion, pluginVersion);

        if (reportedProtocol != PluginProtocol.VERSION) {
            if (changed) {
                if (reportedProtocol < PluginProtocol.VERSION) {
                    logger.warning("[advancedstaff] Backend '" + serverName
                        + "' is OUTDATED, reports protocol v" + reportedProtocol
                        + " (plugin " + pluginVersion + "), proxy uses protocol v"
                        + PluginProtocol.VERSION + " (plugin " + proxyVersion
                        + "). Update the plugin on this backend.");
                } else {
                    logger.warning("[advancedstaff] Backend '" + serverName
                        + "' reports a NEWER protocol v" + reportedProtocol
                        + " (plugin " + pluginVersion + ") than this proxy (v"
                        + PluginProtocol.VERSION + ", plugin " + proxyVersion
                        + "). Update the proxy plugin.");
                }
            }
        } else if (changed) {
            logger.info("[advancedstaff] Backend '" + serverName
                + "' connected: protocol v" + reportedProtocol
                + ", plugin " + pluginVersion + " (in sync with proxy).");
        }

        sendProxyHello(source);
    }

    public void onServerConnection(String serverName) {
        // mark as unseen so the next hello logs status fresh
        seen.remove(serverName);
    }

    private void sendProxyHello(ServerConnection source) {
        String reply = "{\"protocol\":" + PluginProtocol.VERSION
            + ",\"plugin_version\":\"" + JsonUtil.escape(proxyVersion) + "\""
            + ",\"side\":\"proxy\"}";
        PluginProtocol.wrap(PluginProtocol.CH_HELLO, reply, sharedSecret).ifPresent(payload ->
            source.sendPluginMessage(
                MinecraftChannelIdentifier.from(PluginProtocol.CH_HELLO),
                payload.getBytes(StandardCharsets.UTF_8)));
    }

    private record ServerProtocol(int protocol, String pluginVersion) {}
}
