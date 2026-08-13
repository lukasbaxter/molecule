# molecule-core

The foundation of the ecosystem: database, migrations, configuration, audit log,
HTTP/REST/WebSocket server, web-panel backend, registries, text engine,
resource packs, and the Folia execution bridge (SPEC §3, §1A).

Core ships the API inside its jar, so `molecule-api` is on the server at runtime.
Other plugins depend on the API `compileOnly`.

## Status: in progress (Phase 1)

Implemented:

- `MoleculePlugin` — two-stage bootstrap. Services that touch nothing are built in
  `onEnable`; anything needing a running regionized server waits for
  `RegionizedServerInitEvent`, because Folia has no post-startup main-thread tick.
- `scheduler.FoliaScheduler` — the execution bridge (SPEC §1A.2). Maps each thread
  class onto the Folia scheduler that owns it, reports `ENTITY_RETIRED` instead of
  dropping work silently, and stops accepting work before teardown.
- `position.PositionTracker` / `PositionListener` — immutable position snapshots
  written only by a player's owning thread (SPEC §1A.3).
- `database.HikariDatabaseService` — the ecosystem's single connection pool
  (SPEC §5). Plugins get namespaced access returning futures; nothing can occupy a
  region thread. Table names are validated as identifiers rather than escaped,
  because identifiers cannot be parameterised.
- `database.migration` — versioned migrations that refuse four unsafe states rather
  than guessing: duplicate versions, a database ahead of the running build, a
  migration edited after it was applied, and a pending migration numbered below one
  already applied. Each migration commits with its history row, and a named database
  lock prevents two servers migrating at once.
- Bootstrap `config.yml` holding only what is needed to reach the database. Live
  configuration belongs in MariaDB (SPEC §4).
- `config.DatabaseConfigService` — live configuration (SPEC §59, §60). Reads answer
  from an in-memory cache, so gameplay code can read a setting on a region thread
  every tick; writes persist before the cache moves, so a watcher never sees a value
  that failed to save. Constraints are enforced on write *and* on read, so a
  hand-edited database row degrades one setting rather than poisoning it.
- `audit.DatabaseAuditService` — append-only history (SPEC §6). Insert and select
  only; there is deliberately no update or delete path. Undo applies the previous
  value as a new change pointing at the revision it reverses.

**Known limitation:** changes made by this server propagate immediately, but changes
made directly in the database or by another server sharing it are only picked up on
`reload()`. Molecule does not yet poll or subscribe for external changes. Stated
rather than hidden, per SPEC §60's rule against faking live support.

Not yet started: action and variable registries, player profile, HTTP server,
REST/WebSocket API, authentication, web panel, resource-pack architecture.

## Tests

Pure logic — the migration planner, config keys and codecs — is unit-tested
exhaustively. Anything involving persistence is verified against a real MariaDB via
Testcontainers (`MigrationRunnerIT`, `ConfigAuditIT`), which skips itself where
Docker is unavailable; CI has Docker, so it runs there.
