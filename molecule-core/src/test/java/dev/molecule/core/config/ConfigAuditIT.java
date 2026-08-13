package dev.molecule.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.molecule.api.audit.AuditActor;
import dev.molecule.api.audit.AuditEntry;
import dev.molecule.api.audit.AuditService;
import dev.molecule.api.audit.ChangeSource;
import dev.molecule.api.config.ConfigKey;
import dev.molecule.api.database.DatabaseNamespace;
import dev.molecule.core.audit.DatabaseAuditService;
import dev.molecule.core.database.CoreSchema;
import dev.molecule.core.database.HikariDatabaseService;
import dev.molecule.core.database.migration.MigrationRunner;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Exercises live configuration and the audit log against a real MariaDB.
 *
 * <p>What needs a database here is everything the unit tests cannot reach: that a change
 * survives a restart, that history is genuinely append-only, and that an undo restores the
 * old value while leaving the original change on the record.
 */
@EnabledIf("dockerAvailable")
class ConfigAuditIT {

    private static final DatabaseNamespace TP = DatabaseNamespace.forModule("tp");
    private static final Logger LOGGER = Logger.getLogger(ConfigAuditIT.class.getName());
    private static final AuditActor ADMIN = AuditActor.player(UUID.randomUUID(), "Lukas");

    private static final ConfigKey<Integer> RTP_RADIUS =
            ConfigKey.integer(TP, "rtp.radius", 10_000)
                    .constrained(value -> value >= 100, "at least 100");

    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>("mariadb:11.4").withDatabaseName("molecule_test");

    private static HikariDataSource dataSource;
    private static ExecutorService pool;

    private HikariDatabaseService database;
    private DatabaseConfigService config;
    private AuditService audit;

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeAll
    static void startDatabase() {
        MARIADB.start();
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(MARIADB.getJdbcUrl());
        hikari.setUsername(MARIADB.getUsername());
        hikari.setPassword(MARIADB.getPassword());
        hikari.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(hikari);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        MARIADB.stop();
    }

