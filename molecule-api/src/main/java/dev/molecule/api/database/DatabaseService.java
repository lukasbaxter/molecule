package dev.molecule.api.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Access to the Molecule database (SPEC §5).
 *
 * <p>Core owns the only connection pool in the ecosystem. Plugins request work here
 * instead of opening their own — a server running twelve Molecule plugins should hold one
 * pool, not twelve.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method runs its work on Molecule's own pool and returns a future. Nothing here
 * blocks a region thread, and nothing here is safe to {@code join()} from one. To act on a
 * result, hop back explicitly:
 *
 * <pre>{@code
 * database.query(namespace, "SELECT ...", statement -> ..., this::readHomes)
 *         .thenAccept(homes -> scheduler.runForPlayer(player, () -> show(player, homes)));
 * }</pre>
 *
 * <p>The hop is deliberately not hidden. A result computed off-thread has no owning
 * region, and only the call site knows which thread should receive it.
 */
public interface DatabaseService {

    /**
     * Runs a read query and maps its results.
     *
     * @param <T>       the mapped row type
     * @param namespace the calling plugin's namespace, for diagnostics and auditing
     * @param sql       the query, with {@code ?} placeholders for every value
     * @param binder    binds parameters to the statement
     * @param mapper    maps a single row; called once per row
     * @return a future of the mapped rows, completing exceptionally on {@link SQLException}
     */
    <T> CompletableFuture<List<T>> query(
            DatabaseNamespace namespace, String sql, StatementBinder binder, RowMapper<T> mapper);

    /**
     * Runs a statement that modifies data.
     *
     * @param namespace the calling plugin's namespace
     * @param sql       the statement, with {@code ?} placeholders for every value
     * @param binder    binds parameters to the statement
     * @return a future of the affected row count
     */
    CompletableFuture<Integer> update(
            DatabaseNamespace namespace, String sql, StatementBinder binder);

    /**
     * Runs several statements in one transaction, rolling back if the work throws.
     *
     * <p>The connection is borrowed for the duration and must not outlive the callback —
     * do not retain it, and do not schedule server work from inside it, since the
     * transaction is holding a pooled connection while it runs.
     *
     * @param <T>       the result type
     * @param namespace the calling plugin's namespace
     * @param work      the transactional work
     * @return a future of the result, completing exceptionally if the work failed and the
     *     transaction was rolled back
     */
    <T> CompletableFuture<T> transaction(DatabaseNamespace namespace, TransactionalWork<T> work);

    /**
     * Returns whether the pool is currently able to serve connections.
     *
     * <p>Intended for health reporting rather than for guarding calls — a pool can fail
     * between this check and the next query, so handle failed futures regardless.
     *
     * @return {@code true} if the database is reachable
     */
    boolean isHealthy();

    /** Binds parameters to a prepared statement. */
    @FunctionalInterface
    interface StatementBinder {
        /**
         * Binds every parameter the statement expects.
         *
         * @param statement the statement to bind
         * @throws SQLException if binding fails
         */
        void bind(java.sql.PreparedStatement statement) throws SQLException;

        /** A binder for statements that take no parameters. */
        StatementBinder NONE = statement -> {};
    }

    /**
     * Maps one row of a result set.
     *
     * @param <T> the mapped type
     */
    @FunctionalInterface
    interface RowMapper<T> {
        /**
         * Maps the row the result set is currently positioned on.
         *
         * @param resultSet the result set, already advanced to a row
         * @return the mapped value
         * @throws SQLException if reading fails
         */
        T map(java.sql.ResultSet resultSet) throws SQLException;
    }

    /**
     * Work performed inside a transaction.
     *
     * @param <T> the result type
     */
    @FunctionalInterface
    interface TransactionalWork<T> {
        /**
         * Performs the work.
         *
         * @param connection the transactional connection; do not close or commit it
         * @return the result
         * @throws SQLException if the work fails, which rolls the transaction back
         */
        T execute(Connection connection) throws SQLException;
    }
}
