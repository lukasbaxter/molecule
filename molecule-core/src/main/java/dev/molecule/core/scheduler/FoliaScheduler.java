package dev.molecule.core.scheduler;

import dev.molecule.api.scheduler.MoleculeScheduler;
import dev.molecule.api.scheduler.TaskResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

/**
 * The Folia implementation of {@link MoleculeScheduler} (SPEC §1A.2).
 *
 * <p>Each method maps onto the Folia scheduler that owns the relevant state, and every
 * method is safe to call from any thread — including Netty threads, which is the whole
 * point of having this indirection.
 *
 * <p>Nothing here caches a scheduler or a region. Entity-targeted work is handed to the
 * entity's own scheduler, which re-resolves the owning region at dispatch time; that is
 * what makes {@link #runForPlayer} correct for a player who crosses a region boundary
 * between scheduling and execution, and what makes a region scheduler keyed on a sampled
 * location wrong.
 */
public final class FoliaScheduler implements MoleculeScheduler {

    private final Plugin plugin;
    private final ExecutorService offThreadPool;
    private final Logger logger;

    /**
     * Marks the window during which the plugin is shutting down. Folia's schedulers reject
     * or drop work once a plugin is disabling; rather than let that surface as an
     * exception at every call site, callers get {@link TaskResult#SCHEDULER_RETIRED} and
     * can apply their retry policy.
     */
    private volatile boolean active = true;

    /**
     * Creates a scheduler bound to a plugin and its off-thread pool.
     *
     * @param plugin        the owning plugin, used for Folia task attribution
     * @param offThreadPool the pool backing {@link #runOffThread}
     */
    public FoliaScheduler(Plugin plugin, ExecutorService offThreadPool) {
        this.plugin = plugin;
        this.offThreadPool = offThreadPool;
        this.logger = plugin.getLogger();
    }

    @Override
    public CompletableFuture<TaskResult> runForPlayer(Player player, Runnable task) {
        return runForEntity(player, task);
    }

    @Override
    public CompletableFuture<TaskResult> runForEntity(Entity entity, Runnable task) {
        CompletableFuture<TaskResult> future = new CompletableFuture<>();
        if (!active) {
            future.complete(TaskResult.SCHEDULER_RETIRED);
            return future;
        }
        try {
            // A null return means the entity is already gone. Folia guarantees exactly
            // one of the two callbacks fires otherwise, so the future completes once.
            boolean scheduled =
                    entity.getScheduler()
                                    .run(
                                            plugin,
                                            ignored -> runAndComplete(future, task),
                                            () -> future.complete(TaskResult.ENTITY_RETIRED))
                            != null;
            if (!scheduled) {
                future.complete(TaskResult.ENTITY_RETIRED);
            }
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            // Thrown when the plugin is being disabled underneath us.
            future.complete(TaskResult.SCHEDULER_RETIRED);
        }
        return future;
    }

    @Override
    public CompletableFuture<TaskResult> runAtLocation(Location location, Runnable task) {
        CompletableFuture<TaskResult> future = new CompletableFuture<>();
        if (!active) {
            future.complete(TaskResult.SCHEDULER_RETIRED);
            return future;
        }
        try {
            Bukkit.getRegionScheduler()
                    .execute(plugin, location, () -> runAndComplete(future, task));
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            future.complete(TaskResult.SCHEDULER_RETIRED);
        }
        return future;
    }

    @Override
    public CompletableFuture<TaskResult> runGlobal(Runnable task) {
        CompletableFuture<TaskResult> future = new CompletableFuture<>();
        if (!active) {
            future.complete(TaskResult.SCHEDULER_RETIRED);
            return future;
        }
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> runAndComplete(future, task));
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            future.complete(TaskResult.SCHEDULER_RETIRED);
        }
        return future;
    }

    @Override
    public CompletableFuture<Void> runOffThread(Runnable task) {
        // Deliberately Molecule's own pool rather than Folia's async scheduler: this work
        // must keep running while the server is busy, and must not be attributed to a
        // region. See SPEC §1A.1, thread class 4.
        return CompletableFuture.runAsync(task, offThreadPool);
    }

    @Override
    public boolean isOnOwnerThread(Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    @Override
    public boolean isOnOwnerThread(Location location) {
        return Bukkit.isOwnedByCurrentRegion(location);
    }

    /**
     * Stops accepting new server-thread work.
     *
     * <p>Called from {@code onDisable}. Tasks already handed to Folia still run; new ones
     * report {@link TaskResult#SCHEDULER_RETIRED} rather than throwing.
     */
    public void shutdown() {
        active = false;
    }

    /**
     * Runs a task on a thread Folia has already chosen, and reports the outcome.
     *
     * <p>A task that throws completes the future exceptionally rather than leaving the
     * caller waiting forever, and is logged here because an exception on a region thread
     * is otherwise easy to lose.
     */
    private void runAndComplete(CompletableFuture<TaskResult> future, Runnable task) {
        try {
            task.run();
            future.complete(TaskResult.SUCCESS);
        } catch (RuntimeException | Error e) {
            logger.log(Level.SEVERE, "Scheduled Molecule task threw", e);
            future.completeExceptionally(e);
            throw e;
        }
    }
}
