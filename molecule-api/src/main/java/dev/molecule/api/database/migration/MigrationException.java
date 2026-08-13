package dev.molecule.api.database.migration;

import java.io.Serial;

/**
 * Raised when the schema cannot be safely brought up to date (SPEC §5).
 *
 * <p>Always fatal to startup. Every condition that produces this exception means the
 * database is in a state Molecule cannot reason about — running anyway risks writing
 * against a schema that is not what the code expects, which is worse than refusing to
 * start.
 */
public class MigrationException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message what went wrong, and what the administrator should do about it
     */
    public MigrationException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message what went wrong
     * @param cause   the underlying failure
     */
    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
