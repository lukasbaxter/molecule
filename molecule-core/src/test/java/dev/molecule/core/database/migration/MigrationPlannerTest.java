package dev.molecule.core.database.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.molecule.api.database.DatabaseNamespace;
import dev.molecule.api.database.migration.Migration;
import dev.molecule.api.database.migration.MigrationException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationPlannerTest {

    private static final DatabaseNamespace NAMESPACE = DatabaseNamespace.forModule("tp");

    private static Migration migration(int version) {
        return new Migration(version, "migration " + version, "CREATE TABLE t" + version + " (id INT)");
    }

    private static Map<Integer, AppliedMigration> applied(Migration... migrations) {
        Map<Integer, AppliedMigration> map = new HashMap<>();
        for (Migration migration : migrations) {
            map.put(
                    migration.version(),
                    new AppliedMigration(
                            migration.version(),
                            migration.description(),
                            migration.checksum(),
                            Instant.now(),
                            5L));
        }
        return map;
    }

    @Test
    void runsEverythingOnAFreshDatabase() {
        List<Migration> plan =
                MigrationPlanner.plan(
                        NAMESPACE, List.of(migration(2), migration(1), migration(3)), Map.of());

        assertThat(plan).extracting(Migration::version).containsExactly(1, 2, 3);
    }

    @Test
    void runsOnlyWhatIsMissing() {
        List<Migration> available = List.of(migration(1), migration(2), migration(3));

        List<Migration> plan =
                MigrationPlanner.plan(NAMESPACE, available, applied(migration(1), migration(2)));

        assertThat(plan).extracting(Migration::version).containsExactly(3);
    }

    @Test
    void doesNothingWhenUpToDate() {
        List<Migration> available = List.of(migration(1), migration(2));

        assertThat(MigrationPlanner.plan(NAMESPACE, available, applied(migration(1), migration(2))))
                .isEmpty();
    }

    @Test
    void refusesDuplicateVersions() {
        // Two branches each adding "version 2" — one would silently never run.
        List<Migration> available =
                List.of(
                        migration(1),
                        new Migration(2, "adds homes", "CREATE TABLE a (id INT)"),
                        new Migration(2, "adds warps", "CREATE TABLE b (id INT)"));

        assertThatThrownBy(() -> MigrationPlanner.plan(NAMESPACE, available, Map.of()))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("both claim version 2");
    }

    @Test
    void refusesWhenTheDatabaseKnowsAVersionThisBuildDoesNot() {
        // Downgraded plugin, or a database belonging to a newer install.
        List<Migration> available = List.of(migration(1));

        assertThatThrownBy(
                        () ->
                                MigrationPlanner.plan(
                                        NAMESPACE, available, applied(migration(1), migration(2))))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("newer version of Molecule");
    }

    @Test
    void refusesWhenAnAppliedMigrationHasBeenEdited() {
        Map<Integer, AppliedMigration> alreadyApplied = applied(migration(1));
        // Same version, different SQL: this server ran the old statements, another server
        // would run the new ones, and the two schemas would diverge in silence.
        List<Migration> edited =
                List.of(new Migration(1, "migration 1", "CREATE TABLE t1 (id BIGINT)"));

        assertThatThrownBy(() -> MigrationPlanner.plan(NAMESPACE, edited, alreadyApplied))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("has changed since it was applied");
    }

    @Test
    void refusesAPendingMigrationNumberedBelowOneAlreadyApplied() {
        List<Migration> available = List.of(migration(1), migration(2), migration(3));

        assertThatThrownBy(
                        () ->
                                MigrationPlanner.plan(
                                        NAMESPACE, available, applied(migration(1), migration(3))))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("Renumber it above 3");
    }

    @Test
    void reorderingTheListDoesNotChangeThePlan() {
        List<Migration> forwards = List.of(migration(1), migration(2), migration(3));
        List<Migration> backwards = List.of(migration(3), migration(2), migration(1));

        assertThat(MigrationPlanner.plan(NAMESPACE, forwards, Map.of()))
                .isEqualTo(MigrationPlanner.plan(NAMESPACE, backwards, Map.of()));
    }
}