    @BeforeEach
    void reset() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + CoreSchema.NAMESPACE.table("config"));
            statement.execute("DROP TABLE IF EXISTS " + CoreSchema.NAMESPACE.table("audit"));
            statement.execute("DROP TABLE IF EXISTS " + CoreSchema.NAMESPACE.table("players"));
            statement.execute("DROP TABLE IF EXISTS " + MigrationRunner.HISTORY_TABLE);
        }
        new MigrationRunner(dataSource, LOGGER).migrate(CoreSchema.NAMESPACE, CoreSchema.migrations());

        database = new HikariDatabaseService(testSettings(), pool);
        audit = new DatabaseAuditService(database);
        config = new DatabaseConfigService(database, audit, LOGGER);
        config.register(RTP_RADIUS);
    }

    private static dev.molecule.core.database.DatabaseSettings testSettings() {
        // Reuse the container's credentials through the real settings type, so the
        // production connection path is what gets exercised.
        String url = MARIADB.getJdbcUrl();
        String hostAndPort = url.substring("jdbc:mariadb://".length(), url.lastIndexOf('/'));
        return new dev.molecule.core.database.DatabaseSettings(
                hostAndPort.substring(0, hostAndPort.indexOf(':')),
                Integer.parseInt(hostAndPort.substring(hostAndPort.indexOf(':') + 1)),
                MARIADB.getDatabaseName(),
                MARIADB.getUsername(),
                MARIADB.getPassword(),
                false,
                4,
                java.time.Duration.ofSeconds(10),
                java.util.Map.of());
    }

    @Test
    void readsTheDefaultUntilSomethingIsStored() {
        assertThat(config.get(RTP_RADIUS)).isEqualTo(10_000);
    }

    @Test
    void aChangeIsVisibleImmediatelyAndSurvivesAReload() {
        config.set(RTP_RADIUS, 25_000, ADMIN, ChangeSource.WEB).join();

        assertThat(config.get(RTP_RADIUS)).isEqualTo(25_000);

        // A fresh service reading the same database is the restart case.
        DatabaseConfigService restarted = new DatabaseConfigService(database, audit, LOGGER);
        restarted.register(RTP_RADIUS);
        restarted.reload().join();

        assertThat(restarted.get(RTP_RADIUS)).isEqualTo(25_000);
    }

    @Test
    void everyChangeIsRecordedWithBothValues() {
        config.set(RTP_RADIUS, 25_000, ADMIN, ChangeSource.WEB).join();

        List<AuditEntry> history =
                audit.search(AuditService.AuditQuery.recent(10)).join();

        assertThat(history).hasSize(1);
        AuditEntry entry = history.get(0);
        assertThat(entry.summary()).isEqualTo("rtp.radius: (unset) → 25000");
        assertThat(entry.actor().name()).isEqualTo("Lukas");
        assertThat(entry.actor().uuid()).isPresent();
        assertThat(entry.source()).isEqualTo(ChangeSource.WEB);
        assertThat(entry.oldValue()).isEmpty();
        assertThat(entry.newValue()).contains("25000");
    }

    @Test
    void successiveChangesRecordThePreviousValue() {
        config.set(RTP_RADIUS, 25_000, ADMIN, ChangeSource.WEB).join();
        config.set(RTP_RADIUS, 30_000, ADMIN, ChangeSource.COMMAND).join();

        List<AuditEntry> history = audit.search(AuditService.AuditQuery.recent(10)).join();

        assertThat(history).hasSize(2);
        // Newest first.
        assertThat(history.get(0).summary()).isEqualTo("rtp.radius: 25000 → 30000");
        assertThat(history.get(1).summary()).isEqualTo("rtp.radius: (unset) → 25000");
    }

    @Test
    void watchersAreNotifiedOfTheNewValue() {
        AtomicReference<Integer> observed = new AtomicReference<>();
        config.watch(RTP_RADIUS, observed::set);

        config.set(RTP_RADIUS, 25_000, ADMIN, ChangeSource.WEB).join();

        assertThat(observed.get()).isEqualTo(25_000);
    }

    @Test
    void aClosedWatcherStopsBeingNotified() throws Exception {
        AtomicReference<Integer> observed = new AtomicReference<>();
        AutoCloseable subscription = config.watch(RTP_RADIUS, observed::set);

        subscription.close();
        config.set(RTP_RADIUS, 25_000, ADMIN, ChangeSource.WEB).join();

        assertThat(observed.get()).isNull();
    }

    /** SPEC §6: an undo writes a new change; it never removes what happened. */
    @Test
    void undoRestoresTheOldValueAndKeepsBothEntries() {
        long revision = config.set(RTP_RADIUS, 25_000, ADMIN, ChangeSource.WEB).join();
        config.set(RTP_RADIUS, 30_000, ADMIN, ChangeSource.WEB).join();

        long undoRevision = config.undo(revision, ADMIN, ChangeSource.WEB).join();

        // Undoing "unset → 25000" restores the declared default.
        assertThat(config.get(RTP_RADIUS)).isEqualTo(10_000);

        List<AuditEntry> history = audit.search(AuditService.AuditQuery.recent(10)).join();
        assertThat(history).hasSize(3);
        assertThat(history.get(0).revision()).isEqualTo(undoRevision);
        assertThat(history.get(0).undoesRevision()).hasValue(revision);
        // The original change is still on the record.
        assertThat(history).anyMatch(entry -> entry.revision() == revision);
    }

    @Test
    void undoOfALaterChangeRestoresTheValueBeforeIt() {
        config.set(RTP_RADIUS, 25_000, ADMIN, ChangeSource.WEB).join();
        long second = config.set(RTP_RADIUS, 30_000, ADMIN, ChangeSource.WEB).join();

        config.undo(second, ADMIN, ChangeSource.WEB).join();

        assertThat(config.get(RTP_RADIUS)).isEqualTo(25_000);
    }

    @Test
    void refusesToUndoARevisionThatDoesNotExist() {
        assertThatThrownBy(() -> config.undo(9999, ADMIN, ChangeSource.WEB).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAValueThatFailsItsConstraintWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> config.set(RTP_RADIUS, 5, ADMIN, ChangeSource.WEB).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        assertThat(config.get(RTP_RADIUS)).isEqualTo(10_000);
        assertThat(audit.search(AuditService.AuditQuery.recent(10)).join()).isEmpty();
    }

    @Test
    void historyCanBeFilteredToOneSetting() {
        ConfigKey<String> prefix = ConfigKey.string(TP, "messages.prefix", "[TP]");
        config.register(prefix);
        config.set(RTP_RADIUS, 25_000, ADMIN, ChangeSource.WEB).join();
        config.set(prefix, "[Molecule]", ADMIN, ChangeSource.WEB).join();

        List<AuditEntry> history =
                audit.search(AuditService.AuditQuery.forTarget(TP.prefix(), "rtp.radius", 10)).join();

        assertThat(history).hasSize(1);
        assertThat(history.get(0).target()).contains("rtp.radius");
    }
}
