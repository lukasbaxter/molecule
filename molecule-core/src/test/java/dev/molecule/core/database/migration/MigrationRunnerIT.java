package dev.molecule.core.database.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.molecule.api.database.DatabaseNamespace;
import dev.molecule.api.database.migration.Migration;
import dev.molecule.api.database.migration.MigrationException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Exercises the migration runner against a real MariaDB.
 *
 * <p>The planner's decisions are unit-tested without a database; what needs a real one is
 * everything the planner cannot see — that DDL actually applies, that a migration and its
 * history row commit together, and that re-running is a no-op.
 *
 * <p>Skipped automatically where Docker is unavailable, so a developer without it can
 * still run the rest of the suite.
 */
@EnabledIf("dockerAvailable")
class MigrationRunnerIT {

    private static final DatabaseNamespace NAMESPACE = DatabaseNamespace.forModule("tp");
    private static final Logger LOGGER = Logger.getLogger(MigrationRunnerIT.class.getName());

    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>("mariadb:11.4").withDatabaseName("molecule_test");

    private static HikariDataSource dataSource;

    private MigrationRunner runner;

    /**
     * Skips this class rather than failing it where Docker is absent — a contributor
     * without Docker should still be able to run the rest of the suite. CI has Docker, so
     * these do run there.
     */
    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeAll
    static void startDatabase() {
        MARIADB.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MARIADB.getJdbcUrl());
        config.setUsername(MARIADB.getUsername());
        config.setPassword(MARIADB.getPassword());
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
        MARIADB.stop();
    }

    @BeforeEach
    void reset() throws SQLException {
        // Each test starts from an empty schema so ordering between tests cannot matter.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + MigrationRunner.HISTORY_TABLE);
            statement.execute("DROP TABLE IF EXISTS " + NAMESPACE.table("homes"));
            statement.execute("DROP TABLE IF EXISTS " + NAMESPACE.table("warps"));
        }
        runner = new MigrationRunner(dataSource, LOGGER);
    }

    private static Migration createHomes() {
        return new Migration(
                1,
                "homes",
                "CREATE TABLE " + NAMESPACE.table("homes") + " (id INT PRIMARY KEY, name VARCHAR(32))");
    }

    private static Migration createWarps() {
        return new Migration(
                2, "warps", "CREATE TABLE " + NAMESPACE.table("warps") + " (id INT PRIMARY KEY)");
    }

    @Test
    void appliesMigrationsAndRecordsThem() throws SQLException {
        int applied = runner.migrate(NAMESPACE, List.of(createHomes(), createWarps()));

        assertThat(applied).isEqualTo(2);
        assertThat(tableExists(NAMESPACE.table("homes"))).isTrue();
        assertThat(tableExists(NAMESPACE.table("warps"))).isTrue();
        assertThat(historyCount()).isEqualTo(2);
    }

    @Test
    void isANoOpWhenRunAgain() {
        List<Migration> migrations = List.of(createHomes(), createWarps());
        runner.migrate(NAMESPACE, migrations);

        // Restarting the server must not re-run DDL that already succeeded.
        assertThat(runner.migrate(NAMESPACE, migrations)).isZero();
    }

    @Test
    void appliesOnlyTheNewMigrationOnUpgrade() throws SQLException {
        runner.migrate(NAMESPACE, List.of(createHomes()));

        int applied = runner.migrate(NAMESPACE, List.of(createHomes(), createWarps()));

        assertThat(applied).isEqualTo(1);
        assertThat(tableExists(NAMESPACE.table("warps"))).isTrue();
        assertThat(historyCount()).isEqualTo(2);
    }

    @Test
    void detectsAnEditedMigrationAgainstARealHistoryTable() {
        runner.migrate(NAMESPACE, List.of(createHomes()));

        Migration edited =
                new Migration(
                        1,
                        "homes",
                        "CREATE TABLE " + NAMESPACE.table("homes") + " (id BIGINT PRIMARY KEY)");

        assertThatThrownBy(() -> runner.migrate(NAMESPACE, List.of(edited)))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("has changed since it was applied");
    }

    /**
     * A failing migration must leave no trace: neither a half-created table nor a history
     * row claiming it succeeded. Otherwise the next start would skip it.
     */
    @Test
    void rollsBackAndRecordsNothingWhenAMigrationFails() throws SQLException {
        Migration broken = new Migration(1, "broken", "CREATE TABLE (this is not sql");

        assertThatThrownBy(() -> runner.migrate(NAMESPACE, List.of(broken)))
                .isInstanceOf(MigrationException.class);

        assertThat(historyCount()).isZero();
    }

    @Test
    void aFailedMigrationCanBeFixedAndReapplied() throws SQLException {
        Migration broken = new Migration(1, "homes", "CREATE TABLE (this is not sql");
        assertThatThrownBy(() -> runner.migrate(NAMESPACE, List.of(broken)))
                .isInstanceOf(MigrationException.class);

        // Because nothing was recorded, correcting version 1 and starting again works.
        assertThat(runner.migrate(NAMESPACE, List.of(createHomes()))).isEqualTo(1);
        assertThat(tableExists(NAMESPACE.table("homes"))).isTrue();
    }

    @Test
    void namespacesAreMigratedIndependently() {
        DatabaseNamespace eco = DatabaseNamespace.forModule("eco");
        runner.migrate(NAMESPACE, List.of(createHomes()));

        // A plugin at version 1 must not be affected by another plugin's version 1.
        int applied =
                runner.migrate(
                        eco,
                        List.of(
                                new Migration(
                                        1,
                                        "accounts",
                                        "CREATE TABLE IF NOT EXISTS "
                                                + eco.table("accounts")
                                                + " (id INT PRIMARY KEY)")));

        assertThat(applied).isEqualTo(1);
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                ResultSet tables =
                        connection.getMetaData().getTables(null, null, table, new String[] {"TABLE"})) {
            return tables.next();
        }
    }

    private int historyCount() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM " + MigrationRunner.HISTORY_TABLE)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }
}
