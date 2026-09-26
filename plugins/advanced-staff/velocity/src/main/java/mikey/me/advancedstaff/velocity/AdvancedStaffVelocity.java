package mikey.me.advancedstaff.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import mikey.me.advancedstaff.velocity.database.VelocityDatabaseManager;
import mikey.me.advancedstaff.velocity.listeners.BanEnforcementListener;
import mikey.me.advancedstaff.velocity.listeners.NetworkPlayerTracker;
import mikey.me.advancedstaff.velocity.listeners.VersionTracker;
import mikey.me.advancedstaff.velocity.messaging.NetworkRelay;
import mikey.me.advancedstaff.velocity.messaging.PluginMessageHandler;
import mikey.me.advancedstaff.velocity.messaging.PluginProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Plugin(
    id = "advancedstaff",
    name = "Advanced-Staff-Velocity",
    version = "1.0-SNAPSHOT",
    description = "Network staff enforcement companion",
    authors = {"Mikey"}
)
public class AdvancedStaffVelocity {

    private static final String VERSION = "1.0-SNAPSHOT";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityDatabaseManager databaseManager;
    private String sharedSecret = "";
    private boolean listenersRegistered;
    private NetworkPlayerTracker networkPlayerTracker;
    private ScheduledTask onlineEnforcementTask;
    private ScheduledTask relayTask;
    private NetworkRelay networkRelay;

    @Inject
    public AdvancedStaffVelocity(ProxyServer server, Logger logger,
                                  @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            extractDefaultConfig();
        } catch (IOException e) {
            logger.severe("Failed to write default config: " + e.getMessage());
            registerFailClosed();
            return;
        }

        VelocityDatabaseManager manager = new VelocityDatabaseManager(logger);
        try {
            manager.initialize(dataDirectory.resolve("config.properties"));
        } catch (Exception e) {
            manager.shutdown();
            logger.severe("Failed to connect to MySQL: " + e.getMessage());
            registerFailClosed();
            return;
        }
        databaseManager = manager;
        sharedSecret = databaseManager.getSharedSecret();
        if (sharedSecret == null || sharedSecret.isBlank()) {
            logger.severe("network.shared-secret is blank, all proxy logins will be denied");
            registerFailClosed();
            return;
        }

        try {
            purgeStaleNetworkPlayers();
            NetworkRelay relay = new NetworkRelay(databaseManager, logger);
            try {
                relay.start();
            } catch (SQLException e) {
                logger.warning("Network relay could not start, other proxies won't see this one: " + e.getMessage());
            }

            for (String ch : PluginProtocol.ALL_CHANNELS) {
                server.getChannelRegistrar().register(MinecraftChannelIdentifier.from(ch));
            }

            VersionTracker versionTracker = new VersionTracker(logger, VERSION, sharedSecret);
            networkPlayerTracker = new NetworkPlayerTracker(server, databaseManager, logger, sharedSecret);
            PluginMessageHandler messageHandler = new PluginMessageHandler(
                    server, databaseManager, networkPlayerTracker, versionTracker, logger, sharedSecret, relay);
            relay.onHeartbeatRestored(networkPlayerTracker::resyncNetworkPlayers);

            server.getEventManager().register(this, new BanEnforcementListener(databaseManager, logger, true));
            server.getEventManager().register(this, networkPlayerTracker);
            server.getEventManager().register(this, messageHandler);
            onlineEnforcementTask = server.getScheduler().buildTask(this, networkPlayerTracker::enforceOnlinePlayers)
                    .repeat(databaseManager.getEnforcementIntervalSeconds(), TimeUnit.SECONDS)
                    .schedule();
            relayTask = server.getScheduler().buildTask(this, relay::poll)
                    .repeat(1, TimeUnit.SECONDS)
                    .schedule();
            networkRelay = relay;
            listenersRegistered = true;
        } catch (RuntimeException e) {
            logger.severe("Failed to initialize network enforcement: " + e.getMessage());
            registerFailClosed();
            return;
        }

        logger.info("Advanced-Staff-Velocity enabled. Protocol v" + PluginProtocol.VERSION
            + " (plugin " + VERSION + "), proxy-id " + databaseManager.getProxyId() + ".");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (onlineEnforcementTask != null) onlineEnforcementTask.cancel();
        if (relayTask != null) relayTask.cancel();
        if (networkRelay != null) networkRelay.stop();
        if (databaseManager != null) databaseManager.shutdown();
        logger.info("Advanced-Staff-Velocity disabled.");
    }

    private void extractDefaultConfig() throws IOException {
        Files.createDirectories(dataDirectory);
        Path configFile = dataDirectory.resolve("config.properties");
        if (!Files.exists(configFile)) {
            try (InputStream in = getClass().getResourceAsStream("/config.properties")) {
                if (in != null) Files.copy(in, configFile);
            }
        }
    }

    private void registerFailClosed() {
        if (listenersRegistered) return;
        server.getEventManager().register(this, new BanEnforcementListener(databaseManager, logger, false));
        listenersRegistered = true;
        logger.severe("startup failed, denying all logins until its fixed");
    }

    private void purgeStaleNetworkPlayers() {
        // only our own rows, the other proxies still have players online
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM network_players WHERE proxy_id = ? OR proxy_id = ''")) {
            statement.setString(1, databaseManager.getProxyId());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Failed to purge stale network_players on startup: " + e.getMessage());
        }
    }
}
