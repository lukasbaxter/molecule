# Molecule

A modular plugin ecosystem for [Folia](https://github.com/PaperMC/Folia) servers — shared
core, shared API, shared database, shared web panel.

> **Status: pre-implementation.** The architecture is specified and the build is set up;
> no plugin is implemented yet. There are no releases, and there are deliberately no jars
> to download. Watch the repo if you want to know when that changes.

## What this is

Most Minecraft plugin suites are a collection of separate plugins that happen to share an
author. Molecule is built the other way round: one core owns the database, configuration,
web panel, action registry, text engine and developer API, and every plugin consumes that
infrastructure instead of reimplementing it.

Plugins remain independently usable — install `molecule-core` and `molecule-eco` alone and
it works. Optional integrations degrade to disabled features rather than crashes.

## Why Folia specifically

Folia replaces Minecraft's single main thread with independently ticking regions. Most
existing plugins either don't run on it or run with silent thread-safety violations that
degrade rather than crash — stale positions, flickering entities, lost teleports.

Molecule treats that as the primary design constraint rather than a porting problem. The
architecture spec includes a survey of how working Folia plugins actually solve these
problems, read from source: see [`docs/SPEC.md`](docs/SPEC.md) §1A for the execution model
and §73 for the prior-art map. Constraints proven by reading real implementations are
tagged `[VERIFIED]`.

Three findings shape the whole project:

- **World management needs NMS internals.** `Bukkit.createWorld()` and `World#setGameRule()`
  do not work on Folia. Live gamerule editing has no known working implementation anywhere.
- **NPCs must be packet entities.** Packet NPCs have no region, so they sidestep
  regionization entirely. Real-entity NPCs pay a scheduler hop per tick.
- **Player state cannot be read off-thread.** Molecule publishes immutable position
  snapshots instead, written only from a player's owning thread.

## Repository layout

```text
molecule-api/     public developer API — third parties compile against this only
molecule-core/    database, config, web panel, registries, execution bridge
molecule-*/       one module per plugin, shipped per phase
docs/SPEC.md      the architecture specification
```

## Building

Requires JDK 21.

```bash
./gradlew build
```

## Releases

Tagging drives everything; nothing publishes on a normal push.

```text
v0.2.0                 release every implemented module
molecule-npc-v0.2.0    release one module
```

Release notes are generated per module from [Conventional
Commits](https://www.conventionalcommits.org/), so `fix(npc): stop nameplates flickering
on region handoff` becomes a line under **Fixed** in the next `molecule-npc` release.

Modules that aren't implemented yet carry a `.scaffold` marker and are excluded from
releases — the build compiles them, but an empty jar published as a working plugin would
misrepresent the project.

## Contributing

The spec is the source of truth for architecture, and §1A is binding: if a change schedules
work on the wrong thread class, it's wrong even if it appears to work. Folia bugs of this
kind degrade silently rather than throwing, so review leans on the ownership assertions
described in §64.

## Licence

MIT — see [LICENSE](LICENSE).
