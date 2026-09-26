package mikey.me.staffsystem.utils;

import me.clip.placeholderapi.PlaceholderAPI;
import mikey.me.staffsystem.config.MessagesConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.util.Map;

public class TextUtil {

    private final MessagesConfig messagesConfig;

    public TextUtil(MessagesConfig messagesConfig) {
        this.messagesConfig = messagesConfig;
    }

    public String color(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    public String prefixed(String path) {
        String prefix = messagesConfig.getPrefix();
        String message = messagesConfig.getString(path);
        if (message == null) {
            return "";
        }
        return color(message.replace("%prefix%", prefix == null ? "" : prefix));
    }

    public String format(String raw, Map<String, String> placeholders) {
        if (raw == null) {
            return "";
        }
        String prefix = messagesConfig.getPrefix();
        String withPrefix = raw.replace("%prefix%", prefix == null ? "" : prefix);
        String result = withPrefix;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return color(result);
    }

    public String papi(org.bukkit.OfflinePlayer context, String path) {
        return papi(context, path, Map.of());
    }

    // placeholders get filled before papi so they work without it too
    public String papi(org.bukkit.OfflinePlayer context, String path, Map<String, String> placeholders) {
        String message = messagesConfig.getString(path);
        if (message == null) {
            return "";
        }
        String withPrefix = message.replace("%prefix%", messagesConfig.getPrefix() == null ? "" : messagesConfig.getPrefix());
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            withPrefix = withPrefix.replace(entry.getKey(), entry.getValue());
        }
        if (context != null && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            withPrefix = PlaceholderAPI.setPlaceholders(context, withPrefix);
        }
        return color(withPrefix);
    }
}
