package dev.molecule.core.database;

import dev.molecule.api.database.DatabaseNamespace;
import dev.molecule.api.database.migration.Migration;
import java.util.List;

/**
 * Core's own schema (SPEC §5, §8).
 *
 * <p>Migrations are append-only: to change the schema, add the next version. Editing one
 * of these after it has run anywhere is detected at startup and refused.
 */
public final class CoreSchema {

    /** The namespace Core's tables live in. */
    public static final DatabaseNamespace NAMESPACE = DatabaseNamespace.forModule("core");

    private CoreSchema() {}

    /**
     * Returns Core's migrations, oldest first.
     *
     * @return the migration list
     */
    public static List<Migration> migrations() {
        return List.of(
                new Migration(
                        1,
                        "player identity",
                        "CREATE TABLE "
                                + NAMESPACE.table("players")
                                + " ("
                                + "  uuid          BINARY(16)   NOT NULL,"
                                + "  last_username VARCHAR(16)  NOT NULL,"
                                + "  first_seen    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),"
                                + "  last_seen     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),"
                                + "  PRIMARY KEY (uuid),"
                                // Lookups by name are common (commands, web panel) and must
                                // not table-scan as the player table grows.
                                + "  KEY idx_players_username (last_username)"
                                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"),
                new Migration(
                        2,
                        "audit log",
                        "CREATE TABLE "
                                + NAMESPACE.table("audit")
                                + " ("
                                + "  id          BIGINT       NOT NULL AUTO_INCREMENT,"
                                + "  occurred_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),"
                                + "  actor       BINARY(16)   NULL,"
                                + "  actor_name  VARCHAR(64)  NOT NULL,"
                                + "  namespace   VARCHAR(64)  NOT NULL,"
                                + "  action      VARCHAR(64)  NOT NULL,"
                                + "  target      VARCHAR(255) NULL,"
                                + "  old_value   TEXT         NULL,"
                                + "  new_value   TEXT         NULL,"
                                + "  PRIMARY KEY (id),"
                                // The panel reads this newest-first, and filtered by who or
                                // what changed (SPEC §6).
                                + "  KEY idx_audit_occurred (occurred_at),"
                                + "  KEY idx_audit_actor (actor),"
                                + "  KEY idx_audit_namespace (namespace, action)"
                                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"));
    }
}
