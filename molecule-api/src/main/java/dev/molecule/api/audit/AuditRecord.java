package dev.molecule.api.audit;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * A change about to be recorded (SPEC §6).
 *
 * <p>Separate from {@link AuditEntry} because the revision number and timestamp are
 * assigned by the database, not by the caller — a caller cannot invent a revision, which
 * is what keeps the sequence trustworthy.
 *
 * @param actor          who made the change
 * @param source         how it was made
 * @param namespace      the owning plugin's table prefix
 * @param operation      what happened, such as {@code config.set}
 * @param target         what was changed
 * @param oldValue       value before the change
 * @param newValue       value after the change
 * @param undoesRevision the revision being reversed, if this is an undo
 * @param context        any extra detail worth keeping
 */
public record AuditRecord(
        AuditActor actor,
        ChangeSource source,
        String namespace,
        String operation,
        Optional<String> target,
        Optional<String> oldValue,
        Optional<String> newValue,
        OptionalLong undoesRevision,
        Optional<String> context) {

    /**
     * Validates the record.
     *
     * @throws IllegalArgumentException if the namespace or operation is blank
     */
    public AuditRecord {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("Audit record needs a namespace");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Audit record needs an operation");
        }
    }

    /**
     * Records a setting changing value.
     *
     * @param actor     who changed it
     * @param source    how
     * @param namespace the owning namespace prefix
     * @param path      the config path
     * @param oldValue  previous value, or {@code null} if it was unset
     * @param newValue  new value
     * @return the record
     */
    public static AuditRecord configChange(
            AuditActor actor,
            ChangeSource source,
            String namespace,
            String path,
            String oldValue,
            String newValue) {
        return new AuditRecord(
                actor,
                source,
                namespace,
                "config.set",
                Optional.of(path),
                Optional.ofNullable(oldValue),
                Optional.of(newValue),
                OptionalLong.empty(),
                Optional.empty());
    }

    /**
     * Returns a copy of this record marked as reversing an earlier revision.
     *
     * @param revision the revision being undone
     * @return the record
     */
    public AuditRecord undoing(long revision) {
        return new AuditRecord(
                actor,
                source,
                namespace,
                operation,
                target,
                oldValue,
                newValue,
                OptionalLong.of(revision),
                Optional.of("undo of revision " + revision));
    }
}
