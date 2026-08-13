package dev.molecule.api.config;

import dev.molecule.api.audit.AuditActor;
import dev.molecule.api.audit.ChangeSource;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Live configuration, backed by MariaDB (SPEC §4, §59, §60).
 *
 * <p>The database is the source of truth. Changes flow panel → database → change event →
 * plugin, and take effect without a restart.
 *
 * <h2>Reads never block</h2>
 *
 * <p>{@link #get} answers from an in-memory cache and is safe to call from a region
 * thread, inside a tight loop, on every tick. This is deliberate: settings like an RTP
 * radius or an XP multiplier are read from gameplay code, and a configuration system that
 * touched the database on read would put a query on a region thread — the exact thing
 * SPEC §1A forbids.
 *
 * <p>Writes are the opposite: {@link #set} goes to the database, records an audit entry,
 * updates the cache and notifies listeners, so it returns a future.
 */
public interface ConfigService {

    /**
     * Declares a setting.
     *
     * <p>Call once at startup, before reading. Registering makes the key visible to the
     * web panel and loads its current value; an unregistered key always reads as its
     * default, since nothing knows it exists.
     *
     * @param <T> the value type
     * @param key the setting to declare
     */
    <T> void register(ConfigKey<T> key);

    /**
     * Reads the current value.
     *
     * <p>Non-blocking and safe from any thread. Returns the key's default if no value has
     * been set, or if a stored value cannot be parsed — a bad row in the database degrades
     * one setting rather than breaking the plugin.
     *
     * @param <T> the value type
     * @param key the setting to read
     * @return the current value, never {@code null}
     */
    <T> T get(ConfigKey<T> key);

    /**
     * Changes a setting.
     *
     * <p>Writes to the database, records the change in the audit log, updates the cache,
     * and notifies listeners — in that order, so a listener never observes a value that
     * failed to persist.
     *
     * @param <T>    the value type
     * @param key    the setting to change
     * @param value  the new value
     * @param actor  who is making the change
     * @param source how the change is being made
     * @return a future of the audit revision number, failing if the value is invalid or
     *     the write fails
     */
    <T> CompletableFuture<Long> set(
            ConfigKey<T> key, T value, AuditActor actor, ChangeSource source);

    /**
     * Reverses an earlier change.
     *
     * <p>Applies the old value as a new change rather than deleting history, so the audit
     * log records both what happened and that it was undone (SPEC §6).
     *
     * @param revision the audit revision to reverse
     * @param actor    who is undoing it
     * @param source   how
     * @return a future of the new revision number, failing if the revision does not exist
     *     or is not something that can be undone
     */
    CompletableFuture<Long> undo(long revision, AuditActor actor, ChangeSource source);

    /**
     * Watches a setting for changes.
     *
     * <p>Listeners are invoked off the server threads. A listener that touches the world,
     * an entity or a player must hop first, through {@code MoleculeScheduler}.
     *
     * @param <T>      the value type
     * @param key      the setting to watch
     * @param listener called with the new value after each change
     * @return a handle that removes the listener when closed
     */
    <T> AutoCloseable watch(ConfigKey<T> key, Consumer<T> listener);

    /**
     * Re-reads every registered setting from the database.
     *
     * <p>Needed when the database has been changed by something other than this server —
     * a direct SQL edit, or another server sharing the database. Fires change events for
     * anything whose value differs.
     *
     * @return a future completing when the cache matches the database
     */
    CompletableFuture<Void> reload();
}
