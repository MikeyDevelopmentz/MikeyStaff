package mikey.me.staffsystem.managers;

import mikey.me.staffsystem.database.models.PlayerNote;
import mikey.me.staffsystem.database.repositories.PlayerNoteRepository;
import mikey.me.staffsystem.utils.TimeUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class NotesManager {

    private final PlayerNoteRepository playerNoteRepository;
    private final TimeUtil timeUtil;
    private final Map<UUID, PlayerNote> latestByPlayer;

    public NotesManager(PlayerNoteRepository playerNoteRepository) {
        this.playerNoteRepository = playerNoteRepository;
        this.timeUtil = new TimeUtil();
        this.latestByPlayer = new ConcurrentHashMap<>();
    }

    public CompletableFuture<PlayerNote> addNote(Player staff, OfflinePlayer target, String text) {
        UUID staffId = staff.getUniqueId();
        UUID targetId = target.getUniqueId();
        long now = System.currentTimeMillis();
        PlayerNote note = new PlayerNote(0, targetId, staffId, text, now);
        return playerNoteRepository.insert(note).thenApply(saved -> {
            latestByPlayer.put(targetId, saved);
            return saved;
        });
    }

    public CompletableFuture<Boolean> removeNote(long id) {
        return playerNoteRepository.deleteById(id).thenApply(removed -> {
            latestByPlayer.values().removeIf(note -> note.getId() == id);
            return removed;
        });
    }

    public CompletableFuture<List<PlayerNote>> getNotes(UUID target) {
        return playerNoteRepository.findByPlayer(target).thenApply(list -> {
            if (!list.isEmpty()) {
                latestByPlayer.put(target, list.get(0));
            }
            return list;
        });
    }

    public PlayerNote getLatestNote(UUID target) {
        return latestByPlayer.get(target);
    }

    public void shutdown() {
        latestByPlayer.clear();
    }
}
