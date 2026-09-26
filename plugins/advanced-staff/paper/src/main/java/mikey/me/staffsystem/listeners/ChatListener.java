package mikey.me.staffsystem.listeners;

import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.FreezeManager;
import mikey.me.staffsystem.managers.PunishmentManager;
import mikey.me.staffsystem.managers.StaffChatManager;
import mikey.me.staffsystem.utils.TextUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;

public class ChatListener implements Listener {

    private final FreezeManager freezeManager;
    private final StaffChatManager staffChatManager;
    private final TextUtil textUtil;
    private final SettingsConfig settings;
    private final PunishmentManager punishmentManager;
    private final Plugin plugin;

    public ChatListener(FreezeManager freezeManager, StaffChatManager staffChatManager, TextUtil textUtil,
                         SettingsConfig settings, PunishmentManager punishmentManager, Plugin plugin) {
        this.freezeManager = freezeManager;
        this.staffChatManager = staffChatManager;
        this.textUtil = textUtil;
        this.settings = settings;
        this.punishmentManager = punishmentManager;
        this.plugin = plugin;
    }

    @EventHandler
    public void onPaperChat(AsyncChatEvent event) {
        if (event.isCancelled()) return;
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        handleChat(event.getPlayer(), message, () -> event.setCancelled(true));
    }

    private void handleChat(Player player, String message, Runnable cancel) {
        UUID uuid = player.getUniqueId();
        if (freezeManager.isFrozen(uuid)) {
            cancel.run();
            if (settings.isFreezeChatMessageEnabled()) {
                sendMessage(player, textUtil.prefixed("freeze.chat-blocked"));
            }
            return;
        }
        if (punishmentManager.isMutedCached(uuid)) {
            cancel.run();
            sendMessage(player, textUtil.prefixed("punishments.muted-chat"));
            return;
        }
        if (staffChatManager.isToggled(uuid)) {
            cancel.run();
            if (!message.isEmpty()) {
                staffChatManager.sendStaffMessage(player, message);
            }
        }
    }

    private void sendMessage(Player player, String message) {
        if (Bukkit.isPrimaryThread()) {
            player.sendMessage(message);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                if (player.isOnline()) player.sendMessage(message);
            });
        }
    }
}
