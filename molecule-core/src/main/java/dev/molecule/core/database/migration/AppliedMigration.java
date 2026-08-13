package dev.molecule.core.database.migration;

import java.time.Instant;

/**
 * A migration the database reports as already applied.
 *
 * @param version     the migration's version number
 * @param description the description recorded when it ran
 * @param checksum    the checksum recorded when it ran, compared against the migration
 *     shipped today to detect edits
 * @param appliedAt   when it ran
 * @param durationMs  how long it took, kept because a migration that was slow once will be
 *     slow again on the next server and is worth knowing about before an upgrade
 */
public record AppliedMigration(
        int version, String description, String checksum, Instant appliedAt, long durationMs) {}
