package dev.molecule.api.scheduler;

/**
 * The outcome of a task submitted to the {@link MoleculeScheduler} (SPEC §1A.3).
 *
 * <p>Entity scheduling has three outcomes, not two. Folia's own entity scheduler
 * re-resolves an entity's current owning region at dispatch time, but it does not retry
 * and it does not report failure as an exception — a task targeting a retired entity
 * simply never runs. That silent no-op is the correct disconnect-safety mechanism for
 * cosmetic work and a correctness bug for anything persistent, so Molecule surfaces the
 * distinction and forces each call site to choose (see {@link RetryPolicy}).
 */
public enum TaskResult {

    /** The task ran on a thread legally owning its subject. */
    SUCCESS,

    /**
     * The target entity was removed, died, or disconnected before the task could run.
     *
     * <p>Expect this routinely — players quit mid-teleport and mid-warmup. Cosmetic work
     * may drop silently; work with side effects (currency, cooldowns, hub reservations)
     * must resolve them explicitly rather than leaving them dangling.
     */
    ENTITY_RETIRED,

    /**
     * The scheduler itself was shut down, typically because the plugin is disabling.
     *
     * <p>Not retryable — retrying during shutdown prevents the server from stopping.
     * Persist anything that must survive, then give up.
     */
    SCHEDULER_RETIRED
}
