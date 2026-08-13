package dev.molecule.api.scheduler;

/**
 * What a call site does when a scheduled task does not reach its target (SPEC §1A.3).
 *
 * <p>No existing Folia scheduler library ships a retry policy — they report the outcome
 * and stop. Molecule requires the decision to be explicit at every call site, because the
 * right answer differs sharply by subject: dropping a nameplate update is correct, and
 * dropping a completed purchase is a duplication bug.
 */
public enum RetryPolicy {

    /**
     * Discard the task if it cannot be delivered.
     *
     * <p>Correct for cosmetic and presentational work — nameplates, scoreboard lines,
     * particles, hologram refreshes. The next tick will recompute it anyway.
     */
    DROP,

    /**
     * Re-resolve the target and reschedule, up to an implementation-defined bound.
     *
     * <p>For work that must land but is safe to run late. The target is looked up again
     * rather than reused, so a player who reconnects is found on their new thread. Tasks
     * using this policy must be idempotent — a retry may run after a partial effect.
     */
    RETRY,

    /**
     * Report the failure to the caller for compensating action.
     *
     * <p>For work whose side effects must stay consistent: economy transactions, cooldown
     * consumption, hub capacity reservations (SPEC §29), persistence writes. The caller
     * releases the reservation, refunds the charge, or records the inconsistency for the
     * audit log (SPEC §6) rather than silently losing it.
     */
    ESCALATE
}
