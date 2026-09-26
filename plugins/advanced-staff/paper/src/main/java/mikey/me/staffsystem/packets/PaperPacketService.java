package mikey.me.staffsystem.packets;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class PaperPacketService implements PacketService {

    private final Plugin plugin;

    public PaperPacketService(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void hidePlayer(Player target, Player viewer) {
        viewer.hidePlayer(plugin, target);
    }

    @Override
    public void showPlayer(Player target, Player viewer) {
        viewer.showPlayer(plugin, target);
    }

    public void refreshVisibility(Player target) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(target)) {
                showPlayer(target, viewer);
            }
        }
    }
}
