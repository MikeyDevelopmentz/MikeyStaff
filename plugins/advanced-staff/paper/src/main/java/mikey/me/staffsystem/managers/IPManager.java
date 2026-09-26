package mikey.me.staffsystem.managers;

import mikey.me.staffsystem.config.ConfigurationManager;
import mikey.me.staffsystem.config.SettingsConfig;
import mikey.me.staffsystem.database.models.PlayerIPLog;
import mikey.me.staffsystem.database.models.LoginLog;
import mikey.me.staffsystem.database.repositories.PlayerIPLogRepository;
import mikey.me.staffsystem.database.repositories.LoginLogRepository;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class IPManager {
    private final SettingsConfig settings;
    private final PlayerIPLogRepository repository;
    private final LoginLogRepository loginLogRepository;

    public IPManager(ConfigurationManager configurationManager, PlayerIPLogRepository repository,
            LoginLogRepository loginLogRepository) {
        this.settings = configurationManager.getSettings();
        this.repository = repository;
        this.loginLogRepository = loginLogRepository;
    }

    public void logPlayerIP(Player player) {
        logPlayerIP(player, null);
    }

    public void logPlayerIP(Player player, String knownIp) {
        if (!settings.isInspectLogIPs() && !settings.isAltBanEnabled()) {
            return;
        }
        // prefer the pre-login ip, behind a proxy player.getAddress() is the proxy itself
        String ip = (knownIp != null && !knownIp.isEmpty()) ? knownIp : getPlayerIP(player);
        if (ip == null || ip.isEmpty()) {
            return;
        }
        long timestamp = System.currentTimeMillis();
        repository.logIP(player.getUniqueId(), ip, timestamp);
        loginLogRepository.logLogin(player.getUniqueId(), ip, timestamp);
    }

    public CompletableFuture<List<PlayerIPLog>> getPlayerIPs(UUID playerUuid) {
        return repository.getIPsByPlayer(playerUuid);
    }

    public CompletableFuture<List<UUID>> getAltAccounts(UUID playerUuid) {
        return repository.getAltAccounts(playerUuid);
    }

    public CompletableFuture<List<LoginLog>> getLoginLogs(UUID playerUuid) {
        return loginLogRepository.getLoginLogs(playerUuid);
    }

    private String getPlayerIP(Player player) {
        if (player.getAddress() == null) {
            return null;
        }
        String fullAddress = player.getAddress().getAddress().getHostAddress();
        return fullAddress;
    }
}
