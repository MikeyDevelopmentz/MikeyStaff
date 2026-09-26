package mikey.me.staffsystem.managers;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.messaging.VelocityMessenger;
import mikey.me.staffsystem.utils.JsonUtil;
import mikey.me.staffsystem.utils.SchedulerProvider;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StaffChatManager {

    private final SettingsConfig settings;
    private final TextUtil textUtil;
    private final VelocityMessenger velocityMessenger;
    private final SchedulerProvider schedulerProvider;
    private final Map<UUID, Boolean> toggled;

    public StaffChatManager(ConfigurationManager configurationManager, TextUtil textUtil,
                            VelocityMessenger velocityMessenger, SchedulerProvider schedulerProvider) {
        this.settings = configurationManager.getSettings();
        this.textUtil = textUtil;
        this.velocityMessenger = velocityMessenger;
        this.schedulerProvider = schedulerProvider;
        this.toggled = new ConcurrentHashMap<>();
        velocityMessenger.onStaffChat(this::deliverLocally);
    }

    public boolean isToggled(UUID uuid) {
        return settings.isStaffChatEnabled() && toggled.getOrDefault(uuid, false);
    }

    public boolean toggle(Player player) {
        if (!settings.isStaffChatEnabled()) {
            toggled.remove(player.getUniqueId());
            return false;
        }
        UUID uuid = player.getUniqueId();
        boolean newValue = !isToggled(uuid);
        toggled.put(uuid, newValue);
        return newValue;
    }

    public void sendStaffMessage(Player sender, String message) {
        if (!settings.isStaffChatEnabled()) {
            return;
        }
        // papi isnt safe off the main thread
        if (Bukkit.isPrimaryThread()) {
            renderAndSend(sender, message);
        } else {
            schedulerProvider.runSync(() -> renderAndSend(sender, message));
        }
    }

    private void renderAndSend(Player sender, String message) {
        String format = textUtil.papi(sender, "staffchat.format", Map.of("%staff_target_name%", sender.getName()));
        String rendered = format.replace("%message%", message);
        String payload = "{\"message\":\"" + JsonUtil.escape(rendered) + "\"}";
        velocityMessenger.send(VelocityMessenger.CH_STAFFCHAT, payload);
    }

    private void deliverLocally(String payload) {
        // network notices (ban broadcast, freeze logout) bring their own permission
        boolean notice = JsonUtil.hasKey(payload, "permission");
        if (!notice && !settings.isStaffChatEnabled()) {
            return;
        }
        String rendered = JsonUtil.extractString(payload, "message");
        if (rendered.isBlank()) {
            return;
        }
        String permission = notice ? JsonUtil.extractString(payload, "permission") : settings.getPermission("staffchat");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if ((notice && permission.isEmpty()) || online.hasPermission(permission)) {
                online.sendMessage(rendered);
            }
        }
    }

    public void handleQuit(UUID uuid) {
        toggled.remove(uuid);
    }

    public void shutdown() {
        toggled.clear();
    }
}
