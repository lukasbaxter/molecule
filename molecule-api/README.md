# molecule-api

The public developer API (SPEC §9). Third-party plugins compile against this module
and nothing else — `molecule-core` internals are not API.

## Status: in progress (Phase 0)

Implemented so far, per SPEC §1A:

- `scheduler.MoleculeScheduler` — the Folia execution bridge contract
- `scheduler.TaskResult` — the three outcomes of entity-targeted scheduling
- `scheduler.RetryPolicy` — what a call site does when delivery fails
- `position.PositionSnapshot` — immutable player position, safe to read off-thread

Still to come: service registry, player profile, ranks, economy, stats, actions,
variables and UI contracts.
