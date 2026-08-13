plugins {
    alias(libs.plugins.shadow)
}

description = "Molecule Core — shared infrastructure for the Molecule ecosystem"

dependencies {
    api(project(":molecule-api"))
}

tasks.shadowJar {
    archiveClassifier.set("")
    // SPEC §5 — Core owns the only database pool in the ecosystem, so it is the
    // only module that bundles third-party libraries. Relocate to avoid clashing
    // with whatever else the server has loaded.
    relocate("com.zaxxer.hikari", "dev.molecule.core.libs.hikari")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
