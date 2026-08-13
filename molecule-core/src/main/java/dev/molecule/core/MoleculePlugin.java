package dev.molecule.core;

import dev.molecule.api.audit.AuditService;
import dev.molecule.api.config.ConfigService;
import dev.molecule.api.database.DatabaseService;
import dev.molecule.api.position.PositionService;
import dev.molecule.api.scheduler.MoleculeScheduler;
import dev.molecule.core.audit.DatabaseAuditService;
import dev.molecule.core.config.DatabaseConfigService;
import dev.molecule.core.database.CoreSchema;
import dev.molecule.core.database.DatabaseSettings;
import dev.molecule.core.database.DatabaseSettingsLoader;
import dev.molecule.core.database.HikariDatabaseService;
import dev.molecule.core.database.migration.MigrationRunner;
import dev.molecule.core.position.PositionListener;
import dev.molecule.core.position.PositionTracker;
import dev.molecule.core.scheduler.FoliaScheduler;
import io.papermc.paper.threadedregions.RegionizedServerInitEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
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
 * <p>Implemented so far: the execution bridge, position snapshots, the database pool with
 * versioned migrations, the audit log, and live configuration. The remaining Phase 1
 * infrastructure — HTTP server, REST/WebSocket API, web panel, action and variable
 * registries, resource packs — is not built yet.
 */
public final class MoleculePlugin extends JavaPlugin implements Listener {

    private ExecutorService offThreadPool;
    private FoliaScheduler scheduler;
    private PositionTracker positions;
    private HikariDatabaseService database;
    private DatabaseAuditService audit;
    private DatabaseConfigService config;

    /** Completes once the schema is migrated and the pool is usable, or fails if it is not. */
    private final CompletableFuture<Void> databaseReady = new CompletableFuture<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

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
        connectDatabase();
    }

    /**
     * Opens the pool and migrates the schema, off-thread.
     *
     * <p>Connecting and migrating both block, so neither may happen on a server thread.
     * Failure disables Core rather than leaving the ecosystem half-started against a
     * schema nobody has verified — plugins that depend on Core would otherwise fail one
     * confusing query at a time.
     */
    private void connectDatabase() {
        // Chained rather than joined. reload() submits its query to the same pool this
        // runs on, so blocking here to wait for it would occupy one worker while
        // depending on another — a deadlock once the pool is small or busy.
        scheduler
                .runOffThread(this::openDatabase)
                .thenCompose(ignored -> config.reload())
                .whenComplete(
                        (ignored, error) -> {
                            if (error == null) {
                                databaseReady.complete(null);
                                getLogger().info("Molecule Core ready.");
                            } else {
                                databaseReady.completeExceptionally(error);
                                getLogger().log(Level.SEVERE, "Molecule Core could not start", error);
                                // Disabling touches the plugin manager, which is server state.
                                scheduler.runGlobal(
                                        () -> getServer().getPluginManager().disablePlugin(this));
                            }
                        });
    }

    /** Opens the pool and brings the schema up to date. Blocking, so never on a server thread. */
    private void openDatabase() {
        DatabaseSettings settings =
                DatabaseSettingsLoader.load(getConfig().getConfigurationSection("database"));
        getLogger().info("Connecting to " + settings.redacted().jdbcUrl());

        database = new HikariDatabaseService(settings, offThreadPool);
        new MigrationRunner(database.dataSource(), getLogger())
                .migrate(CoreSchema.NAMESPACE, CoreSchema.migrations());

        audit = new DatabaseAuditService(database);
        config = new DatabaseConfigService(database, audit, getLogger());
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
        // Drain the pool before closing the datasource, so in-flight queries finish
        // against a live connection rather than a closed one.
        if (offThreadPool != null) {
            shutdownPool();
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("Molecule Core disabled.");
    }

    /**
     * Returns the database service (SPEC §5).
     *
     * @return the service, or {@code null} until {@link #whenDatabaseReady()} completes
     */
    public DatabaseService database() {
        return database;
    }

    /**
     * Returns the audit log (SPEC §6).
     *
     * @return the service, or {@code null} until {@link #whenDatabaseReady()} completes
     */
    public AuditService audit() {
        return audit;
    }

    /**
     * Returns live configuration (SPEC §59).
     *
     * @return the service, or {@code null} until {@link #whenDatabaseReady()} completes
     */
    public ConfigService config() {
        return config;
    }

    /**
     * Signals when the database is migrated and usable.
     *
     * <p>Other Molecule plugins should wait on this before their first query rather than
     * assuming Core finished first — Core connects off-thread, so enable order does not
     * guarantee readiness.
     *
     * @return a future completing on success, or failing if startup failed
     */
    public CompletableFuture<Void> whenDatabaseReady() {
        return databaseReady.copy();
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
