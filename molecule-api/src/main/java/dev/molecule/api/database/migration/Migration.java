package dev.molecule.api.database.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One versioned schema change (SPEC §5).
 *
 * <p>Migrations are immutable history. Once a migration has run against any server it must
 * never be edited — change the schema by adding the next version instead. Molecule
 * enforces this with a checksum: an altered migration is detected at startup rather than
 * leaving two servers silently on different schemas.
 *
 * @param version     the version number, unique and positive within a namespace
 * @param description a short human-readable summary, shown in startup logs
 * @param sql         the statement to run; one statement per migration
 */
public record Migration(int version, String description, String sql) {

    /**
     * Creates a migration.
     *
     * @throws IllegalArgumentException if the version is not positive, or the description
     *     or SQL is blank
     */
    public Migration {
        if (version <= 0) {
            throw new IllegalArgumentException("Migration version must be positive, got " + version);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Migration " + version + " needs a description");
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Migration " + version + " has no SQL");
        }
    }

    /**
     * Returns the checksum recorded when this migration is applied.
     *
     * <p>Line endings are normalised and trailing whitespace stripped, so reformatting or
     * a change of checkout platform does not read as a schema change — but any change to
     * the statements themselves does.
     *
     * @return a lowercase hex SHA-256 digest
     */
    public String checksum() {
        String normalised = sql.replace("\r\n", "\n").strip();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is missing the platform is broken.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
