plugins {
    alias(libs.plugins.shadow)
}

description = "Molecule Core — shared infrastructure for the Molecule ecosystem"

dependencies {
    // Shipped inside Core's jar: the API must be on the server at runtime, and Core
    // is what puts it there. Other plugins depend on it `compileOnly`.
    api(project(":molecule-api"))
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
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
