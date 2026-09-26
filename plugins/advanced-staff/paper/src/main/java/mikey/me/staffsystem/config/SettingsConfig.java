package mikey.me.staffsystem.config;

import mikey.me.core.communication.CommunicationMode;
import org.bukkit.configuration.file.FileConfiguration;

public class SettingsConfig {

    private FileConfiguration config;
    private CommunicationMode communicationMode;

    public SettingsConfig(FileConfiguration config) {
        this.config = config;
        this.communicationMode = readCommunicationMode(config);
    }

    // swap in place on reload so anything holding this sees new values
    void reload(FileConfiguration config) {
        this.config = config;
    }

    public String getDbHost() {
        return config.getString("database.host", "localhost");
    }

    public int getDbPort() {
        return config.getInt("database.port", 3306);
    }

    public String getDbDatabase() {
        return config.getString("database.database", "spearmace");
    }

    public String getDbUser() {
        return config.getString("database.user", "root");
    }

    public String getDbPassword() {
        return config.getString("database.password", "");
    }

    public int getDbPoolSize() {
        return config.getInt("database.pool-size", 5);
    }

    public CommunicationMode getCommunicationMode() {
        return communicationMode;
    }

    private CommunicationMode readCommunicationMode(FileConfiguration source) {
        String mode = source.getString("network.mode", "proxy");
        if ("paper".equalsIgnoreCase(mode)) {
            return CommunicationMode.PAPER;
        }
        return CommunicationMode.PROXY;
    }

    public boolean isProxyless() {
        return getCommunicationMode() == CommunicationMode.PAPER;
    }

    public String getVelocitySharedSecret() {
        return config.getString("network.shared-secret", "");
    }

    public boolean isVanishSilentJoinLeave() {
        return config.getBoolean("features.vanish.silent-join-leave");
    }

    public boolean isStaffModeAutoVanish() {
        return config.getBoolean("features.staffmode.auto-vanish");
    }

    public boolean isStaffModeSaveInventory() {
        return config.getBoolean("features.staffmode.save-inventory");
    }

    public boolean isAltBanEnabled() {
        return config.getBoolean("features.alt-ban.enabled", false);
    }

    public String getAltBanAction() {
        return config.getString("features.alt-ban.action", "kick");
    }

    public long getAltBanDurationSeconds() {
        return config.getLong("features.alt-ban.ban-duration-seconds", 3600L);
    }

    public boolean isAltBanRequireForwarding() {
        return config.getBoolean("features.alt-ban.require-forwarding", true);
    }

    public int getFreezeDefaultDurationSeconds() {
        return config.getInt("features.freeze.default-duration-seconds");
    }

    public boolean isFreezeChatMessageEnabled() {
        return config.getBoolean("features.freeze.send-chat-message");
    }

    public boolean isFreezeCommandMessageEnabled() {
        return config.getBoolean("features.freeze.send-command-message", true);
    }

    public boolean isFreezeTitleEnabled() {
        return config.getBoolean("features.freeze.send-title");
    }

    public boolean isFreezeTitlePersistent() {
        return config.getBoolean("features.freeze.persistent-title", true);
    }

    public int getFreezeTitleFadeIn()  { return 10; }
    public int getFreezeTitleStay()    { return 70; }
    public int getFreezeTitleFadeOut() { return 20; }

    public int getFreezeTitleUpdateIntervalTicks() {
        return config.getInt("features.freeze.title-update-interval-ticks", 40);
    }

    public boolean isStaffChatEnabled() {
        return config.getBoolean("features.staffchat.enabled");
    }

    public boolean isTeleportSafetyChecks() {
        return config.getBoolean("features.teleport.safety-checks");
    }

    public boolean isInspectLogIPs() {
        return config.getBoolean("features.inspect.log-ips", true);
    }

    public int getReportCooldownSeconds() {
        return config.getInt("features.reports.cooldown-seconds", 30);
    }

    public int getReportSameTargetCooldownSeconds() {
        return config.getInt("features.reports.same-target-cooldown-seconds", 600);
    }

    public String getPermission(String path) {
        // fall back so a missing node cant NPE hasPermission
        return config.getString("permissions." + path, "staff." + path);
    }
}
