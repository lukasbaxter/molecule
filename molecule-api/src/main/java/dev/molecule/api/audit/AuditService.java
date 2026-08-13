package dev.molecule.api.audit;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Permanent history of every administrative change (SPEC §6).
 *
 * <p>Append-only by construction: there is no update or delete. Reversing a change is
 * {@link #undo}, which writes a new entry rather than removing the original.
 *
 * <p>All methods run off-thread and return futures. Recording is fire-and-forget from a
 * caller's point of view — the change itself should not wait on its own audit write —
 * but the future is returned so callers who need the revision number can have it.
 */
public interface AuditService {

    /**
     * Records a change.
     *
     * @param record what happened
     * @return a future of the new revision number
     */
    CompletableFuture<Long> record(AuditRecord record);

    /**
     * Reads history, newest first.
     *
     * @param query what to include
     * @return a future of the matching entries
     */
    CompletableFuture<List<AuditEntry>> search(AuditQuery query);

    /**
     * Reads a single entry.
     *
     * @param revision the revision number
     * @return a future of the entry, empty if no such revision exists
     */
    CompletableFuture<Optional<AuditEntry>> find(long revision);

    /**
     * What to include when reading history.
     *
     * @param namespace restrict to one plugin's changes, or empty for all
     * @param target    restrict to one subject, such as a config path
     * @param limit     maximum entries to return
     */
    record AuditQuery(Optional<String> namespace, Optional<String> target, int limit) {

        /**
         * Validates the query.
         *
         * @throws IllegalArgumentException if the limit is not positive
         */
        public AuditQuery {
            if (limit <= 0) {
                throw new IllegalArgumentException("Audit query limit must be positive");
            }
        }

        /**
         * Returns the most recent changes across everything.
         *
         * @param limit maximum entries
         * @return the query
         */
        public static AuditQuery recent(int limit) {
            return new AuditQuery(Optional.empty(), Optional.empty(), limit);
        }

        /**
         * Returns the history of one subject, which is what the panel shows beside a
         * setting.
         *
         * @param namespace the owning namespace prefix
         * @param target    the subject, such as a config path
         * @param limit     maximum entries
         * @return the query
         */
        public static AuditQuery forTarget(String namespace, String target, int limit) {
            return new AuditQuery(Optional.of(namespace), Optional.of(target), limit);
        }
    }
}
