# Molecule — Master Project Specification

You are the lead architect and senior Java/Folia engineer for a new Minecraft server ecosystem called **Molecule**.

You are not being asked to make a collection of disconnected plugins. You are building a **modular, extensible platform for Folia servers**, with a shared core, shared APIs, shared database infrastructure, shared web panel, shared resource-pack system, shared text system, shared actions/events, and a developer API.

The goal is for Molecule to become a first-class ecosystem for modern Folia servers.

Do not rush into implementing every plugin simultaneously. Establish the architecture and contracts first, then implement the system in deliberate phases with automated tests after every major phase.

---

# 1. NON-NEGOTIABLE REQUIREMENTS

## Minecraft

- Target **Folia 1.21.x latest stable**.
- Folia is the primary and only supported server platform.
- Do not design around Bukkit/Paper-only behavior.
- Do not assume global Bukkit main-thread execution.
- Respect Folia's region-threaded architecture everywhere.
- Use Folia-compatible schedulers and APIs.
- Any operation touching a world/entity/player must execute on the correct Folia region/entity scheduler.
- Database, HTTP, filesystem, resource-pack generation, and other blocking operations must never block region threads.
- Build the system so Folia concurrency is an architectural concern, not something patched later.

Do not support old versions unless there is an extremely compelling architectural reason.

---

# 1A. FOLIA EXECUTION MODEL

> **Source note.** This section, and the constraints marked **[VERIFIED]** throughout this
> document, were derived by reading the source of working Folia plugins rather than from
> documentation or assumption. See §73 for the reference implementations and what each one
> got right and wrong. Where this section contradicts an earlier draft of a later section,
> this section wins.

Section 1 says "any operation touching a world/entity/player must execute on the correct
Folia region/entity scheduler." That is true but insufficient as an engineering contract.
It does not say *how* a result computed on an HTTP, database, or plugin thread gets back
onto a legal thread. Every plugin in this ecosystem needs that, and without it named as
Core infrastructure, each plugin will reinvent it differently — violating Design Principle
11 ("do not reinvent the same system twice").

## 1A.1 The four thread classes

Molecule defines exactly four thread classes. Every task in every Molecule plugin must be
classifiable into one of them, and the API must name them so call sites cannot be
ambiguous. Describe tasks by **what state they touch**, never by "sync" vs "async" — the
sync/async vocabulary is Bukkit's and cannot express region ownership.

```text
1. Region thread      — owns blocks/chunks in one region of one world
2. Entity scheduler   — owns one entity/player, follows it across regions
3. Global region      — owns server-wide state (console, plugin lifecycle)
4. Plugin-owned pool  — owns Molecule's own in-memory state; touches no server state
```

Netty threads are a fifth context Molecule does not own but must handle: packets arrive
there. Treat Netty threads as read-only — never call the server from one.

## 1A.2 The Core execution bridge (REQUIRED Phase 1 deliverable)

Core must provide a documented, tested primitive for scheduling a callback onto a specific
region/entity/global scheduler from an arbitrary thread. This is load-bearing for RTP
(§26), worlds (§30), spawn waterfall (§29), NPCs (§37), live config (§59), and every
database read that ends in a player-visible effect.

The bridge must expose, at minimum:

```text
runForPlayer(Player, task)        → that player's EntityScheduler
runForEntity(Entity, task)        → that entity's EntityScheduler
runAtLocation(Location, task)     → RegionScheduler for that location
runGlobal(task)                   → GlobalRegionScheduler
runOffThread(task)                → Molecule's own pool
isOnOwnerThread(Entity) : boolean → assertion predicate
isOnOwnerThread(Location): boolean → assertion predicate
```

**`runForPlayer` must be the most prominent method in the API.** [VERIFIED] A scheduler
abstraction that omits an entity-scheduler entry point causes its callers to reach for the
region scheduler and get it wrong — this is an observed, repeated failure in shipped
plugins (§73.5). The `isOnOwnerThread` predicates exist so debug builds can assert
thread-correctness instead of discovering violations in production.

## 1A.3 Rules that follow from the model

**Never key a region scheduler on a sampled entity location.** [VERIFIED] This is the most
common Folia bug in shipped plugins, present in multiple otherwise-competent NPC plugins:

```text
WRONG:  runAtLocation(player.getLocation(), () -> doThingToPlayer(player))
RIGHT:  runForPlayer(player, () -> doThingToPlayer(player))
```

The location is sampled off-thread at schedule time. By the time the task runs, the player
may have crossed a region boundary, so the task executes on a thread that does not own that
player. Only the EntityScheduler re-resolves the entity's *current* owning region at
dispatch time.

**Never cache a region or thread reference.** Re-derive "where does this run" from the live
object on every call.

**Entity scheduling has three outcomes, not two.** The bridge must surface them:

```text
SUCCESS            — task ran
ENTITY_RETIRED     — entity removed/disconnected before the task ran
SCHEDULER_RETIRED  — scheduler shut down (plugin disabling)
```

[VERIFIED] Folia's own `EntityScheduler` performs the re-resolution; it does not retry.
No existing library ships a retry policy. **Molecule must define one** — per call site,
choose explicitly between drop, re-resolve-and-retry, and escalate. Silent no-op on a
retired entity is the correct default for cosmetic updates and the wrong default for
economy or persistence work.

**Coalesce all queued cross-thread work.** [VERIFIED] Any per-tick driver enqueueing onto a
slower thread builds an unbounded backlog. Guard every such path with a pending-key set:
add before scheduling, remove in a `finally`, skip if already present.

**Never read entity state off-thread.** `player.getLocation()` from a non-owning thread
reads mutable position fields while the owning region thread may be writing them. This
degrades to stale/torn reads rather than crashing, which is why it ships widely — it is
still a data race and it causes visible flicker. Core must instead publish an immutable
position snapshot per player:

```text
ConcurrentHashMap<UUID, PositionSnapshot>

PositionSnapshot = immutable record (world UID, x, y, z, yaw, pitch, timestamp)
  written  — from the player's own owning thread only
  read     — lock-free, from any thread
```

This gives coherent reads with a single writer and no scheduler hop in hot paths. Any
Molecule subsystem doing distance/visibility maths (NPC, holograms, particles, scoreboard,
spawn capacity, regions) must consume snapshots, not live entity reads.

**`Entity#teleport` does not exist on Folia.** [VERIFIED] Only `teleportAsync`. Teleport
completion timing is undefined, and the entity may belong to a different region thread
afterward — so post-teleport effects must be re-dispatched through `runForPlayer`, never
run on the future's continuation thread. Teleport contracts are specified in §25A.

**There is no "server started" main-thread tick on Folia.** [VERIFIED] Startup work must
listen for `io.papermc.paper.threadedregions.RegionizedServerInitEvent`, resolved
reflectively because it is absent from the Paper API. Do not use a delayed task as a
readiness heuristic.

**Platform detection** is `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`.
This is unanimous across every implementation surveyed.

## 1A.4 Build the bridge, do not adopt one

[VERIFIED] No serious plugin in this space uses a generic wrapper library (FoliaLib,
UniversalScheduler). They all hand-roll, because the generic wrappers model *Bukkit's task
vocabulary* (`runTimer`/`runTimerAsync`) rather than Folia's *ownership vocabulary*. Molecule
should write its own bridge in Core, following §1A.1's ownership model, and may study
FoliaLib's retired/fallback result contract as prior art (§73.2).

---

# 2. MOLECULE PLUGIN ECOSYSTEM

The ecosystem will eventually contain:

- molecule-core
- molecule-ui
- molecule-ranks
- molecule-tp
- molecule-worlds
- molecule-eco
- molecule-shop
- molecule-regions
- molecule-npc
- molecule-interactions
- molecule-stats
- molecule-skills
- molecule-scoreboard
- molecule-cosmetics
- molecule-motd
- molecule-particles
- molecule-holograms
- molecule-permissions functionality integrated into molecule-ranks/core
- future third-party Molecule integrations

Do not assume all plugins are installed.

Every plugin should be independently usable wherever technically possible.

Example:

A server may install:

```text
molecule-core
molecule-eco
molecule-shop
```

and nothing else.

That should work.

A server may install only:

```text
molecule-core
molecule-tp
```

and that should also work.

If an optional integration is missing, the plugin should gracefully disable only the dependent functionality rather than crash.

---

# 3. MOLECULE CORE

Molecule Core is the foundation of the entire ecosystem.

Core owns:

