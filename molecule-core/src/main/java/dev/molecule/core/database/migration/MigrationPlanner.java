package dev.molecule.core.database.migration;

import dev.molecule.api.database.DatabaseNamespace;
import dev.molecule.api.database.migration.Migration;
import dev.molecule.api.database.migration.MigrationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which migrations to run, and refuses when the database is in a state that cannot
 * be safely reconciled (SPEC §5).
 *
 * <p>Deliberately free of JDBC: given what a plugin ships and what the database says has
 * been applied, the decision is pure logic and can be tested exhaustively without a
 * database. {@link MigrationRunner} supplies the two inputs and executes the answer.
 *
 * <p>The four refusals below all describe situations where continuing would mean writing
 * against a schema nobody can describe. Startup failing loudly is the lesser harm.
 */
public final class MigrationPlanner {

    private MigrationPlanner() {}

    /**
     * Works out which migrations still need to run.
     *
     * @param namespace the namespace being migrated, used for error messages
     * @param available the migrations this build of the plugin ships, in any order
     * @param applied   what the database reports as already applied, by version
     * @return the migrations to run, in ascending version order; empty if up to date
     * @throws MigrationException if the schema cannot be safely brought up to date
     */
    public static List<Migration> plan(
            DatabaseNamespace namespace,
            List<Migration> available,
            Map<Integer, AppliedMigration> applied) {

        rejectDuplicateVersions(namespace, available);

        List<Migration> ordered = new ArrayList<>(available);
        ordered.sort(Comparator.comparingInt(Migration::version));

        rejectUnknownAppliedVersions(namespace, ordered, applied);
        rejectChangedMigrations(namespace, ordered, applied);

        List<Migration> pending = new ArrayList<>();
        for (Migration migration : ordered) {
            if (!applied.containsKey(migration.version())) {
                pending.add(migration);
            }
        }

        rejectOutOfOrderMigrations(namespace, pending, applied);
        return pending;
    }

    /**
     * Two migrations claiming the same version is a merge accident — whichever ran first
     * would win, and the other would be skipped forever without any error.
     */
    private static void rejectDuplicateVersions(
            DatabaseNamespace namespace, List<Migration> available) {
        Set<Integer> seen = new HashSet<>();
        for (Migration migration : available) {
            if (!seen.add(migration.version())) {
                throw new MigrationException(
                        namespace
                                + ": two migrations both claim version "
                                + migration.version()
                                + ". Renumber one of them; versions must be unique.");
            }
        }
    }

    /**
     * The database knows a version this build does not. The server has been downgraded, or
     * pointed at a database belonging to a newer install — either way the running code may
     * not understand the schema it would be writing to.
     */
    private static void rejectUnknownAppliedVersions(
            DatabaseNamespace namespace,
            List<Migration> available,
            Map<Integer, AppliedMigration> applied) {

        Set<Integer> known = new HashSet<>();
        for (Migration migration : available) {
            known.add(migration.version());
        }
        for (Integer version : applied.keySet()) {
            if (!known.contains(version)) {
                throw new MigrationException(
                        namespace
                                + ": the database has migration "
                                + version
                                + " applied, but this build does not ship it. The database"
                                + " belongs to a newer version of Molecule — upgrade the"
                                + " plugin rather than downgrading the database.");
            }
        }
    }

    /**
     * A migration whose SQL changed after it ran means this server's schema and another's
     * have silently diverged, because the altered statements never executed here.
     */
    private static void rejectChangedMigrations(
            DatabaseNamespace namespace,
            List<Migration> available,
            Map<Integer, AppliedMigration> applied) {

        for (Migration migration : available) {
            AppliedMigration record = applied.get(migration.version());
            if (record == null) {
                continue;
            }
            if (!record.checksum().equals(migration.checksum())) {
                throw new MigrationException(
                        namespace
                                + ": migration "
                                + migration.version()
                                + " ("
                                + migration.description()
                                + ") has changed since it was applied. Applied migrations are"
                                + " immutable — revert the edit and add a new migration"
                                + " instead.");
            }
        }
    }

    /**
     * A pending migration numbered below one already applied would run out of sequence,
     * so servers would end up with the same version number reached by different paths.
     * Typically two branches each added the "next" migration.
     */
    private static void rejectOutOfOrderMigrations(
            DatabaseNamespace namespace,
            List<Migration> pending,
            Map<Integer, AppliedMigration> applied) {

        if (applied.isEmpty() || pending.isEmpty()) {
            return;
        }
        int highestApplied = applied.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
        for (Migration migration : pending) {
            if (migration.version() < highestApplied) {
                throw new MigrationException(
                        namespace
                                + ": migration "
                                + migration.version()
                                + " ("
                                + migration.description()
                                + ") has not been applied, but version "
                                + highestApplied
                                + " already has. Renumber it above "
                                + highestApplied
                                + " so it runs in a defined order.");
            }
        }
    }
}
