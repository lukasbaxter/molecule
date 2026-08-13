package dev.molecule.core.database.migration;

import dev.molecule.api.database.DatabaseNamespace;
import dev.molecule.api.database.migration.Migration;
import dev.molecule.api.database.migration.MigrationException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Applies pending migrations for a namespace (SPEC §5).
 *
 * <p>Runs during startup, on Molecule's own pool — never on a region thread. The decision
 * of <em>what</em> to run belongs to {@link MigrationPlanner}; this class supplies the
 * database state, executes the answer, and records the outcome.
 *
 * <p>Migrations are guarded by a named database lock, so two servers pointed at one
 * database cannot migrate simultaneously. Without it, both would read the same "pending"
 * list and both would try to create the same table.
 */
public final class MigrationRunner {

    /** Shared by every namespace, so one table describes the whole install's schema state. */
    public static final String HISTORY_TABLE = "molecule_core_schema_history";

    private static final String CREATE_HISTORY =
            "CREATE TABLE IF NOT EXISTS "
                    + HISTORY_TABLE
                    + " ("
                    + "  namespace   VARCHAR(64)  NOT NULL,"
                    + "  version     INT          NOT NULL,"
                    + "  description VARCHAR(255) NOT NULL,"
                    + "  checksum    CHAR(64)     NOT NULL,"
                    + "  applied_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),"
                    + "  duration_ms BIGINT       NOT NULL,"
                    + "  PRIMARY KEY (namespace, version)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    /** Long enough to outlast a slow migration on a large table, short enough to fail a deadlock. */
    private static final int LOCK_TIMEOUT_SECONDS = 60;

    private final DataSource dataSource;
    private final Logger logger;

    /**
     * Creates a runner.
     *
     * @param dataSource the pool to migrate through
     * @param logger     where progress is reported
     */
    public MigrationRunner(DataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    /**
     * Brings a namespace's schema up to date.
     *
     * @param namespace  the namespace being migrated
     * @param migrations every migration the plugin ships, in any order
     * @return how many migrations were applied
     * @throws MigrationException if the schema cannot be safely brought up to date
     */
    public int migrate(DatabaseNamespace namespace, List<Migration> migrations) {
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);

            String lockName = "molecule_migrate_" + namespace.prefix();
            acquireLock(connection, lockName);
            try {
                Map<Integer, AppliedMigration> applied = readApplied(connection, namespace);
                List<Migration> pending = MigrationPlanner.plan(namespace, migrations, applied);

                if (pending.isEmpty()) {
                    logger.fine(() -> namespace + " schema is up to date.");
                    return 0;
                }

                logger.info(
                        () ->
                                "Applying "
                                        + pending.size()
                                        + " migration(s) to "
                                        + namespace
                                        + " (currently at version "
                                        + currentVersion(applied)
                                        + ").");

                for (Migration migration : pending) {
                    apply(connection, namespace, migration);
                }
                return pending.size();
            } finally {
                releaseLock(connection, lockName);
            }
        } catch (SQLException e) {
            throw new MigrationException("Failed to migrate " + namespace, e);
        }
    }

    /**
     * Runs one migration and records it in the same transaction.
     *
     * <p>Both together, so a crash between the two cannot leave a migration that ran but
     * is not recorded — which would make it run again on the next start.
     */
    private void apply(Connection connection, DatabaseNamespace namespace, Migration migration)
            throws SQLException {

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        long startedAt = System.nanoTime();
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute(migration.sql());
            }
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            record(connection, namespace, migration, durationMs);
            connection.commit();
            logger.info(
                    () ->
                            "  applied "
                                    + namespace.prefix()
                                    + " v"
                                    + migration.version()
                                    + " — "
                                    + migration.description()
                                    + " ("
                                    + durationMs
                                    + "ms)");
        } catch (SQLException e) {
            connection.rollback();
            throw new MigrationException(
                    namespace
                            + ": migration "
                            + migration.version()
                            + " ("
                            + migration.description()
                            + ") failed and was rolled back",
                    e);
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void record(
            Connection connection,
            DatabaseNamespace namespace,
            Migration migration,
            long durationMs)
            throws SQLException {

        String sql =
                "INSERT INTO "
                        + HISTORY_TABLE
                        + " (namespace, version, description, checksum, duration_ms)"
                        + " VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace.prefix());
            statement.setInt(2, migration.version());
            statement.setString(3, migration.description());
            statement.setString(4, migration.checksum());
            statement.setLong(5, durationMs);
            statement.executeUpdate();
        }
    }

    private Map<Integer, AppliedMigration> readApplied(
            Connection connection, DatabaseNamespace namespace) throws SQLException {

        String sql =
                "SELECT version, description, checksum, applied_at, duration_ms FROM "
                        + HISTORY_TABLE
                        + " WHERE namespace = ?";
        Map<Integer, AppliedMigration> applied = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace.prefix());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    applied.put(
                            rows.getInt("version"),
                            new AppliedMigration(
                                    rows.getInt("version"),
                                    rows.getString("description"),
                                    rows.getString("checksum"),
                                    rows.getTimestamp("applied_at").toInstant(),
                                    rows.getLong("duration_ms")));
                }
            }
        }
        return applied;
    }

    private void ensureHistoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_HISTORY);
        }
    }

    /**
     * Takes the migration lock, or fails.
     *
     * <p>{@code GET_LOCK} is held for the session, so the lock is scoped to this
     * connection and is released if the server dies mid-migration.
     */
    private void acquireLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, lockName);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                boolean acquired = result.next() && result.getInt(1) == 1;
                if (!acquired) {
                    throw new MigrationException(
                            "Timed out waiting for the migration lock '"
                                    + lockName
                                    + "'. Another server is probably migrating the same"
                                    + " database; wait for it to finish and start again.");
                }
            }
        }
    }

    private void releaseLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            try (ResultSet ignored = statement.executeQuery()) {
                // The result is irrelevant; the call is what releases the lock.
            }
        }
    }

    private static String currentVersion(Map<Integer, AppliedMigration> applied) {
        return applied.isEmpty()
                ? "none"
                : String.valueOf(
                        applied.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow());
    }
}