- plugin/service registry
- Molecule API
- database connection infrastructure
- database migrations
- configuration infrastructure
- audit logging
- live configuration updates
- HTTP server
- REST API
- WebSocket API
- authentication
- player identity/profile infrastructure
- Molecule Tags
- universal text infrastructure
- resource-pack management
- resource-pack building
- asset management
- action registry
- variable registry
- shared events/interfaces
- **Folia execution bridge (§1A.2)**
- **player position snapshot service (§1A.3)**
- common Folia-safe utilities
- permissions/rank integration interfaces
- third-party developer API
- web-panel backend
- API authentication
- server identity
- server metadata
- logging/diagnostics

Core should not contain every plugin's business logic.

It provides infrastructure that plugins consume.

---

# 4. DATABASE ARCHITECTURE

MariaDB is the **source of truth** for live Molecule configuration and persistent Molecule state.

Do NOT make YAML and MariaDB equal sources of truth.

## Source of truth

```text
MariaDB
   ↓
Live Molecule configuration
```

The web UI edits MariaDB.

Plugins receive live configuration changes and update themselves without requiring a restart.

## YAML

YAML exists for:

- bootstrap configuration
- initial database connection
- server startup configuration
- import/export
- backups/manual editing
- portability
- debugging

YAML is NOT the canonical source of live configuration.

Provide mechanisms for:

```text
Export configuration → YAML
Import YAML → MariaDB
```

Importing configuration must itself be an auditable change.

If an administrator manually modifies a YAML file that is not being used as the active configuration source, do not silently pretend that it is authoritative.

---

# 5. DATABASE CONNECTION

Molecule Core owns the database connection/service.

Plugins should not independently create arbitrary MariaDB connection pools.

Plugins request database functionality from Core.

Support:

### Built-in Molecule database

The normal/default configuration.

### External MariaDB

Administrators can provide:

- host
- port
- database
- username
- password
- SSL settings
- connection options

The database provider should be configurable through the web UI and bootstrap configuration.

Each Molecule plugin gets its own database namespace/tables.

Example:

```text
molecule_core_*
molecule_ui_*
molecule_tp_*
molecule_worlds_*
molecule_eco_*
molecule_shop_*
molecule_regions_*
molecule_ranks_*
molecule_npc_*
molecule_stats_*
molecule_skills_*
molecule_scoreboard_*
molecule_cosmetics_*
```

A plugin may have tables that are never used if that plugin is not installed.

This is acceptable.

Use migrations.

Never assume the database schema will always be at the latest version.

Implement versioned migrations and safe startup migration handling.

---

# 6. AUDIT LOG

Every configuration/admin change must be permanently logged.

Examples:

```text
Changed RTP radius:
10000 → 25000

Changed UI slot 22:
DIAMOND → COMPASS

Changed VIP home limit:
3 → 10

Changed XP multiplier:
1.0 → 0.75
```

Audit records should include, where applicable:

- timestamp
- actor
- Minecraft UUID
- actor type
- plugin
- object/entity
- setting/property
- previous value
- new value
- operation
- revision ID
- source
- relevant context

Never overwrite audit history.

Undo should create a new auditable change rather than deleting history.

Example:

```text
Revision 103:
VIP home limit 3 → 10

Revision 104:
Undo revision 103
VIP home limit 10 → 3
```

Provide an audit interface in the web panel with filtering and rollback.

---

# 7. GAMEPLAY EVENT HISTORY

Molecule Stats should permanently track meaningful gameplay events.

Examples include:

- player joins
- player leaves
- player deaths
- player chat
- commands
- block breaking
- block placing
- block interactions
- inventory interactions
- chest interactions
- chest sorting
- item movement where useful
- crafting
- smelting
- enchanting
- player interactions
- entity interactions
- NPC interactions
- damage
- kills
- mob deaths
- item pickup
- item drop
- farming actions
- breeding
- teleportation
- RTP
- homes
- claims
- shops
- economy transactions
- skill progression
- rank changes
- cosmetic changes
- world interactions
- plugin-specific events

Do not attempt to store every Minecraft tick or every movement packet forever.

Movement should be tracked as useful time/activity data rather than millions of meaningless rows.

Use:

- raw event history for meaningful events
- aggregated statistics for counters
- time-series/session records for activity/playtime

All meaningful events remain available forever.

---

# 8. PLAYER PROFILE

Molecule Core should provide a unified player-profile framework.

The web UI should have a player profile such as:

```text
Player
├── Overview
├── Activity
├── Stats
├── Skills
├── Rank
├── Permissions
├── Cosmetics
├── Economy
├── Homes
├── Claims
├── Shops
├── Discord
└── Plugin-specific information
```

Plugins should be able to register profile sections.

The profile is a platform concept rather than being owned exclusively by one plugin.

Molecule Ranks should manage the Users/Groups/Ranks/Permissions side of the profile.

---

# 9. MOLECULE API

Create a clean public developer API.

Third-party plugins should be able to integrate with Molecule without depending on implementation details.

Conceptually:

```java
MoleculeAPI.get().players()
MoleculeAPI.get().ranks()
MoleculeAPI.get().economy()
MoleculeAPI.get().stats()
MoleculeAPI.get().skills()
MoleculeAPI.get().regions()
MoleculeAPI.get().ui()
MoleculeAPI.get().actions()
MoleculeAPI.get().variables()
```

Do not necessarily use those exact method names; design a clean API.

Third-party developers should be able to register:

- services
- actions
- events
- stats
- skills
- variables
- UI components
- commands
- integrations
- profile sections

Document the public API.

Avoid exposing internal implementation classes as the public API.

---

# 10. PLUGIN ARCHITECTURE

Every Molecule plugin should conceptually expose:

```text
Plugin
├── Commands
├── Service/API
├── Events
├── Actions
├── Database
├── Configuration
├── Permissions
├── Web UI integration
├── Stats integration
├── Skills integration where relevant
└── Profile integration where relevant
```

Plugins should register their capabilities with Core.

Do not hard-code Molecule UI around individual plugins.

For example, molecule-ui should discover registered actions from molecule-tp rather than containing hardcoded molecule-tp code.

---

# 11. ACTION SYSTEM

Create a universal Molecule Action Registry.

Plugins register actions.

Example:

```text
molecule.tp.teleport
molecule.tp.rtp
molecule.tp.home
molecule.eco.give
molecule.shop.open
molecule.ui.open
molecule.rank.check
```

Actions can expose parameters.

Example:

```text
Action:
Molecule TP → Teleport

Parameters:
Destination
World
```

The web panel should automatically generate appropriate parameter editors from the action's declared metadata.

Actions can be used by:

- UI buttons
- NPCs
- block interactions
- item interactions
- holograms
- commands
- other plugins
- future integrations

Actions can have conditions.

Possible condition types include:

- rank
- permission
- level
- XP
- stat
- skill
- currency
- world
- biome
- item
- claim ownership
- region
- time
- custom Molecule variable

---

# 12. EVENTS

Do NOT create a separate events plugin.

Events are built into the relevant plugins.

Examples:

```text
ShopPurchaseEvent
ShopCreateEvent
ShopOutOfStockEvent

RTPEvent
HomeCreateEvent
HomeDeleteEvent
TPARequestEvent

ClaimCreateEvent
ClaimDeleteEvent

RankChangeEvent
LevelUpEvent

SkillLevelUpEvent
SkillXPEvent

NPCInteractEvent
UIOpenEvent
UIActionEvent
```

Use standard Molecule event conventions.

Events should be usable by third-party developers.

---

# 13. MOLECULE VARIABLES

Create a shared variable/tag system.

Examples:

```text
<molecule.player.name>
<molecule.player.nickname>
<molecule.player.real_name>
<molecule.player.rank>
<molecule.player.level>
<molecule.player.xp>
<molecule.player.balance>

<molecule.skill.mining.level>
<molecule.skill.mining.xp>

<molecule.server.online>
<molecule.server.tps>

<molecule.world.name>
<molecule.world.biome>
```

Allow plugins to register their own variables.

Allow administrators to create custom variables where appropriate.

Variables must work with:

- UI
- NPCs
- holograms
- scoreboards
- tab
- MOTD
- messages
- conditions
- actions
- rank formatting
- cosmetics

---

# 14. UNIVERSAL TEXT ENGINE

Use MiniMessage as the underlying modern text format where appropriate.

Create a Molecule text abstraction on top of it.

Support:

- MiniMessage
- hex colors
- gradients
- bold
- italic
- underline
- strikethrough
- obfuscated
- custom symbols
- Unicode
- custom fonts
- custom Molecule tags
- dynamic variables
- alignment
- line-by-line editing
- selected-text formatting

The web UI should have a visual text editor.

For each line support:

```text
Left
Center
Right
```

Allow selecting text and applying:

```text
Bold
Italic
Underline
Strikethrough
Color
Gradient
Font
```

Allow generating gradients from:

