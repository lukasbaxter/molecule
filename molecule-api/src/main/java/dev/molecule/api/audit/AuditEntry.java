package dev.molecule.api.audit;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One permanently recorded change (SPEC §6).
 *
 * <p>Audit history is append-only. Nothing edits or deletes an entry — an undo writes a
 * new entry that reverses the original and points at it, so the record of what happened
 * survives even after the change itself is reverted.
 *
 * @param revision       monotonic identifier, and what an undo refers to
 * @param occurredAt     when the change was made
 * @param actor          who made it
 * @param source         how it was made
 * @param namespace      the owning plugin's table prefix
 * @param operation      what happened, such as {@code config.set}
 * @param target         what was changed, such as a config path — absent for changes with
 *     no single subject
 * @param oldValue       value before the change, absent when nothing existed before
 * @param newValue       value after the change, absent when the change removed something
 * @param undoesRevision the revision this entry reverses, if it is an undo
 * @param context        any extra detail worth keeping, such as the import file name
 */
public record AuditEntry(
        long revision,
        Instant occurredAt,
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
     * Renders the change the way the spec's examples read, for logs and the panel's list
     * view.
     *
     * @return a one-line summary, such as {@code rtp.radius: 10000 → 25000}
     */
    public String summary() {
        String subject = target.orElse(operation);
        String before = oldValue.orElse("(unset)");
        String after = newValue.orElse("(removed)");
        return subject + ": " + before + " → " + after;
    }
}
