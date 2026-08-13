package dev.molecule.api.scheduler;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The Folia execution bridge (SPEC §1A.2).
 *
 * <p>Folia has no global main thread. The server is split into regions that tick
 * independently on their own threads, entities carry schedulers that follow them across
 * region boundaries, and server-wide state lives on a global scheduler. Any result
 * computed off-thread — a database read, an HTTP response, a plugin-owned tick — must be
 * marshalled back onto a legal thread before it touches the server.
 *
 * <p>This interface is that marshalling primitive. Every Molecule plugin routes
 * cross-thread work through it rather than reimplementing the pattern, and tasks are
 * described by <em>what state they touch</em> rather than by "sync" or "async" — the
 * sync/async vocabulary is Bukkit's and cannot express region ownership.
 *
 * <h2>Choosing a method</h2>
 *
 * <pre>
 * touches a specific player or entity  → runForPlayer / runForEntity
 * touches blocks or chunks             → runAtLocation
 * touches server-wide state            → runGlobal
 * touches only Molecule's own state    → runOffThread
 * </pre>
 *
 * <h2>The rule that is easiest to get wrong</h2>
 *
 * <p>Never key a region scheduler on an entity location sampled off-thread:
 *
 * <pre>{@code
 * // WRONG — location is sampled now, the task runs later, and the player may
 * // have crossed a region boundary in between.
 * scheduler.runAtLocation(player.getLocation(), () -> give(player, reward));
 *
 * // RIGHT — the entity scheduler re-resolves the owning region at dispatch time.
 * scheduler.runForPlayer(player, () -> give(player, reward));
 * }</pre>
 *
 * <p>This is the single most common Folia defect in shipped plugins (SPEC §73.5), and it
 * is caused upstream by scheduler abstractions that omit an entity-scheduler entry point.
 * {@link #runForPlayer} is therefore the primary method of this interface.
 *
 * <p>Implementations must be safe to call from any thread, including Netty threads.
 */
public interface MoleculeScheduler {

    /**
     * Runs {@code task} on the scheduler owning {@code player}, re-resolving that player's
     * current region at dispatch time.
     *
     * <p>Use this for anything touching a player: commands, chat, inventory, teleports,
     * sounds, velocity, and firing non-async events.
     *
     * @param player the player whose thread should run the task
     * @param task   the work to perform
     * @return a future completing with the outcome; never completes exceptionally for
     *     retirement, which is reported as a {@link TaskResult} instead
     */
    CompletableFuture<TaskResult> runForPlayer(Player player, Runnable task);

    /**
     * Runs {@code task} on the scheduler owning {@code entity}.
     *
     * @param entity the entity whose thread should run the task
     * @param task   the work to perform
     * @return a future completing with the outcome
     * @see #runForPlayer
     */
    CompletableFuture<TaskResult> runForEntity(Entity entity, Runnable task);

    /**
     * Runs {@code task} on the region thread owning {@code location}.
     *
     * <p>Only for work whose subject genuinely is a fixed position — block edits, chunk
     * reads, world state. If the subject is an entity that happens to be at a location,
     * use {@link #runForEntity} instead.
     *
     * @param location the position whose owning region should run the task
     * @param task     the work to perform
     * @return a future completing with the outcome
     */
    CompletableFuture<TaskResult> runAtLocation(Location location, Runnable task);

    /**
     * Runs {@code task} on the global region scheduler, for server-wide state such as
     * console commands and plugin lifecycle.
     *
     * @param task the work to perform
     * @return a future completing with the outcome
     */
    CompletableFuture<TaskResult> runGlobal(Runnable task);

    /**
     * Runs {@code task} on Molecule's own pool, off every server thread.
     *
     * <p>For database, HTTP, filesystem and resource-pack work, and for plugin-owned state
     * that touches no server object. Tasks here must never call into the server; hop back
     * through one of the other methods first.
     *
     * @param task the work to perform
     * @return a future completing when the task has run
     */
    CompletableFuture<Void> runOffThread(Runnable task);

    /**
     * Returns whether the calling thread owns {@code entity}.
     *
     * <p>Intended for assertions in debug builds (SPEC §64) so ownership violations fail
     * tests rather than degrading silently in production.
     *
     * @param entity the entity to check ownership of
     * @return {@code true} if the current thread may legally touch that entity
     */
    boolean isOnOwnerThread(Entity entity);

    /**
     * Returns whether the calling thread owns the region containing {@code location}.
     *
     * @param location the position to check ownership of
     * @return {@code true} if the current thread may legally touch that position
     */
    boolean isOnOwnerThread(Location location);
}
