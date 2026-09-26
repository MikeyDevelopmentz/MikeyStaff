package mikey.me.staffsystem.config;

import mikey.me.core.communication.CommunicationMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SettingsConfigTest {

    @Test
    void defaultsToProxyModeAndSupportsPaperOnlyMode() {
        SettingsConfig settings = new SettingsConfig(new YamlConfiguration());
        assertEquals(CommunicationMode.PROXY, settings.getCommunicationMode());
        assertFalse(settings.isProxyless());

        YamlConfiguration proxyless = new YamlConfiguration();
        proxyless.set("network.mode", "paper");
        SettingsConfig paperSettings = new SettingsConfig(proxyless);
        assertEquals(CommunicationMode.PAPER, paperSettings.getCommunicationMode());
        assertEquals(true, paperSettings.isProxyless());
    }

    @Test
    void communicationModeRequiresRestart() {
        YamlConfiguration initial = new YamlConfiguration();
        SettingsConfig settings = new SettingsConfig(initial);
        YamlConfiguration reloaded = new YamlConfiguration();
        reloaded.set("network.mode", "paper");
        settings.reload(reloaded);

        assertEquals(CommunicationMode.PROXY, settings.getCommunicationMode());
    }

    @Test
    void altBanIsDisabledByDefault() {
        SettingsConfig settings = new SettingsConfig(new YamlConfiguration());

        assertFalse(settings.isAltBanEnabled());
        assertEquals("kick", settings.getAltBanAction());
        assertEquals(3600L, settings.getAltBanDurationSeconds());
        assertEquals(true, settings.isAltBanRequireForwarding());
    }

    @Test
    void altBanCanBeConfiguredWithATemporaryBan() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("features.alt-ban.enabled", true);
        config.set("features.alt-ban.action", "ban");
        config.set("features.alt-ban.ban-duration-seconds", 900L);

        SettingsConfig settings = new SettingsConfig(config);

        assertEquals(true, settings.isAltBanEnabled());
        assertEquals("ban", settings.getAltBanAction());
        assertEquals(900L, settings.getAltBanDurationSeconds());
    }
}
