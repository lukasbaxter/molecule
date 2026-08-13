package dev.molecule.api.position;

import java.util.UUID;

/**
 * An immutable copy of where a player was, taken on the thread that owned them (SPEC §1A.3).
 *
 * <p>Reading {@code player.getLocation()} from a thread that does not own the player reads
 * mutable position fields while the owning region thread may be writing them. It does not
 * crash — it yields stale or torn values — which is why the mistake ships widely and why
 * it shows up as visibility flicker rather than as an exception.
 *
 * <p>Molecule solves this once: snapshots are written only from a player's own owning
 * thread, published through a concurrent map, and read lock-free from anywhere. Every
 * subsystem doing distance or visibility maths — NPCs, holograms, particles, scoreboard,
 * spawn capacity, regions — consumes snapshots instead of live entity reads.
 *
 * @param player    the player this snapshot describes
 * @param worldId   unique id of the world the player was in
 * @param x         world x coordinate
 * @param y         world y coordinate
 * @param z         world z coordinate
 * @param yaw       horizontal rotation in degrees
 * @param pitch     vertical rotation in degrees
 * @param timestamp {@link System#nanoTime()} when the snapshot was taken
 */
public record PositionSnapshot(
        UUID player,
        UUID worldId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        long timestamp) {

    /**
     * Returns whether this snapshot and {@code other} describe positions in the same world.
     *
     * @param other the snapshot to compare against
     * @return {@code true} if both are in the same world
     */
    public boolean sameWorld(PositionSnapshot other) {
        return worldId.equals(other.worldId);
    }

    /**
     * Returns the squared distance to {@code other}, or {@link Double#POSITIVE_INFINITY}
     * if the two are in different worlds.
     *
     * <p>Squared, so callers comparing against a radius can avoid a square root in hot
     * visibility loops.
     *
     * @param other the snapshot to measure to
     * @return squared distance, or infinity across worlds
     */
    public double distanceSquared(PositionSnapshot other) {
        if (!sameWorld(other)) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    /**
     * Returns how long ago this snapshot was taken, in milliseconds.
     *
     * <p>Callers that must not act on stale data should check this rather than assuming
     * freshness; a player whose region is lagging updates less often.
     *
     * @return age in milliseconds
     */
    public long ageMillis() {
        return (System.nanoTime() - timestamp) / 1_000_000L;
    }
}
