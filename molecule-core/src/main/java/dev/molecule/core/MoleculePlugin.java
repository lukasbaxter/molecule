package dev.molecule.core;

import dev.molecule.api.position.PositionService;
import dev.molecule.api.scheduler.MoleculeScheduler;
import dev.molecule.core.position.PositionListener;
import dev.molecule.core.position.PositionTracker;
import dev.molecule.core.scheduler.FoliaScheduler;
import io.papermc.paper.threadedregions.RegionizedServerInitEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Molecule Core (SPEC §3).
 *
 * <p>Core owns the infrastructure the rest of the ecosystem consumes. This class is the
 * bootstrap: it builds the services, wires them to the server, and tears them down in the
 * right order.
 *
 * <p>Startup runs in two stages, because Folia has no post-startup main-thread tick to
 * hang initialisation off. Services that touch nothing are constructed in
 * {@link #onEnable()}; anything that needs a running, regionized server waits for
 * {@link RegionizedServerInitEvent}.
 *
 * <p>Implemented so far: the execution bridge and the position snapshot service. The
 * remaining Phase 1 infrastructure — database, configuration, audit log, HTTP server,
 * registries — is not built yet.
 */
public final class MoleculePlugin extends JavaPlugin implements Listener {

    private ExecutorService offThreadPool;
    private FoliaScheduler scheduler;
    private PositionTracker positions;

    @Override
    public void onEnable() {
        offThreadPool = Executors.newFixedThreadPool(workerCount(), workerFactory());
        scheduler = new FoliaScheduler(this, offThreadPool);
        positions = new PositionTracker();

        getServer().getPluginManager().registerEvents(new PositionListener(positions), this);
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("Molecule Core enabled; waiting for server initialisation.");
    }

    /**
     * Completes startup once the regionized server is up.
     *
     * <p>This is the Folia equivalent of "the first tick has happened". Scheduling a
     * delayed task instead would be a guess, and would race a slow world load.
     *
     * @param event the server initialisation event
     */
    @EventHandler
    public void onServerInit(RegionizedServerInitEvent event) {
        seedPositions();
        getLogger().info("Molecule Core ready.");
    }

    @Override
    public void onDisable() {
        // Stop accepting server-thread work before tearing anything down, so in-flight
        // callers get SCHEDULER_RETIRED rather than an exception.
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (positions != null) {
            positions.clear();
        }
        if (offThreadPool != null) {
            shutdownPool();
        }
        getLogger().info("Molecule Core disabled.");
    }

    /**
     * Returns the execution bridge (SPEC §1A.2).
     *
     * @return the scheduler, or {@code null} before enable
     */
    public MoleculeScheduler scheduler() {
        return scheduler;
    }

    /**
     * Returns the position snapshot service (SPEC §1A.3).
     *
     * @return the position service, or {@code null} before enable
     */
    public PositionService positions() {
        return positions;
    }

    /**
     * Records positions for players who are already online.
     *
     * <p>Matters on a reload, and on a proxy where players can be connected before Core
     * finishes starting. Each read is dispatched to the thread owning that player rather
     * than performed here.
     */
    private void seedPositions() {
        for (Player player : getServer().getOnlinePlayers()) {
            scheduler.runForPlayer(
                    player,
                    () ->
                            positions.update(
                                    player.getUniqueId(),
                                    player.getWorld().getUID(),
                                    player.getLocation().getX(),
                                    player.getLocation().getY(),
                                    player.getLocation().getZ(),
                                    player.getLocation().getYaw(),
                                    player.getLocation().getPitch()));
        }
    }

    /** Drains the worker pool, without blocking shutdown indefinitely on a stuck task. */
    private void shutdownPool() {
        offThreadPool.shutdown();
        try {
            if (!offThreadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                getLogger().warning("Molecule worker pool did not drain in 10s; forcing shutdown.");
                offThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            offThreadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sizes the worker pool.
     *
     * <p>This pool exists for blocking work — database, HTTP, filesystem — so it is sized
     * for waiting rather than for computing, but capped so a large machine does not open
     * an unreasonable number of database connections later.
     */
    private static int workerCount() {
        return Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    }

    /** Names worker threads so they are identifiable in a thread dump or profiler. */
    private static ThreadFactory workerFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "molecule-worker-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
