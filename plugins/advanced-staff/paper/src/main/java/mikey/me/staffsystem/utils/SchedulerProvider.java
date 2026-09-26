package mikey.me.staffsystem.utils;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

// global scheduler is the main thread on paper, works on folia too
public class SchedulerProvider {

    private final Plugin plugin;

    public SchedulerProvider(Plugin plugin) {
        this.plugin = plugin;
    }

    public ScheduledTask runSync(Runnable task) {
        return Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
    }

    public ScheduledTask runAsync(Runnable task) {
        return Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run());
    }

    public ScheduledTask runSyncLater(Runnable task, long delay) {
        return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduled -> task.run(), Math.max(1L, delay));
    }

    public ScheduledTask runAsyncLater(Runnable task, long delay) {
        return Bukkit.getAsyncScheduler().runDelayed(plugin, scheduled -> task.run(),
                Math.max(1L, delay) * 50L, TimeUnit.MILLISECONDS);
    }

    public ScheduledTask runSyncTimer(Runnable task, long delay, long period) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> task.run(),
                Math.max(1L, delay), Math.max(1L, period));
    }

    public ScheduledTask runAsyncTimer(Runnable task, long delay, long period) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduled -> task.run(),
                Math.max(1L, delay) * 50L, Math.max(1L, period) * 50L, TimeUnit.MILLISECONDS);
    }

    // runs on the players own thread, null if they left
    public ScheduledTask runFor(Entity entity, Runnable task) {
        return entity.getScheduler().run(plugin, scheduled -> task.run(), null);
    }

    // already on their thread, just run it
    public void runOn(Entity entity, Runnable task) {
        if (Bukkit.getServer().isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            runFor(entity, task);
        }
    }

    public ScheduledTask runForLater(Entity entity, Runnable task, long delay) {
        return entity.getScheduler().runDelayed(plugin, scheduled -> task.run(), null, Math.max(1L, delay));
    }

    public ScheduledTask runForTimer(Entity entity, Runnable task, long delay, long period) {
        return entity.getScheduler().runAtFixedRate(plugin, scheduled -> task.run(), null,
                Math.max(1L, delay), Math.max(1L, period));
    }
}
