# Changelog

Release notes are generated per module from Conventional Commit subjects — see
`.github/scripts/release-notes.sh`. This file records ecosystem-wide changes that
span modules or change the architecture.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Repository, Gradle multi-module build, and CI.
- `molecule-api`: the Folia execution bridge contract (`MoleculeScheduler`,
  `TaskResult`, `RetryPolicy`), `PositionSnapshot` and `PositionService`, per SPEC §1A.
- `molecule-core`: Folia bootstrap, `FoliaScheduler`, and the position snapshot
  service — the Phase 1 prerequisites everything else depends on.
- `molecule-api`: `DatabaseService`, `DatabaseNamespace`, `Migration` — the database
  contracts plugins consume, per SPEC §5.
- `molecule-core`: connection pool, bootstrap config, and a versioned migration
  engine with integration tests against a real MariaDB.
- `molecule-api`: `ConfigKey`, `ConfigService`, `AuditService` and supporting types.
- `molecule-core`: live MariaDB-backed configuration with non-blocking reads, and an
  append-only audit log with undo, per SPEC §6 and §59.
- `molecule-api`: `Variable`, `VariableContext`, `VariableRegistry`, `TextRenderer`.
- `molecule-core`: the shared variable registry and the MiniMessage-backed universal
  text engine, per SPEC §13 and §14.
- Release pipeline with per-module note generation.

Nothing has been released yet. `molecule-api` and `molecule-core` now produce real
jars and would be included if a release were tagged; every other module is still
scaffolding and is excluded automatically.
