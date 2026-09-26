package mikey.me.staffsystem.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class TabCompletion {

    private TabCompletion() {}

    static boolean allowed(CommandSender sender, String permission) {
        if (!(sender instanceof Player)) return false;
        return permission == null || permission.isEmpty() || sender.hasPermission(permission);
    }

    // only players the sender can actually see, so vanished staff stay hidden
    static List<String> visiblePlayers(Player viewer, String input) {
        List<String> result = new ArrayList<>();
        String lower = input.toLowerCase(Locale.ROOT);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(viewer) || !viewer.canSee(online)) continue;
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(lower)) result.add(online.getName());
        }
        return result;
    }

    static List<String> matching(Collection<String> options, String input) {
        List<String> result = new ArrayList<>();
        String lower = input.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option != null && option.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(option);
        }
        return result;
    }
}