```text
Start color
End color
```

This universal text system should be reused everywhere in Molecule.

---

# 15. RESOURCE PACK SYSTEM

Molecule Core owns the server resource-pack pipeline.

Support:

- uploaded textures
- PNG assets
- TTF
- OTF
- models
- custom item textures
- custom model data
- fonts
- sounds
- particles
- rank tags
- UI assets
- cosmetic assets
- NPC assets

Users can upload their own assets.

Molecule can also generate/manage assets.

Support importing an existing/base resource pack.

Recommended pipeline:

```text
Base/existing resource pack
        ↓
Molecule asset system
        ↓
UI assets
Ranks
NPCs
Cosmetics
Particles
Custom items
Fonts
Sounds
        ↓
Generated resource pack ZIP
```

Molecule should:

- generate the ZIP
- host the ZIP
- calculate SHA-1
- version the pack
- invalidate/update caches
- detect asset changes
- optionally retain previous versions
- send it to players
- allow force-resource-pack configuration

Resource pack forcing is:

```text
Default: ON
Configurable: YES
```

Molecule should host the resource pack through the Core HTTP server.

---

# 16. MOLECULE HTTP SERVER

Molecule Core should run its own configurable HTTP server.

One configurable port should expose:

```text
/panel
/api/v1/...
/ws
/resourcepack/...
```

The server should support:

- web panel
- REST API
- WebSocket
- resource-pack hosting
- authentication
- API authentication

It should work behind nginx/Cloudflare/reverse proxies.

Do not require a separate web server.

---

# 17. WEB PANEL

The web UI is a major part of Molecule.

Use a modern frontend stack such as:

- React
- TypeScript
- Vite
- Tailwind or equivalent
- modern component library where useful

Do not add paid dependencies/services.

The web panel should be served by Molecule Core.

Authentication should use Minecraft identity, not a separate Molecule password system.

Prefer temporary authenticated login links/codes.

Example:

```text
/molecule panel
```

generates a temporary web login link.

The user opens it and is authenticated.

The web panel is considered trusted/admin-level access according to the configured Minecraft/Molecule permissions model.

The panel should never require a server restart for configuration changes.

Use WebSockets/live updates where appropriate.

---

# 18. WEB PANEL NAVIGATION

Use a sidebar.

Conceptually:

```text
Dashboard

Players

Ranks
Permissions

UI

Teleportation
Worlds

Economy
Shops

Regions
Claims

NPCs
Interactions

Stats
Skills

Scoreboard
Tab

Cosmetics
Particles
Holograms
MOTD

Resource Pack

Discord

Audit Log

System
```

Within each plugin, show its registered configurable objects/devices/entities.

Example:

```text
Teleportation
├── RTP
├── Homes
├── Spawn
└── TPA
```

Then selecting RTP exposes all RTP configuration.

Do not make the panel one giant settings page.

---

# 19. MOLECULE UI

Molecule UI is a Minecraft inventory/chest UI designer.

It must support Minecraft items, blocks, custom textures, custom models, and Molecule-managed resource-pack assets.

The web editor should visually represent chest inventories.

Support:

- 9-slot
- 18-slot
- 27-slot
- 36-slot
- 45-slot
- 54-slot

Allow placing items into slots.

Each UI element can have:

- material
- custom model data/model
- texture
- title
- lore
- font
- color
- gradient
- symbols
- custom tags
- conditions
- actions
- animations

Uploaded images should be processed into appropriate square asset formats for use in Molecule's resource-pack system.

Support user-uploaded assets as well as real Minecraft assets.

---

# 20. UI ANIMATIONS

Molecule UI must support animations.

Start with a flexible animation system rather than hardcoding only a few effects.

Support things such as:

- item cycling
- slot changes
- lore changes
- title changes
- countdowns
- pulsing
- blinking
- animated gradients
- progress bars
- timed transitions

Design the underlying system so future animation types can be added.

A timeline/keyframe-capable representation is preferred if it does not create unnecessary complexity.

---

# 21. UI ACTIONS

Every button can trigger Molecule Actions.

Example:

```text
Button
 ↓
molecule.tp.rtp
```

or:

```text
Button
 ↓
molecule.shop.open
```

or:

```text
Button
 ↓
molecule.ui.open
```

Do not hardcode plugin-specific button behavior inside molecule-ui.

Plugins register actions.

Molecule UI discovers them.

---

# 22. COMMAND FALLBACK

Every Molecule plugin must remain functional through Minecraft commands if Molecule UI is:

- not installed
- disabled
- unavailable
- temporarily broken

UI is an interface, not a requirement.

Examples:

```text
/rtp
/home
/sethome
/tpa
/shop
/auction
/mw
/claim
```

should still work without the in-game UI.

---

# 23. MOLECULE RANKS

Molecule Ranks replaces LuckPerms.

Ranks directly contain permissions.

Ranks have:

- name
- display name
- weight
- inheritance
- permissions
- prefix
- suffix
- chat formatting
- tab formatting
- nametag formatting
- cosmetic permissions
- RTP permissions
- RTP cooldowns
- home limits
- home cooldowns
- action costs
- shop permissions
- claim limits
- skill multipliers
- XP multipliers
- other Molecule-specific limitations

Attributes should support inheritance.

If a child rank does not define a property, inherit it from its parent chain.

Ranks should NOT have manual player overrides.

Rank progression is tied to XP/requirements.

Admins may modify a player's XP.

Admins may NOT simply assign an arbitrary rank to bypass the progression system.

Changing XP naturally changes the player's rank.

Support automatic rank thresholds such as:

```text
20,000 global XP → VIP
50,000 global XP → MVP
```

---

# 24. MOLECULE COSMETICS

The former "molecule-customization" plugin is named:

**molecule-cosmetics**

Support:

- trails
- halos
- particles
- name colors
- name formatting
- nicknames
- tab appearance
- nametag appearance
- other player cosmetics

Nicknames can use Molecule's universal text system.

Example:

```text
Real name:
Lukas

Nickname:
<gradient:#ff00ff:#00ffff>Lukas</gradient>
```

Cosmetic permissions should integrate directly with Molecule Ranks.

---

# 25. MOLECULE TP

Provide:

```text
/rtp
/rtp <category>

 /sethome
/sethome <name>

/home
/home <name>
/home bed

/listhome
/delhome <name>
/renamehome <old> <new>

/tpa
/tpahere
/tpaccept
/tpdeny
```

## Homes

Default:

```text
/sethome
```

creates:

```text
home
```

Maximum homes are rank-configurable.

Home cooldowns and costs are rank/config configurable.

If enabled, `/home bed` can teleport to the player's bed.

`/listhome` should provide clickable chat options.

---

# 25A. TELEPORT CONTRACT

Every teleport in Molecule — homes, TPA, RTP, spawn, waterfall hubs, admin commands —
follows one contract, provided by Core and used by all plugins.

**All teleports are `teleportAsync`.** [VERIFIED] Folia has no synchronous teleport. This
holds even when the destination chunk is already loaded — the target region may be owned by
a different thread than the caller. Do not add a "chunk already loaded" fast path; upstream
plugins have had to remove exactly that optimisation.

**Post-teleport effects re-dispatch through the entity scheduler:**

```text
teleportAsync(destination)
   ↓ completes on an undefined thread
runForPlayer(player, () -> postTeleportEffects())
```

Do not run effects on the future's continuation thread. After the teleport resolves the
player may belong to a different region thread.

**Every teleport is cancellable and re-entrant-safe.** Core must provide, not each plugin:

- an in-flight guard keyed by player UUID, so a second `/rtp` cannot start mid-teleport
- cancellation on movement, world change, damage, or disconnect during any warmup/countdown
- a defined outcome when the player disconnects mid-flight (entity-scheduler tasks
  silently no-op on a retired entity — that is the disconnect-safety mechanism, but the
  economy/cooldown side effects must be explicitly resolved, not left to the no-op)

**Warmups and countdowns are objects with lifecycle, not chained futures.** They span
multiple ticks and must be individually cancellable and unregisterable.

---

# 26. RTP

> **[VERIFIED] Risk flag — this is the most ambitious section in the document.**
> No surveyed RTP plugin pre-scans a world into a database. Every real implementation
> tests one candidate at a time, on demand, off-thread. The indexed-location product
> features in §27–28 are achievable, but the *acquisition strategy* below has been
> revised: the index is filled incrementally and opportunistically, never by a
> synchronous grid sweep. Treat full-world prescanning as an R&D spike (§63, Phase 5),
> not as assumed-working infrastructure.

RTP is backed by a persistent location database, filled by an incremental scanner and
topped up on demand.

The scanner checks coordinates at a configurable grid spacing.

Example:

