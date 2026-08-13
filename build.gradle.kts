import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "dev.molecule"
    version = providers.gradleProperty("moleculeVersion").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    apply(plugin = "java-library")

    // Plugins are applied imperatively here, so Kotlin DSL type-safe accessors
    // are not available — configure the extension explicitly.
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(rootProject.libs.versions.java.get()))
        withSourcesJar()
    }

    dependencies {
        "compileOnly"(rootProject.libs.folia.api)
        // Tests exercise the text engine, which needs Adventure/MiniMessage on the test
        // runtime classpath. Both arrive transitively with the Folia API.
        "testImplementation"(rootProject.libs.folia.api)
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.assertj)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(rootProject.libs.versions.java.get().toInt())
        // SPEC §66 — warnings are defects. Folia thread-safety mistakes often
        // surface first as unchecked or deprecation warnings.
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            // "passed" included deliberately: several suites skip themselves when a
            // precondition is missing (Docker, for instance), and a silent skip reads
            // exactly like a pass in CI unless the counts are visible.
            events("passed", "failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    tasks.withType<Jar>().configureEach {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
            )
        }
    }
}
