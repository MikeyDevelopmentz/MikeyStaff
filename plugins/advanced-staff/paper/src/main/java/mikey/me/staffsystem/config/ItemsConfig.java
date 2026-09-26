package mikey.me.staffsystem.config;

import org.bukkit.configuration.file.FileConfiguration;

public class ItemsConfig {

    private FileConfiguration config;

    public ItemsConfig(FileConfiguration config) {
        this.config = config;
    }

    void reload(FileConfiguration config) {
        this.config = config;
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public boolean isStaffModeEnabled() {
        return config.getBoolean("staffmode.enabled", true);
    }
}