```text
Grid spacing = 4

x = ..., -8, -4, 0, 4, 8, ...
z = ..., -8, -4, 0, 4, 8, ...
```

Each candidate is evaluated for safety.

Safe location criteria include:

- solid block underneath
- sufficient air above
- not lava
- not water where prohibited
- not leaves where prohibited
- not cactus
- not powder snow
- not dangerous blocks
- safe slope
- configurable additional rules

Every safety rule must be configurable.

RTP scan:

- radius configurable
- shape configurable
- grid spacing configurable
- live reconfiguration
- rescanning without restarting
- support scanning beyond pregenerated area if required
- never block Folia region threads

## Scanner execution model [VERIFIED]

The scanner runs entirely on Molecule's own pool (thread class 4), never on a region
thread. Candidate evaluation must use chunk snapshots, not live block reads:

```text
runOffThread:
    pick candidate (x, z)
    world.getChunkAtAsync(x >> 4, z >> 4)
        → ChunkSnapshot
        → evaluate safety rules against the snapshot
    if safe   → persist to index
    if unsafe → next candidate, up to a configurable attempt cap
```

Blocking on the chunk future is acceptable *because this is not a region thread*. A
`ChunkSnapshot` is a coherent copy, so safety evaluation is thread-safe; live block reads
from the scanner thread are not.

Mandatory constraints:

- **Bounded concurrency.** Chunk loading is per-region work. A scanner that iterates a grid
  without throttling will force-generate chunks across an unbounded number of regions and
  starve the server. Cap in-flight chunk loads globally and per-world, configurable, with a
  conservative default.
- **Incremental and resumable.** Persist scan progress. A scan must survive restart and be
  pausable, and must never run as a single blocking sweep.
- **Yield under load.** Suspend or slow scanning when server tick health degrades. Expose
  the throttle in the web UI.
- **On-demand fallback.** If the index is empty or exhausted for a requested category,
  fall back to live on-demand candidate testing rather than failing the command. This path
  must always exist — it is the proven-correct baseline, and the index is an optimisation
  layered on top of it.

Store:

- world
- coordinates
- dimension
- biome
- category
- index
- verification time
- usage information
- other metadata useful for selection

---

# 27. RTP BIOMES/CATEGORIES

Support biome categories such as:

```text
Forests
Plains
Caves
Oceans
Deserts
Mountains
etc.
```

Each category contains individual biomes.

Every biome in every supported Minecraft dimension must be individually enableable/disableable.

Admins can:

- enable/disable biomes
- exclude biomes
- weight biomes
- define categories
- configure safety rules
- configure scan rules

Weights:

```text
0% = disabled
```

---

# 28. RTP LOCATION INDEXING

Locations must have persistent indexes.

Example:

```text
cherry_grove_001
cherry_grove_002
cherry_grove_003
```

A player should never receive the same indexed location twice unless the entire available selection for that category is exhausted.

Random RTP should also avoid repeating locations.

"Recently used" is effectively permanent history.

If all valid locations have already been used, the selection system may reuse locations.

Selection should be deterministic enough to avoid obvious repetition but still feel random.

---

# 29. SPAWN

Support first-join behavior:

- fixed spawn
- random spawn
- configured spawn selection

Support multiple spawn hubs.

Example:

```text
Hub 1
Hub 2
Hub 3
...
Hub 10
```

Each can have a player capacity.

If a hub reaches capacity:

```text
Hub 1 full
 ↓
Hub 2
 ↓
Hub 3
```

This is the Molecule "waterfall" system.

**Waterfall capacity must be tracked in Core state, not computed by polling worlds.**
[VERIFIED] Hub occupancy is cross-region data — counting players per hub by reading live
entity/world state from an arbitrary thread is a data race, and the answer is stale by the
time the teleport resolves anyway. Maintain an authoritative in-memory occupancy map
updated from join/quit/teleport-completion events, and reserve a slot *before* starting the
teleport so two simultaneous joins cannot both fill the last space. Release the reservation
if the teleport fails or the player disconnects mid-flight (§25A).

Worlds can also participate in spawn groups.

On death:

- if valid bed/anchor exists, preserve vanilla behavior
- otherwise execute configured spawn/lobby/hub behavior

Commands such as:

```text
/spawn
/lobby
/hub
```

should be configurable.

---

# 30. MOLECULE WORLDS

> **[VERIFIED] Highest-risk plugin in the ecosystem. Read §30.1 before writing any code.**
> Runtime world management is not supported by Folia's public API. Every feature in this
> section requires NMS internals and reflection. One sub-feature — live gamerule editing —
> has **no known working implementation anywhere**, open-source or otherwise.
> Multiverse-Core's upstream position is that Folia support is "not possible for now."

Molecule Worlds is a Folia-compatible world management system.

Conceptually similar to Multiverse, but designed specifically for Molecule/Folia.

## 30.1 What the public API cannot do

[VERIFIED] The following are stubbed, throwing, or absent on Folia:

```text
Bukkit.createWorld() / WorldCreator#createWorld  — stubbed, does not work
Bukkit.getWorlds()                               — unreliable
World#setGameRule()                              — does not work
RegionizedServer#removeWorld()                   — does not exist (no public unload path)
```

Do not design any feature on the assumption that these work. Do not write a fallback that
calls them and logs a warning — write the real path or mark the feature unsupported.

## 30.2 The proven creation path

Two independent open-source Folia world managers (§73.1) converge on the same technique:
bypass Bukkit entirely and reproduce the server's own world-boot sequence.

```text
build WorldGenSettings / LevelStem / PrimaryLevelData
   ↓
construct ServerLevel directly
   ↓
console.addLevel(level)
console.initWorld(level, creator)
console.prepareLevel(level)
   ↓
start entity ticking for the new level   ← Folia-specific, no-op on Paper
```

The final step is required on Folia and easy to miss: a world created this way has no
entity ticking until it is started explicitly.

## 30.3 The proven unload/delete path

Unloading is harder than creating and must be staged:

```text
1. snapshot chunk holders from the level's chunk holder manager
2. save each chunk via the RegionScheduler, ON ITS OWNING REGION THREAD
3. mark every region-scheduling handle non-schedulable
4. saveLevelData(), then remove the level    — on GlobalRegionScheduler
```

Step 2 is the whole difficulty: chunks belong to different threads and must each be saved
by their owner. Step 4 requires reflection into private world-map fields on both the
`RegionizedServer` and `CraftServer`, because no public removal API exists.

Mandatory: a timeout guard on the whole sequence, refusal to unload the primary overworld,
and a require-empty-player-list precondition.

## 30.4 Gamerules — unsolved, R&D spike required

[VERIFIED] Neither reference implementation implements gamerule editing at all. Zero
occurrences in either codebase. There is no reference implementation to copy.

Live gamerule editing through the web UI (§30, §31) must be treated as an **open research
problem with a defined fallback**, scheduled as its own spike before Phase 4 commits to it:

- investigate routing gamerule writes through the `GlobalRegionScheduler`, or per-region
  writes analogous to the save/unload pattern in §30.3
- if no safe path is found, the fallback is **gamerules are set at world creation and on
  world load only**, with the web UI queuing changes for next load rather than applying
  them live
- the UI must never present a queued change as if it were applied (§4's rule against
  pretending a non-authoritative source is authoritative applies here)

## 30.5 Version coupling

The NMS boot sequence is version-specific and will break on Minecraft updates. Isolate all
of it behind a versioned handler interface with one implementation per Minecraft version,
exactly as the reference implementations do. Molecule Worlds is the only place in the
ecosystem permitted to depend on NMS internals; nothing else may reach into them.

Commands:

```text
/mw
/moleculeworlds
/moleculeworlds create
/moleculeworlds delete
/moleculeworlds import
```

Support creation:

```text
/create <name> <dimension> <generator> ...
```

Dimensions:

- overworld
- nether
- end

Generators:

- normal
- flat
- void
- water
- lava
- configurable custom variants

Support:

- block type for flat worlds
- seed
- random seed
- custom generation options
- gamerules
- difficulty
- PvP
- mob spawning
- weather
- time
- structures
- datapacks
- world border
- other useful world settings

World settings are managed through the web UI.

---

# 31. WORLD GROUPS

Worlds can be grouped.

Example:

```text
Survival Hubs
├── hub-01
├── hub-02
├── hub-03
└── hub-04
```

A world group can share configuration.

Changing group settings can propagate to all member worlds.

Groups are useful for:

- waterfall spawn hubs
- duplicated worlds
- shared gamerules
- shared difficulty
- shared gameplay settings

---

# 32. WORLD IMPORT

Support importing a world folder or ZIP placed in a designated worlds directory.

