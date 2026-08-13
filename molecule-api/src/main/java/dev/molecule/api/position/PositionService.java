package dev.molecule.api.position;

import java.util.Optional;
import java.util.UUID;

/**
 * Publishes player positions that are safe to read from any thread (SPEC §1A.3).
 *
 * <p>Molecule Core keeps this up to date by recording each player's position on the thread
 * that owns them. Everything else — NPC and hologram visibility, particles, scoreboard,
 * spawn capacity, region checks — reads from here instead of calling
 * {@code player.getLocation()} off-thread.
 *
 * <p>Reads are lock-free and never block. A snapshot may be slightly stale, which is the
 * deliberate trade: a coherent value a few ticks old is more useful than a torn value read
 * during a concurrent write. Callers that must not act on stale data should check
 * {@link PositionSnapshot#ageMillis()}.
 */
public interface PositionService {

    /**
     * Returns the most recent snapshot for a player.
     *
     * @param player the player's unique id
     * @return the snapshot, or empty if the player is offline or has not been seen yet
     */
    Optional<PositionSnapshot> snapshot(UUID player);

    /**
     * Returns whether a player currently has a tracked position.
     *
     * @param player the player's unique id
     * @return {@code true} if a snapshot is available
     */
    boolean isTracked(UUID player);

    /**
     * Returns how many players are currently tracked.
     *
     * @return the number of live snapshots
     */
    int trackedCount();
}
