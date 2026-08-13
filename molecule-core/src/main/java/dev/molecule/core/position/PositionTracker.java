package dev.molecule.core.position;

import dev.molecule.api.position.PositionSnapshot;
import dev.molecule.api.position.PositionService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the latest {@link PositionSnapshot} for every online player (SPEC §1A.3).
 *
 * <p>Writes come from a single source per player — the thread that owns them — so the
 * values published here are coherent rather than torn. Reads are lock-free from any
 * thread.
 *
 * <p>This class deliberately knows nothing about Bukkit. {@link PositionListener} adapts
 * server events onto it, which keeps the concurrency-sensitive part testable without a
 * running server.
 */
public final class PositionTracker implements PositionService {

    private final Map<UUID, PositionSnapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * Records where a player is.
     *
     * <p>Must be called from the thread owning that player. Callers that are not already
     * on it should go through {@code MoleculeScheduler.runForPlayer} first — recording a
     * position read off-thread would defeat the purpose of the snapshot.
     *
     * @param player  the player's unique id
     * @param worldId the world they are in
     * @param x       world x coordinate
     * @param y       world y coordinate
     * @param z       world z coordinate
     * @param yaw     horizontal rotation in degrees
     * @param pitch   vertical rotation in degrees
     */
    public void update(
            UUID player, UUID worldId, double x, double y, double z, float yaw, float pitch) {
        snapshots.put(
                player,
                new PositionSnapshot(
                        player, worldId, x, y, z, yaw, pitch, System.nanoTime()));
    }

    /**
     * Drops a player's snapshot, on quit.
     *
     * <p>Stale entries would otherwise keep an offline player visible to distance checks,
     * and would leak memory across a long uptime.
     *
     * @param player the player's unique id
     */
    public void forget(UUID player) {
        snapshots.remove(player);
    }

    /** Drops every snapshot. Used on plugin disable. */
    public void clear() {
        snapshots.clear();
    }

    @Override
    public Optional<PositionSnapshot> snapshot(UUID player) {
        return Optional.ofNullable(snapshots.get(player));
    }

    @Override
    public boolean isTracked(UUID player) {
        return snapshots.containsKey(player);
    }

    @Override
    public int trackedCount() {
        return snapshots.size();
    }
}