Normalize imported worlds into Molecule's structure.

Import should:

- validate
- extract
- normalize
- register in MariaDB
- generate Molecule metadata
- process relevant datapack/world data
- record the operation
- load the world

Deleted worlds should NOT immediately disappear.

Move them into something like:

```text
deleted/trash/
```

with metadata so accidental deletion can be recovered manually.

Worlds cannot simply be unloaded as a normal operation.

---

# 33. MOLECULE ECO

Molecule Eco provides the only economy system supported by Molecule Shop.

Molecule Shop does NOT integrate with arbitrary third-party economy plugins.

Economy should support:

### Virtual currency

Configurable:

- name
- symbol
- decimals
- formatting
- starting balance
- maximum balance
- transaction rules

### Item currency

Support Minecraft items as currencies.

Examples:

- diamond
- diamond block
- diamond ore
- gold ingot
- custom items
- custom coin items

Currency definitions are configurable through the web UI.

Support custom items and NBT/custom-model items.

Support configurable stacking behavior.

Example custom coin:

```text
1000 coins per stack
```

If virtual currency is enabled, item currency may be banked/converted into the virtual system according to configuration.

Molecule Eco must provide permanent transaction history.

---

# 34. MOLECULE SHOP

Molecule Shop requires Molecule Eco.

It does not support other economy plugins.

Support:

- player chest shops
- auction house
- GUI interfaces
- Molecule UI
- command fallback

## Chest Shops

Flow:

```text
Place chest
Place sign
Write shop indicator
```

Sign format:

```text
SHOP
<quantity>
<auto-populated item>
<price>
```

Only one item type can be stored in a shop.

Support:

- armor
- enchantments
- names
- custom items
- NBT

Items must match according to the configured item-equality rules.

The shop owner specifies the batch quantity.

No partial purchases.

Examples:

```text
1 diamond → 64 planks
64 diamonds → 1 stack of rare item
1 item → 1 item
```

If stock reaches zero:

```text
OUT OF STOCK
```

The owner receives a notification.

Notify owners about:

- purchases
- sales
- out-of-stock
- other configurable shop activity

Notifications should work online and offline.

Auction house is GUI-based and uses Molecule UI.

---

# 35. MOLECULE REGIONS

Create:

**molecule-regions**

It manages both:

- admin regions
- player claims

Claims are a specialized region type.

Admin regions can control:

- building
- breaking
- interaction
- containers
- doors
- PvP
- projectiles
- mob damage
- mob spawning
- fire
- explosions
- redstone
- fluids
- entry
- exit
- commands
- other relevant gameplay behavior

Player claims support:

- ownership
- trust
- untrust
- subdivisions
- subdivision permissions
- claim blocks
- claim purchases
- claim selling
- XP-earned claim blocks

Subdivisions can have permissions such as:

```text
Enter
Interact
Open containers
Use doors
Break
Place
PvP
```

Claims use the same underlying region engine as admin regions.

---

# 36. CLAIM BLOCKS

Claim blocks are a separate resource/currency.

Players can obtain them through:

- XP
- playtime
- purchases
- other configurable systems

Molecule Stats/Skills should provide the relevant progression data.

All claim-block values are configurable.

---

# 37. MOLECULE NPC

## 37.1 NPCs are packet entities, not real entities [VERIFIED]

This is a binding architectural decision, not an implementation preference.

A packet NPC exists only as client-side state driven by Molecule. It has no server entity,
no chunk, and therefore **no owning region**. This is precisely why it works well on Folia:
it sidesteps regionization entirely. No surveyed packet-NPC plugin registers a single
chunk-load listener, because packet entities do not live in chunks.

The real-entity alternative was surveyed and rejected. Citizens, which uses real entities,
must schedule every NPC tick onto the owning entity's thread, cannot answer world-unload
synchronously, and **silently disables its entire packet-rewriting subsystem on Folia**
while still declaring `folia-supported: true` — because a packet handler that must consult
a real entity eats a scheduler hop and a tick of latency. Molecule must not inherit that
cost model.

Consequences:

- Do not tick NPCs on a region scheduler. An NPC bound to a region stops updating when that
  region's chunks unload — the opposite of what an NPC is for.
- Do not register chunk-load listeners for NPCs.
- Resolve worlds by name at load. If a world is absent, hold the NPC in a pending set and
  retry on world load. Do not attempt to create the world (§30.1).

## 37.2 NPC threading contract [VERIFIED]

```text
NPC thread (class 4)   — single-threaded executor, sole owner of ALL NPC state:
                         positions, properties, viewer sets, per-viewer flags.
                         Every mutation happens here and only here.

Netty threads          — interact packets arrive. O(1) lookup by entity ID, then
                         hand off immediately. Never call the server from here.

EntityScheduler        — anything touching a player: commands, chat, teleports,
                         inventory, sounds, velocity, non-async events.

GlobalRegionScheduler  — server-wide state and startup only.
```

Use `scheduleWithFixedDelay`, never `scheduleAtFixedRate` — a rate-based schedule with a
slow tick builds an unbounded backlog.

**Send packets directly from the NPC thread.** Netty marshals the write onto the
connection's event loop and preserves per-connection ordering, so no hop is needed. The
connection is owned by its event loop, not by a region thread. Prefer a packet library's
channel-level send over NMS `connection.send`, which relies on undocumented thread-safety.

**Never read `player.getLocation()` from the NPC thread.** Consume Core's position
snapshots (§1A.3). This is the single most widespread bug in surveyed NPC plugins, and the
cause of the visibility flicker they all work around.

**Index NPCs by entity ID in a `ConcurrentHashMap`.** A linear scan per interact packet on
the Netty thread is a real throughput hazard at scale, and is what one surveyed plugin does.

**Serialize viewer-set mutations.** Either keep the single-writer NPC thread for all shared
state, or make every shared structure concurrent — pick one and enforce it. Do not do what
one surveyed plugin does and hop to per-viewer entity schedulers while leaving the viewer
set a plain `HashSet`; that fixes a read race by introducing a write race across N threads.

## 37.3 Interaction handling [VERIFIED]

Prefer Paper's `PlayerUseUnknownEntityEvent` where available. Paper fires it from the
packet handler, which on Folia already runs on the player's owning region thread — so it
delivers correct-thread dispatch, entity-ID resolution, and off-hand deduplication for
free, with no packet-library dependency and no scheduler code.

Fall back to a packet listener only where that event is unavailable. In that fallback, hop
to the player's entity scheduler before touching anything.

Molecule NPC events fired from the NPC thread must be **marked async**, and the contract
documented. Fire non-async events only from inside an entity-scheduler callback.

## 37.4 Visibility [VERIFIED]

Do not blind-respawn NPCs on a timer to fix visibility. Multiple surveyed plugins ship a
"despawn and respawn after 100 ms" workaround for the same underlying bug: a remove and an
add for the same entity ID reaching the client too close together, where the client
processes the removal last and drops the entity. Folia makes this far more likely because
the driver thread and the connection's event loop are fully decoupled.

Fix the cause instead:

- **Hysteresis** — show at distance `d`, hide at `d + margin`. Kills boundary oscillation.
- **Minimum dwell time** per (NPC, viewer) pair before a hide following a show.
- **Never emit remove-then-add for the same entity ID in quick succession.** If a genuine
  respawn is required, allocate a fresh entity ID.

The same three rules apply to Molecule Holograms (§46), which shares this failure mode.

## 37.5 Capabilities

NPCs can support:

- player NPCs
- hostile mobs
- passive mobs
- neutral mobs
- friendly entities
- custom models
- items as interactive objects
- skins
- custom textures
- animations where possible

NPCs can be interacted with.

NPCs support conditional behavior.

Example:

```text
If player has VIP:
    show VIP perk

If player has MVP:
    show MVP perk

otherwise:
    show locked perk
```

Conditions use Molecule's condition/variable system.

NPCs can invoke Molecule Actions.

---

# 38. MOLECULE INTERACTIONS

Support interactions with:

- NPCs
- blocks
- items
- entities

Triggers:

- left click
- right click
- break
- place
- walk over
- other sensible interaction triggers

Actions are handled through the universal Molecule Action Registry.

---

# 39. MOLECULE STATS

Molecule Stats tracks player attributes and events.

It should be extremely extensible.

Plugins can register their own stats.

Stats can be:

- counters
- timestamps
- durations
- aggregates
- histories
- time-series
- custom data

Track things such as:

- first join
- last join
- last leave
- total playtime
- sessions
- blocks broken
- blocks placed
- mobs killed
- deaths
- crafting
- interactions
- messages
- commands
- player interactions
- chest interactions
- shop activity
- claims
- RTP
- economy
- skills
- etc.

