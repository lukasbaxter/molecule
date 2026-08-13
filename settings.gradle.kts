rootProject.name = "molecule"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

// Phase 0 — foundation
include("molecule-api")

// Phase 1 — core infrastructure
include("molecule-core")

// Phase 2+ — plugins. Registered up front so the build graph, CI matrix and
// release pipeline are complete from day one; each module ships when its phase
// lands. See docs/SPEC.md §63 for the phase plan.
include("molecule-ranks")        // Phase 2
include("molecule-ui")           // Phase 3
include("molecule-worlds")       // Phase 4  — gated on the NMS spike, SPEC §30
include("molecule-tp")           // Phase 5
include("molecule-eco")          // Phase 6
include("molecule-shop")         // Phase 7
include("molecule-regions")      // Phase 8
include("molecule-stats")        // Phase 9
include("molecule-skills")       // Phase 10
include("molecule-npc")          // Phase 11
include("molecule-interactions") // Phase 11
include("molecule-scoreboard")   // Phase 12
include("molecule-cosmetics")    // Phase 12
include("molecule-particles")    // Phase 12
include("molecule-holograms")    // Phase 12 — shares the NPC engine, SPEC §46
include("molecule-motd")         // Phase 12
