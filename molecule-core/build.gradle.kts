plugins {
    alias(libs.plugins.shadow)
}

description = "Molecule Core — shared infrastructure for the Molecule ecosystem"

dependencies {
    // Shipped inside Core's jar: the API must be on the server at runtime, and Core
    // is what puts it there. Other plugins depend on it `compileOnly`.
    api(project(":molecule-api"))

    // Core owns the only pool in the ecosystem (SPEC §5), so it is the only module
    // that bundles a driver or a pool.
    implementation(libs.hikari)
    implementation(libs.mariadb)

    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.processResources {
    val pluginVersion = version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Relocated so Molecule cannot collide with another plugin shading its own copy
    // of Hikari or the driver — a classic source of "works alone, breaks together".
    relocate("com.zaxxer.hikari", "dev.molecule.core.libs.hikari")
    relocate("org.mariadb.jdbc", "dev.molecule.core.libs.mariadb")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