Provide configurable tracking levels.

Admins should be able to control high-volume telemetry.

Provide a player activity page in the web UI.

---

# 40. MOLECULE SKILLS

Built-in skills:

- Farming
- Mining
- Woodcutting
- Combat
- Fishing
- Building
- Adventuring
- Alchemy

Skills have:

- XP
- levels
- configurable XP curves
- perks
- level requirements
- rewards
- currency rewards
- global/network XP rewards
- configurable multipliers

Perks are generic and configurable.

Example:

```text
Woodcutting Level 10
→ Tree Feller
```

A perk can invoke Molecule Actions.

Third-party plugins may register skills.

Molecule itself should provide the above core library but should not hardcode every possible perk.

---

# 41. NATURAL-GENERATION XP

Skills must distinguish naturally generated blocks from player-placed blocks.

Example:

```text
Natural stone broken → Mining XP
Player-placed stone broken → no Mining XP
```

Same concept for:

- ores
- logs
- other generated resources

Prevent obvious XP exploits.

Farming XP must be configurable by activity.

Examples:

```text
plant wheat
harvest wheat
plant carrots
harvest carrots
breed cow
kill cow
```

Harvesting only gives XP when the crop is actually mature/harvestable.

For multi-stage crops such as sugar cane, award XP for appropriate mature harvestable segments.

All XP values and enable/disable settings are configurable.

---

# 42. XP SYSTEM

Build a general XP framework.

XP can come from:

- Minecraft gameplay
- Discord activity
- skills
- plugin events
- configurable rewards

Use configurable multipliers.

Example:

```text
Base XP
× activity multiplier
× skill multiplier
× rank multiplier
× global multiplier
= final XP
```

Changing a multiplier must affect future XP generation only.

Do not recalculate historical XP merely because a multiplier changed.

Admins can directly adjust player XP.

Rank progression is based on the resulting XP.

---

# 43. MOLECULE SCOREBOARD

Create:

**molecule-scoreboard**

This plugin manages both:

- scoreboard
- tab list

Use Molecule Tags and MiniMessage.

Support configurable lines.

Each line can be:

- left
- center
- right

Use the universal text editor.

Support:

- gradients
- fonts
- variables
- player information
- ranks
- levels
- skills
- stats
- economy
- server information

---

# 44. MOLECULE COSMETICS

See section above.

Cosmetics should use Molecule's resource-pack system where appropriate.

Support:

- trails
- halos
- particles
- name formatting
- nicknames
- tab formatting
- nametag formatting
- future cosmetics

---

# 45. MOLECULE PARTICLES

Admin tool for placing/configuring particles.

Support:

- locations
- particle type
- amount
- offsets
- speed
- animation
- conditions
- custom resource-pack particles where possible

Use the server's Molecule resource pack.

---

# 46. MOLECULE HOLOGRAMS

Holograms are packet entities and **must reuse the Molecule NPC engine's threading
contract in full** (§37.2–37.4): plugin-owned driver thread, position snapshots instead of
live location reads, hysteresis and fresh entity IDs instead of blind respawn, no chunk
listeners. [VERIFIED] Holograms and NPCs hit identical Folia failure modes in every
surveyed codebase; do not implement two separate engines with two separate sets of bugs.

Support configurable holograms.

Use universal Molecule text.

Support:

- MiniMessage
- tags
- gradients
- fonts
- dynamic values
- animations
- conditions
- interaction/actions where appropriate

---

# 47. MOLECULE MOTD

Manage MOTD and related server presentation through Molecule.

Use universal text.

Support dynamic tags.

---

# 48. COMMAND/PERMISSION SYSTEM

Molecule Ranks replaces LuckPerms.

Support:

- groups
- inheritance
- weights
- permissions
- temporary permissions
- prefixes
- suffixes
- command filtering
- command hiding
- command autocomplete filtering
- world-specific permissions where appropriate

Command visibility/autocomplete should reflect the user's effective permissions and inherited rank.

Do not expose commands the player cannot use.

---

# 49. DISCORD ECOSYSTEM

Create a separate self-hosted Python Discord bot/service:

**molecule-discord**

It must NOT be required for Minecraft functionality.

The bot should run independently, preferably in Docker.

It has its own database.

Example:

```text
Minecraft server
    ↓
Molecule Core
    ↕ HTTPS/WebSocket
Molecule Discord Bot
    ↓
Discord
```

The Discord bot may run:

- on the same machine
- on another machine
- on another WAN
- on another network

No central Molecule cloud server is required.

---

# 50. DISCORD API COMMUNICATION

Use secure authenticated communication.

Preferred architecture:

- HTTPS REST API
- secure WebSocket connection

The Discord bot should establish the connection outward where possible.

Molecule Core exposes:

```text
REST API
WebSocket
```

on its normal Molecule HTTP server.

Use API tokens/credentials.

Do not send secrets through normal player-facing URLs.

---

# 51. DISCORD INDEPENDENCE

If Minecraft is offline:

Discord continues to work.

The bot continues:

- tracking Discord activity
- tracking XP
- tracking levels
- tracking Discord events
- maintaining its own database
- processing commands

If Discord is offline:

Minecraft continues to work.

When connectivity is restored, synchronize missing events.

Do not overwrite current XP blindly.

Use timestamped/event-based synchronization.

Conceptually:

```text
Minecraft events
+
Discord events
↓
Unified event stream
↓
Unified XP
↓
Unified level
↓
Rank/role mapping
```

---

# 52. DISCORD XP

Track configurable XP for:

- messages
- message content/length where appropriate
- attachments
- reactions
- voice time
- threads
- forum posts
- boosts
- other reasonable Discord activities

All XP values are configurable.

Provide anti-spam protections:

- cooldowns
- minimum message lengths
- repeated-message detection
- bot exclusion
- daily caps
- ignored channels
- ignored roles
- diminishing returns

All configurable.

---

# 53. DISCORD HISTORICAL IMPORT

When first installed/configured, the bot can scan accessible Discord history.

Support configurable:

- all history
- last X days
- date ranges
- selected channels
- selected categories
- threads
- forum posts
- archived content where Discord permits access

Store historical events and calculate XP.

Do not double-count messages if the bot restarts or rescans.

---

# 54. MINECRAFT ↔ DISCORD LINKING

Player accounts are 1:1.

One Minecraft account ↔ one Discord account.

Use temporary linking codes.

Example:

```text
Minecraft:
/linkdiscord

→ temporary code

Discord:
/link <code>
```

Allow:

```text
/unlink
```

Store the relationship as:

```text
minecraft_uuid ↔ discord_user_id
```

Discord-specific data remains in the Discord database.

Minecraft-specific data remains in the Minecraft database.

Core provides the conceptual unified identity.

---

# 55. UNIFIED XP

Maintain:

```text
Minecraft XP
Discord XP
Unified XP
Unified Level
```

Use configurable multipliers.

Example:

```text
Minecraft multiplier: 1.0x
Discord multiplier: 0.5x
```

Unified XP can be calculated from both sources.

Changing multipliers affects future XP only.

Existing XP remains unchanged.

---

# 56. LEVEL/RANK SYNCHRONIZATION

Support configurable mappings between:

- Minecraft levels
- Discord levels
- Molecule ranks
- Discord roles

Mappings are NOT limited to simple ranges.

Support arbitrary many-to-many mappings.

Examples:

```text
Minecraft Level 1
→ Discord Levels 1–10
```

or:

```text
Minecraft Levels 1–10
→ Discord Level 1
```

or:

```text
Minecraft Level 1
→ Discord 1, 2, 5
```

Mappings must be configurable through the web panel.

Ranks and levels are the things that sync.

Do not automatically sync arbitrary staff/title roles such as:

- Owner
- Staff
- Moderator

unless explicitly represented as a Molecule rank/level mapping.

---

# 57. RANK SYNC

Support configurable:

```text
Molecule Rank → Discord Role
Discord Role → Molecule Rank
```

Mappings can be:

- one-way
- two-way

according to configuration.

Rank changes should automatically synchronize.

Molecule rank progression remains XP-derived.

Administrators may modify XP.

Administrators cannot simply override the resulting rank.

---

# 58. DISCORD LEVEL ROLES

Support configurable mappings such as:

```text
Minecraft Level 1 → Discord @Level 1
```

or:

```text
Minecraft Levels 1–10 → Discord @Level 1
Minecraft Levels 11–20 → Discord @Level 2
```

or arbitrary mappings.

Keep actual level progression independent from role presentation.

---

# 59. WEB CONFIGURATION

Every configurable plugin feature should be represented in the web UI.

Changes must be live.

Do not require server restarts.

Use WebSockets/live events where appropriate.

