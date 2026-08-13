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

Not yet started: database provider, migrations, configuration system, audit log,
action and variable registries, player profile, HTTP server, REST/WebSocket API,
authentication, web panel, resource-pack architecture.
