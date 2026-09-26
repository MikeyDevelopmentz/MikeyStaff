package mikey.me.staffsystem.packets;

import org.bukkit.entity.Player;

public interface PacketService {

    void hidePlayer(Player target, Player viewer);

    void showPlayer(Player target, Player viewer);
}