Configuration changes should flow:

```text
Web UI
 ↓
MariaDB
 ↓
Config change event
 ↓
Plugin
 ↓
Live update
```

---

# 60. NO-RESTART REQUIREMENT

This is a major requirement.

Configuration changes should take effect without restarting the Minecraft server.

Examples:

- RTP radius
- RTP biome weights
- rank settings
- XP multipliers
- skill XP
- UI appearance
- UI actions
- shop settings
- world settings where Minecraft permits live changes
- particle settings
- scoreboard
- tab
- cosmetics
- resource-pack assets
- permissions

If a particular Minecraft API cannot safely modify something live, document the limitation and implement the safest possible behavior.

Never fake live support.

---

# 61. RESOURCE-PACK LOGIN/UPDATE

Molecule Core should manage player resource-pack state.

Support:

- force toggle
- default ON
- SHA-1
- versioning
- automatic updates
- asset rebuilds
- player notifications
- pack failure handling

---

# 62. PAPI COMPATIBILITY

Do NOT require PlaceholderAPI.

Molecule should provide its own native Molecule Tag system.

If PlaceholderAPI is installed, provide an optional compatibility bridge.

Molecule should be self-sufficient.

---

# 63. DEVELOPMENT PHASES

Do NOT attempt to build the entire ecosystem in one pass.

Build in phases.

## Phase 0 — Architecture

Create:

- repository structure
- Gradle/Maven structure
- module structure
- API module
- Core module
- coding standards
- documentation
- test infrastructure
- CI

Before implementing gameplay features, establish the architecture.

**Phase 0 must also deliver the design of the Folia execution bridge (§1A.2)**, including
the four thread classes, the retry policy per outcome, and the position-snapshot service.
Nothing in later phases is buildable without it, and every plugin will improvise its own if
it is not settled here.

---

## Phase 1 — Core

Implement:

- Folia bootstrap (via `RegionizedServerInitEvent`, not a delayed task — §1A.3)
- **Folia execution bridge (§1A.2)** — required before anything that follows
- **position snapshot service (§1A.3)**
- service registry
- database provider
- migrations
- configuration system
- audit system
- event infrastructure
- action registry
- variable registry
- player identity/profile
- HTTP server
- REST API
- WebSocket
- authentication
- basic web panel shell
- logging
- resource-pack architecture

Write extensive tests.

---

## Phase 2 — Ranks / Permissions

Implement:

- users
- ranks
- inheritance
- weights
- permissions
- command hiding
- autocomplete
- XP-derived rank progression
- profile integration
- web UI

---

## Phase 3 — UI

Implement:

- chest UI engine
- UI serialization
- UI editor
- universal text editor
- actions
- conditions
- animations
- resource-pack integration

---

## Phase 4 — Worlds

**Gated on an R&D spike. Do not begin Phase 4 until the spike has reported.**

Spike deliverables (§30):

- a working NMS world create + unload prototype on the target Folia version
- a decision on live gamerule editing: working path, or the documented fallback in §30.4
- the versioned-handler isolation boundary for all NMS code

If the spike shows runtime world creation is not viable on the target version, reduce
Phase 4 scope to import + settings of pre-existing worlds and reschedule creation. Do not
ship a create command that silently fails.

Implement:

- world management
- creation
- import
- deletion/trash
- world groups
- settings
- web management

---

## Phase 5 — TP

Implement:

- teleport contract (§25A) — build this first, everything else in Phase 5 uses it
- homes
- TPA
- spawn
- waterfall hubs (with reserved-slot capacity, §29)
- on-demand RTP — the correct-by-construction baseline, built before the index
- RTP scanning (incremental, throttled, resumable — §26)
- biome indexing
- RTP categories
- weights
- safety system

---

## Phase 6 — Eco

Implement:

- virtual currency
- item currency
- transactions
- bank
- currency configuration

---

## Phase 7 — Shop

Implement:

- chest shops
- signs
- inventory validation
- custom items
- out-of-stock
- notifications
- auction house
- Molecule UI

---

## Phase 8 — Regions

Implement:

- region engine
- admin regions
- claims
- subdivisions
- permissions
- claim blocks
- XP/playtime integration

---

## Phase 9 — Stats

Implement:

- event tracking
- player activity
- aggregation
- configurable tracking
- profile statistics
- plugin-registered statistics

---

## Phase 10 — Skills

Implement:

- skill framework
- built-in skills
- XP rules
- natural-generation validation
- perks
- level rewards
- configurable progression

---

## Phase 11 — NPC / Interactions

Implement:

- NPC engine — packet entities only, per the §37.2 threading contract
- skins
- models
- conditions
- actions
- block interactions
- entity interactions

The hologram engine (§46) is the same engine. Build it once here rather than again in
Phase 12.

---

## Phase 12 — Scoreboard / Tab / Cosmetics / Particles / Holograms / MOTD

Implement the remaining presentation systems using the shared Core infrastructure.

---

## Phase 13 — Discord

Build the self-hosted Python Discord bot separately.

Implement:

- Discord DB
- bot
- REST/WebSocket integration
- historical scanning
- XP
- levels
- linking
- unified identity
- rank syncing
- level syncing
- offline event synchronization

---

# 64. TESTING

Testing is mandatory.

Do not merely claim something is tested.

Use actual automated tests.

Include:

## Unit tests

Test:

- XP calculations
- rank inheritance
- rank thresholds
- permission inheritance
- RTP selection
- biome weights
- location reuse prevention
- configuration serialization
- UI serialization
- MiniMessage/text processing
- gradients
- action parameters
- conditions
- economy calculations
- shop item matching
- claim calculations
- skill progression

## Database tests

Use a temporary/test MariaDB instance.

Test:

- schema creation
- migrations
- rollback
- concurrent writes
- transactions
- audit logging
- configuration updates

## Integration tests

Use a real Folia test environment where necessary.

Test:

```text
player joins
→ event recorded

player mines natural stone
→ stat recorded
→ mining XP awarded

player mines placed stone
→ no mining XP

player reaches XP threshold
→ rank changes

rank changes
→ Discord synchronization event

shop purchase
→ inventory changes
→ economy transaction
→ stats event
→ shop event
```

## Folia tests

Specifically test:

- region-thread correctness
- entity scheduler correctness
- world operations
- player teleportation
- async DB operations
- cross-region operations
- concurrent player activity

Do not hide Folia thread violations with sleeps.

Additionally, test the specific failure modes identified in §1A and §73 — these are known
to ship undetected in real plugins because they degrade rather than crash:

- **Ownership assertions.** Debug builds assert `isOnOwnerThread` (§1A.2) at every entry
  point that touches an entity, player, or block. A violation must fail the test, not log.
- **Region-boundary crossing.** A player crossing a region boundary between the scheduling
  and execution of a task must still have the task run on their owner thread. This is the
  test that catches the sampled-location bug in §1A.3.
- **Retired-entity handling.** Disconnect a player mid-teleport, mid-warmup, and between
  scheduling and execution; assert the declared retry policy ran and side effects
  (cooldowns, currency, reservations) resolved correctly.
- **Backlog behaviour.** Drive a per-tick task onto a deliberately slow target thread and
  assert the coalescing guard holds the queue bounded.
- **Concurrent structure access.** Exercise NPC/hologram viewer sets and registries from
  many threads at once, under a stress harness, with assertions enabled.
- **Scanner throttling.** Assert the RTP scanner respects its in-flight chunk cap and
  yields under simulated tick pressure.

---

# 65. GITHUB ACTIONS

Use GitHub Actions.

Do not require paid services.

CI should run:

```text
Compile
 ↓
Static checks
 ↓
Unit tests
 ↓
Database tests
 ↓
Integration tests
 ↓
Package JARs
```

Where practical, run a real Folia integration test server.

Build artifacts should be generated automatically.

---

# 66. CODE QUALITY

Use:

- Java modern enough for the selected Folia version
- strong typing
- clean architecture
- dependency injection where useful
- interfaces around services
- immutable data where appropriate
- transactions for multi-step DB operations
- async database operations
- proper logging
- structured errors
- migration versioning
- validation

Do not use giant manager classes containing every feature.

Do not use static global state everywhere.

Do not expose implementation classes as public API unnecessarily.

Do not duplicate shared logic between plugins.

---

# 67. SECURITY

Treat the web panel and remote API as security-sensitive.

Implement:

- authentication
- API tokens
- token rotation
- authorization
- request validation
- rate limiting where appropriate
- secure WebSockets
- CSRF protection where applicable
- safe file uploads
- resource-pack asset validation
- path traversal protection
- SQL injection protection
- permission checks
- audit logging

Never log passwords or API secrets.

