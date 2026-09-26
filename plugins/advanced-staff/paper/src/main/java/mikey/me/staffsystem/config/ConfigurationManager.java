package mikey.me.staffsystem.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;

public class ConfigurationManager {

    private final Plugin plugin;
    private SettingsConfig settingsConfig;
    private MessagesConfig messagesConfig;
    private ItemsConfig itemsConfig;
    private PunishmentPresetsConfig punishmentPresetsConfig;

    public ConfigurationManager(Plugin plugin) {
        this.plugin = plugin;
        ensureDefaults();
        loadAll();
    }

    public SettingsConfig getSettings() {
        return settingsConfig;
    }

    public MessagesConfig getMessages() {
        return messagesConfig;
    }

    public ItemsConfig getItems() {
        return itemsConfig;
    }

    public PunishmentPresetsConfig getPunishments() {
        return punishmentPresetsConfig;
    }

    public void reloadAll() {
        loadAll();
    }

    private void ensureDefaults() {
        ensureDefault("config/settings.yml");
        ensureDefault("config/messages.yml");
        ensureDefault("config/items.yml");
        ensureDefault("config/punishments.yml");
    }

    private void ensureDefault(String resourcePath) {
        File dataFolder = plugin.getDataFolder();
        File configFolder = new File(dataFolder, "config");
        if (!configFolder.exists()) {
            configFolder.mkdirs();
        }
        String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        File target = new File(configFolder, fileName);
        if (!target.exists()) {
            plugin.saveResource(resourcePath, false);
        }
    }

    private void loadAll() {
        if (settingsConfig == null) {
            settingsConfig = new SettingsConfig(load("settings.yml"));
            messagesConfig = new MessagesConfig(load("messages.yml"));
            itemsConfig = new ItemsConfig(load("items.yml"));
            punishmentPresetsConfig = new PunishmentPresetsConfig(load("punishments.yml"));
        } else {
            settingsConfig.reload(load("settings.yml"));
            messagesConfig.reload(load("messages.yml"));
            itemsConfig.reload(load("items.yml"));
            punishmentPresetsConfig.reload(load("punishments.yml"));
        }
    }

    private FileConfiguration load(String name) {
        File file = new File(new File(plugin.getDataFolder(), "config"), name);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        // fall back to the shipped defaults so old configs dont break after updates
        java.io.InputStream resource = plugin.getResource("config/" + name);
        if (resource != null) {
            try (java.io.InputStream input = resource;
                 java.io.InputStreamReader reader = new java.io.InputStreamReader(input,
                         java.nio.charset.StandardCharsets.UTF_8)) {
                config.setDefaults(YamlConfiguration.loadConfiguration(reader));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to read default configuration " + name, e);
            }
        }
        return config;
    }
}
