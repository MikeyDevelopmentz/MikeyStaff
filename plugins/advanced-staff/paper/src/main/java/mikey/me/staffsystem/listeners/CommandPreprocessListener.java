package mikey.me.staffsystem.listeners;

import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.managers.FreezeManager;
import mikey.me.staffsystem.managers.PunishmentManager;
import mikey.me.staffsystem.utils.TextUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.UUID;

public class CommandPreprocessListener implements Listener {

    private final FreezeManager freezeManager;
    private final PunishmentManager punishmentManager;
    private final TextUtil textUtil;
    private final SettingsConfig settings;

    public CommandPreprocessListener(FreezeManager freezeManager, TextUtil textUtil, SettingsConfig settings) {
        this(freezeManager, null, textUtil, settings);
    }

    public CommandPreprocessListener(FreezeManager freezeManager, PunishmentManager punishmentManager,
            TextUtil textUtil, SettingsConfig settings) {
        this.freezeManager = freezeManager;
        this.punishmentManager = punishmentManager;
        this.textUtil = textUtil;
        this.settings = settings;
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (freezeManager.isFrozen(uuid)) {
            event.setCancelled(true);
            if (settings.isFreezeCommandMessageEnabled()) {
                player.sendMessage(textUtil.prefixed("freeze.command-blocked"));
            }
            return;
        }
        if (punishmentManager == null || !isVanillaChatCommand(event.getMessage())
                || !punishmentManager.isMutedCached(uuid)) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(textUtil.prefixed("punishments.muted-chat"));
    }

    private boolean isVanillaChatCommand(String message) {
        if (message == null) {
            return false;
        }
        String command = message.startsWith("/") ? message.substring(1) : message;
        int separator = command.indexOf(' ');
        if (separator >= 0) {
            command = command.substring(0, separator);
        }
        if (command.startsWith("minecraft:")) {
            command = command.substring("minecraft:".length());
        }
        return switch (command.toLowerCase(Locale.ROOT)) {
            case "me", "say", "tell", "w", "msg", "teammsg", "tm" -> true;
            default -> false;
        };
    }
}