Do not store plaintext credentials unless absolutely required by the underlying connection configuration.

---

# 68. THIRD-PARTY DEVELOPMENT

Molecule should eventually be a platform third-party developers can build on.

Document how to:

```text
Register a plugin
Register a service
Register an action
Register an event
Register a stat
Register a skill
Register a variable
Register a UI component
Register a player profile section
Register commands
Register permissions
Register web-panel configuration
```

A third-party plugin should be able to integrate with Molecule without modifying Molecule source code.

---

# 69. DESIGN PRINCIPLES

These rules should guide every implementation decision.

### Principle 1

**UI is an interface, not a dependency.**

If UI disappears, commands/API functionality remains.

### Principle 2

**MariaDB is the live source of truth.**

### Principle 3

**Plugins are modular.**

Do not unnecessarily couple plugins.

### Principle 4

**Core owns shared infrastructure.**

### Principle 5

**Actions are reusable.**

A button, NPC, block, command, or third-party plugin should be able to invoke the same logical action.

### Principle 6

**Events are first-class.**

Every meaningful plugin should expose events.

### Principle 7

**Folia safety comes first.**

Never assume a global main thread.

### Principle 8

**Everything important is auditable.**

### Principle 9

**Configuration is live.**

### Principle 10

**Third-party developers are first-class citizens.**

### Principle 11

**Do not reinvent the same system twice.**

Use Core's shared:

- text
- variables
- actions
- conditions
- resource packs
- profiles
- events
- permissions
- database
- configuration
- web UI infrastructure

---

# 70. DO NOT OVERENGINEER PREMATURELY

The ecosystem is large, but implementation must remain incremental.

Do not build speculative systems that aren't needed yet.

Create clean extension points, but implement actual functionality when its phase arrives.

If there are two reasonable architectural options, explain the tradeoff and select the one that best supports:

1. Folia
2. modularity
3. reliability
4. performance
5. maintainability
6. third-party integrations

---

# 71. IMPORTANT: CLAUDE CODE WORKFLOW

You are working on an actual software project, not writing a fictional architecture document.

Before writing large amounts of code:

1. Inspect the repository.
2. Establish the build system.
3. Establish module boundaries.
4. Establish API boundaries.
5. Establish testing.
6. Establish CI.
7. Establish Core.
8. Implement one phase at a time.

At the end of every phase:

- compile everything
- run tests
- fix failures
- document what was implemented
- document architectural decisions
- update API documentation
- verify no Folia thread violations
- only then proceed

Do not skip tests because the project is large.

Do not generate fake implementations merely to satisfy an interface.

Do not mark unfinished functionality as complete.

If a feature requires a design decision that is not specified here, make the smallest sensible decision consistent with this architecture and document it.

If a decision has major long-term consequences, stop and explain the options before implementing it.

---

# 72. INITIAL TASK

Do NOT immediately implement every Molecule plugin.

Your first task is to:

1. Analyze this specification.
2. Identify architectural conflicts or missing infrastructure.
3. Propose the final repository/module structure.
4. Propose the Core API boundaries.
5. Propose the MariaDB schema strategy.
6. Propose the configuration model.
7. Propose the event model.
8. Propose the Action Registry.
9. Propose the Variable/Tag system.
10. Propose the HTTP/REST/WebSocket architecture.
11. Propose the web-panel architecture.
12. Propose the test architecture.
13. Propose the GitHub Actions CI architecture.
14. Identify anything that would violate Folia's architecture.
15. Identify anything in this specification that needs to be resolved before implementation.

Then create a phased implementation plan.

Do not start by generating thousands of lines of plugin code.

Once the architecture is approved, begin Phase 0 and Phase 1.

The goal is a production-grade Molecule ecosystem, not a demo.

Build it like the foundation of a platform that third-party developers could eventually depend on.

Items 14 and 15 are partially answered already — see §1A and §73. Do not re-derive them from
first principles; extend them.

---

# 73. REFERENCE IMPLEMENTATIONS

Constraints marked **[VERIFIED]** in this document come from reading the source of the
projects below, not from documentation or inference. Treat this as the prior-art map: when
a section says something is proven, this is where it was proven, and when it says something
is unsolved, this is the set of codebases that failed to solve it.

Read the source before reimplementing. Be skeptical of `folia-supported: true` — several of
these declare support while disabling subsystems or shipping thread violations.

## 73.1 World management

| Project | Verdict |
|---|---|
| `TheNextLvl-net/worlds` | Production-grade. Full NMS boot-sequence reimplementation; dedicated Folia support class for staged unload. **No gamerule support.** |
| `Folia-Inquisitors/MoreFoWorld` | Explicitly a reference implementation, not production. More elaborate 3-stage unload pipeline; `VarHandle` reflection for world removal. **No gamerule support.** README documents which features are "hacky patches." |
| `Multiverse/Multiverse-Core` | Upstream issue: Folia support "not possible for now." Third-party forks exist but were not verified. |

Source of §30.1–30.5. The two working implementations independently converged on the same
technique, which is the strongest available evidence it is the right one.

## 73.2 Scheduler bridges

| Project | Verdict |
|---|---|
| `TechnicallyCoded/FoliaLib` | Generic wrapper. Useful prior art for the retired/fallback result contract (§1A.3). No retry policy. No NPC semantics. |
| LuckPerms `FoliaSchedulerAdapter` | Hand-rolled; dispatches by runtime type of the target. Demonstrates the never-cache-a-region rule. |
| DecentHolograms `PlatformScheduler` | The best-designed *interface* surveyed — describes tasks by what they touch, not sync/async. §1A.1's model follows it. |
| Citizens `SchedulerAdapter` | Source of the `isOnOwnerThread` predicates and the coalescing guard. |

[VERIFIED] No serious project adopts a generic wrapper wholesale — see §1A.4.

## 73.3 RTP and teleport

| Project | Verdict |
|---|---|
| `okocraft/RandomTP` | Small and clean. The canonical on-demand pattern: async scheduler → `getChunkAtAsync` → snapshot safety check → `teleportAsync` → re-dispatch via entity scheduler. Source of §25A and §26's execution model. |
| `ez-plugins/EzRTP` | Larger, has pregeneration. Fully reflective platform abstraction so one jar runs everywhere. |
| EssentialsX PR #6431 | One-line proof that even an already-loaded chunk is unsafe to sync-teleport into. |

[VERIFIED] None of these pre-scan a world into a database. See the risk flag on §26.

## 73.4 NPCs, holograms, packet entities

| Project | Verdict |
|---|---|
| `retrooper/packetevents` | The reference for Netty-thread discipline. Always hops to the player's entity scheduler before touching the server. Correct startup via `RegionizedServerInitEvent`. |
| `FancyInnovations/fancyplugins` (FancyNpcs v3, FancyHolograms v3) | Real Folia support via plugin-owned driver threads. FancyHolograms hops to per-viewer entity schedulers — correct reads, but its viewer set is a plain `HashSet`, so it fans writes across N threads. Both ship the 100 ms blind-respawn visibility workaround. |
| `Pyrbu/ZNPCsPlus` | Best visibility structure surveyed (concurrent set for reads + single executor serializing mutations). Also ships genuine violations: off-thread `setVelocity`/`playSound`/`updateInventory`, non-concurrent registry, linear scan per interact packet. |
| `DecentSoftware-eu/DecentHolograms` | Best scheduler API, weakest adherence — the entity-scheduler hop is used in actions and commands but never in the render or click path. |
| `CitizensDev/Citizens2` | The real-entity counter-example. Declares Folia support while disabling packet rewriting entirely on Folia. Read its comments for why. |

Source of §37 in full. The convergent finding across all of them: **a plugin-owned thread
drives NPC state, packets go straight to the connection from that thread, and only real
game-state mutation is marshalled onto the viewer's entity scheduler.** They differ mainly
in how disciplined they are about that last step — and every place they are undisciplined
is a bug listed in §37.

## 73.5 The recurring bugs

These appear in multiple independent, competent codebases. They are the default failure
modes of this platform, and Molecule should assume it will produce them unless the
architecture prevents them:

```text
1. Region scheduler keyed on a sampled player location      → §1A.3
2. Per-viewer scheduler hops over non-concurrent state       → §37.2
3. Reading player.getLocation() off-thread                   → §1A.3
4. Mutating players off-thread (velocity, sound, inventory)  → §1A.1
5. Same-entity-ID despawn/respawn races                      → §37.4
6. Assuming a post-startup main-thread tick exists           → §1A.3
7. Entity#teleport instead of teleportAsync                  → §25A
8. A scheduler abstraction with no entity-scheduler method   → §1A.2
```

Item 8 is causal, not incidental: the omission is what produces item 1 downstream.