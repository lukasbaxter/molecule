package dev.molecule.core.config;

import dev.molecule.api.audit.AuditActor;
import dev.molecule.api.audit.AuditEntry;
import dev.molecule.api.audit.AuditRecord;
import dev.molecule.api.audit.AuditService;
import dev.molecule.api.audit.ChangeSource;
import dev.molecule.api.config.ConfigKey;
import dev.molecule.api.config.ConfigService;
import dev.molecule.api.database.DatabaseService;
import dev.molecule.core.database.CoreSchema;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MariaDB-backed live configuration (SPEC §4, §59, §60).
 *
 * <p>Values are cached in memory so reads never block, and the cache is only ever updated
 * after a successful database write — a listener therefore cannot observe a value that
 * failed to persist.
 *
 * <h2>Known limitation</h2>
 *
 * <p>Changes made by <em>this</em> server propagate immediately. Changes made directly in
 * the database, or by another server sharing it, are picked up on {@link #reload()} rather
 * than automatically. Molecule does not yet poll or subscribe for external changes, so
 * this is stated rather than hidden — SPEC §60 requires that a limitation be documented
 * instead of faked.
 */
public final class DatabaseConfigService implements ConfigService {

    private final DatabaseService database;
    private final AuditService audit;
    private final Logger logger;

    /** Registered keys, so {@link #reload()} knows what to re-read and how to parse it. */
    private final Map<String, ConfigKey<?>> registered = new ConcurrentHashMap<>();

    /** Parsed current values. Read from region threads, so never guarded by a lock. */
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    private final Map<String, List<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    /**
     * Creates the service.
     *
     * @param database the shared database service
     * @param audit    where changes are recorded
     * @param logger   where problems are reported
     */
    public DatabaseConfigService(DatabaseService database, AuditService audit, Logger logger) {
        this.database = database;
        this.audit = audit;
        this.logger = logger;
    }

    @Override
    public <T> void register(ConfigKey<T> key) {
        registered.put(identity(key), key);
    }

    @Override
    public <T> T get(ConfigKey<T> key) {
        Object cached = values.get(identity(key));
        if (cached == null) {
            return key.defaultValue();
        }
        // Safe: only put() writes here, and it always stores a value parsed by this key.
        @SuppressWarnings("unchecked")
        T typed = (T) cached;
        return typed;
    }

    @Override
    public <T> CompletableFuture<Long> set(
            ConfigKey<T> key, T value, AuditActor actor, ChangeSource source) {

        final T validated;
        try {
            validated = key.validate(value);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }

        String serialised = key.serialise(validated);
        String previous = serialisedCurrent(key);

        // Persist first. Only once the write succeeded do the cache and listeners move,
        // so an observer can never see a value the database rejected.
        return database
                .update(
                        key.namespace(),
                        "INSERT INTO "
                                + CoreSchema.NAMESPACE.table("config")
                                + " (namespace, path, value) VALUES (?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE value = VALUES(value)",
                        statement -> {
                            statement.setString(1, key.namespace().prefix());
                            statement.setString(2, key.path());
                            statement.setString(3, serialised);
                        })
                .thenCompose(
                        ignored ->
                                audit.record(
                                        AuditRecord.configChange(
                                                actor,
                                                source,
                                                key.namespace().prefix(),
                                                key.path(),
                                                previous,
                                                serialised)))
                .thenApply(
                        revision -> {
                            apply(key, validated);
                            return revision;
                        });
    }

    @Override
    public CompletableFuture<Long> undo(long revision, AuditActor actor, ChangeSource source) {
        return audit.find(revision)
                .thenCompose(
                        found -> {
                            AuditEntry entry =
                                    found.orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "No audit revision " + revision));
                            return undo(entry, actor, source);
                        });
    }

    private CompletableFuture<Long> undo(AuditEntry entry, AuditActor actor, ChangeSource source) {
        if (!"config.set".equals(entry.operation()) || entry.target().isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Revision " + entry.revision() + " is not a configuration change"));
        }

        ConfigKey<?> key = registered.get(entry.namespace() + entry.target().orElseThrow());
        if (key == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Revision "
                                    + entry.revision()
                                    + " refers to "
                                    + entry.namespace()
                                    + entry.target().orElseThrow()
                                    + ", which no installed plugin declares. Install the plugin"
                                    + " that owns it before undoing this change."));
        }

        // Restoring an unset value means restoring the declared default: the setting had
        // no stored row before, and the default is what the plugin was using.
        String restore = entry.oldValue().orElseGet(() -> serialisedDefault(key));
        return applySerialised(key, restore, actor, source, entry.revision());
    }

    /** Bridges the wildcard from the audit lookup back to a concrete key type. */
    private <T> CompletableFuture<Long> applySerialised(
            ConfigKey<T> key,
            String serialised,
            AuditActor actor,
            ChangeSource source,
            long undoneRevision) {

        final T value;
        try {
            value = key.parse(serialised);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }

        String previous = serialisedCurrent(key);
        return database
                .update(
                        key.namespace(),
                        "INSERT INTO "
                                + CoreSchema.NAMESPACE.table("config")
                                + " (namespace, path, value) VALUES (?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE value = VALUES(value)",
                        statement -> {
                            statement.setString(1, key.namespace().prefix());
                            statement.setString(2, key.path());
                            statement.setString(3, serialised);
                        })
                .thenCompose(
                        ignored ->
                                audit.record(
                                        AuditRecord.configChange(
                                                        actor,
                                                        source,
                                                        key.namespace().prefix(),
                                                        key.path(),
                                                        previous,
                                                        serialised)
                                                .undoing(undoneRevision)))
                .thenApply(
                        revision -> {
                            apply(key, value);
                            return revision;
                        });
    }

    @Override
    public <T> AutoCloseable watch(ConfigKey<T> key, Consumer<T> listener) {
        String identity = identity(key);
        listeners.computeIfAbsent(identity, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            List<Consumer<?>> registeredListeners = listeners.get(identity);
            if (registeredListeners != null) {
                registeredListeners.remove(listener);
            }
        };
    }

    @Override
    public CompletableFuture<Void> reload() {
        Collection<ConfigKey<?>> keys = new ArrayList<>(registered.values());
        return database
                .query(
                        CoreSchema.NAMESPACE,
                        "SELECT namespace, path, value FROM " + CoreSchema.NAMESPACE.table("config"),
                        DatabaseService.StatementBinder.NONE,
                        row ->
                                Map.entry(
                                        row.getString("namespace") + row.getString("path"),
                                        Optional.ofNullable(row.getString("value")).orElse("")))
                .thenAccept(
                        rows -> {
                            Map<String, String> stored = new java.util.HashMap<>();
                            for (Map.Entry<String, String> row : rows) {
                                stored.put(row.getKey(), row.getValue());
                            }
                            for (ConfigKey<?> key : keys) {
                                refresh(key, stored.get(identity(key)));
                            }
                        });
    }

    /**
     * Brings one key's cached value in line with what the database holds.
     *
     * <p>An unparseable stored value degrades that single setting to its default and logs,
     * rather than failing the whole reload — one bad row should not take the server's
     * configuration with it.
     */
    private <T> void refresh(ConfigKey<T> key, String stored) {
        if (stored == null) {
            return;
        }
        try {
            T parsed = key.parse(stored);
            if (!parsed.equals(get(key))) {
                apply(key, parsed);
            }
        } catch (IllegalArgumentException e) {
            logger.log(
                    Level.WARNING,
                    "Ignoring unusable stored value for " + key + "; using the default instead",
                    e);
            values.remove(identity(key));
        }
    }

    /** Updates the cache and notifies watchers. */
    private <T> void apply(ConfigKey<T> key, T value) {
        values.put(identity(key), value);
        List<Consumer<?>> watchers = listeners.get(identity(key));
        if (watchers == null) {
            return;
        }
        for (Consumer<?> watcher : watchers) {
            @SuppressWarnings("unchecked")
            Consumer<T> typed = (Consumer<T>) watcher;
            try {
                typed.accept(value);
            } catch (RuntimeException e) {
                // One misbehaving listener must not stop the others being told.
                logger.log(Level.WARNING, "Config listener for " + key + " threw", e);
            }
        }
    }

    private <T> String serialisedCurrent(ConfigKey<T> key) {
        return values.containsKey(identity(key)) ? key.serialise(get(key)) : null;
    }

    private static <T> String serialisedDefault(ConfigKey<T> key) {
        return key.serialise(key.defaultValue());
    }

    private static String identity(ConfigKey<?> key) {
        return key.namespace().prefix() + key.path();
    }
}
