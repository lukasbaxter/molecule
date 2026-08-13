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
- Release pipeline with per-module note generation.

Nothing has been released yet. `molecule-api` and `molecule-core` now produce real
jars and would be included if a release were tagged; every other module is still
scaffolding and is excluded automatically.
