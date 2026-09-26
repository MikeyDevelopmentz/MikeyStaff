package mikey.me.advancedstaff.velocity.listeners;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import mikey.me.advancedstaff.velocity.database.VelocityDatabaseManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

public class BanEnforcementListener {

    private final VelocityDatabaseManager databaseManager;
    private final Logger logger;
    private final boolean enforcementEnabled;

    public BanEnforcementListener(VelocityDatabaseManager databaseManager, Logger logger) {
        this(databaseManager, logger, true);
    }

    public BanEnforcementListener(VelocityDatabaseManager databaseManager, Logger logger,
                                   boolean enforcementEnabled) {
        this.databaseManager = databaseManager;
        this.logger = logger;
        this.enforcementEnabled = enforcementEnabled;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        if (!enforcementEnabled || databaseManager == null) {
            deny(event, "&cCan't check bans right now, try again in a sec.");
            return;
        }
        try (Connection connection = databaseManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT reason, duration_seconds, start_time " +
                 "FROM punishments " +
                 "WHERE player_uuid = ? AND type = 'BAN' AND active = 1 " +
                 "ORDER BY id ASC")) {
            statement.setString(1, player.getUniqueId().toString());
            BanRow activeBan = null;
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String reason = rs.getString("reason");
                    long duration = rs.getLong("duration_seconds");
                    if (rs.wasNull()) {
                        deny(event, "&cCan't check bans right now, try again in a sec.");
                        return;
                    }
                    long start = rs.getLong("start_time");
                    if (rs.wasNull()) {
                        deny(event, "&cCan't check bans right now, try again in a sec.");
                        return;
                    }
                    if (activeBan == null && VelocityDatabaseManager.isActive(duration, start, System.currentTimeMillis())) {
                        activeBan = new BanRow(reason, duration, start);
                    }
                }
            }
            if (activeBan == null) {
                if (!databaseManager.findOtherLiveProxy(player.getUniqueId()).isEmpty()) {
                    deny(event, "&cYou're already online on this network.");
                }
                return;
            }
            String expiry = activeBan.durationSeconds() <= 0 ? "Permanent"
                : formatRemaining(Math.max(0, activeBan.durationSeconds()
                    - (System.currentTimeMillis() - activeBan.startMillis()) / 1000L));
            String message = "&cYou are banned.\n&7Reason: &f"
                + (activeBan.reason() == null || activeBan.reason().isBlank() ? "None" : activeBan.reason())
                + "\n&7Expires: &f" + expiry;
            deny(event, message);
        } catch (SQLException e) {
            // fail closed, the whole point of this listener is ban enforcement
            logger.warning("Ban check failed for " + player.getUsername() + ": " + e.getMessage());
            deny(event, "&cCan't check bans right now, try again in a sec.");
        }
    }

    private void deny(LoginEvent event, String message) {
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(message);
        event.setResult(ResultedEvent.ComponentResult.denied(component));
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

    private record BanRow(String reason, long durationSeconds, long startMillis) {}
}
