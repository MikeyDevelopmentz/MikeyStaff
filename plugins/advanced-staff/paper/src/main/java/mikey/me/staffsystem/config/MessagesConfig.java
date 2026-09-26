package mikey.me.staffsystem.config;

import org.bukkit.configuration.file.FileConfiguration;

public class MessagesConfig {

    private FileConfiguration config;

    public MessagesConfig(FileConfiguration config) {
        this.config = config;
    }

    void reload(FileConfiguration config) {
        this.config = config;
    }

    public String getPrefix() {
        return config.getString("prefix", "");
    }

    public String getString(String path) {
        return config.getString(path);
    }
}
