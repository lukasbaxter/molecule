package dev.molecule.api.database;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A plugin's slice of the Molecule database (SPEC §5).
 *
 * <p>Core owns the single connection pool; each plugin gets a namespace and builds its
 * table names through it, so ownership is visible in the schema itself:
 *
 * <pre>
 * molecule_core_*      molecule_tp_*      molecule_eco_*
 * molecule_ranks_*     molecule_npc_*     molecule_stats_*
 * </pre>
 *
 * <p>Names are validated rather than trusted. Table names reach SQL as identifiers, which
 * cannot be parameterised, so the only safe approach is to constrain what may become one:
 * lowercase letters, digits and underscores, starting with a letter. A caller cannot
 * smuggle a quote, a space or a semicolon through {@link #table(String)}.
 */
public final class DatabaseNamespace {

    /** MySQL/MariaDB truncate at 64 characters; a longer name is a bug, not a warning. */
    private static final int MAX_IDENTIFIER_LENGTH = 64;

    private static final Pattern VALID_PART = Pattern.compile("[a-z][a-z0-9_]*");

    private final String prefix;

    private DatabaseNamespace(String prefix) {
        this.prefix = prefix;
    }

    /**
     * Creates the namespace for a module.
     *
     * @param module the module's short name, such as {@code core}, {@code tp} or
     *     {@code npc} — not the full plugin name
     * @return the namespace
     * @throws IllegalArgumentException if the module name is not a legal identifier part
     */
    public static DatabaseNamespace forModule(String module) {
        String normalised = module.toLowerCase(Locale.ROOT);
        // Accept the full module name for convenience, since callers usually have
        // "molecule-npc" to hand rather than "npc".
        if (normalised.startsWith("molecule-")) {
            normalised = normalised.substring("molecule-".length());
        }
        requireValidPart(normalised, "module");
        return new DatabaseNamespace("molecule_" + normalised + "_");
    }

    /**
     * Builds a fully qualified table name inside this namespace.
     *
     * @param name the unqualified table name, such as {@code homes}
     * @return the prefixed name, such as {@code molecule_tp_homes}
     * @throws IllegalArgumentException if the name is not a legal identifier part, or the
     *     result would exceed the database's identifier limit
     */
    public String table(String name) {
        String normalised = name.toLowerCase(Locale.ROOT);
        requireValidPart(normalised, "table");
        String qualified = prefix + normalised;
        if (qualified.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    "Table name '" + qualified + "' exceeds " + MAX_IDENTIFIER_LENGTH + " characters");
        }
        return qualified;
    }

    /**
     * Returns the prefix every table in this namespace carries.
     *
     * @return the prefix, including its trailing underscore
     */
    public String prefix() {
        return prefix;
    }

    @Override
    public String toString() {
        return prefix + "*";
    }

    private static void requireValidPart(String value, String what) {
        if (!VALID_PART.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid "
                            + what
                            + " name '"
                            + value
                            + "': expected lowercase letters, digits and underscores,"
                            + " starting with a letter");
        }
    }
}
