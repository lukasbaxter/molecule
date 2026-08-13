package dev.molecule.core.position;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Feeds {@link PositionTracker} from server events.
 *
 * <p>On Folia every one of these events fires on the thread owning the player, which is
 * exactly the guarantee the snapshot design needs — the position is copied by its owner
 * and published for everyone else to read.
 *
 * <p>Handlers are {@link EventPriority#MONITOR} and ignore cancelled events, so the
 * tracker records where a player actually ended up rather than where a cancelled move
 * would have put them.
 */
public final class PositionListener implements Listener {

    private final PositionTracker tracker;

    /**
     * Creates a listener feeding the given tracker.
     *
     * @param tracker the tracker to update
     */
    public PositionListener(PositionTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        record(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        record(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        record(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        record(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracker.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Copies a location into the tracker.
     *
     * <p>Safe because this runs on the owning thread: reading the location here is a
     * legal read, and only the immutable copy escapes to other threads.
     */
    private void record(Player player, Location location) {
        if (location.getWorld() == null) {
            return;
        }
        tracker.update(
                player.getUniqueId(),
                location.getWorld().getUID(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
    }
}
