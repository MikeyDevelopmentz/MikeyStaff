package mikey.me.staffsystem.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public class PunishmentPresetsConfig {

    private FileConfiguration config;

    public PunishmentPresetsConfig(FileConfiguration config) {
        this.config = config;
    }

    void reload(FileConfiguration config) {
        this.config = config;
    }

    public List<String> getTimePresets() {
        return config.getStringList("presets.times");
    }

    public List<String> getReasonPresets() {
        return config.getStringList("presets.reasons");
    }
}
