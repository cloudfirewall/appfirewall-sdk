// Root build file for the AppFirewall Spring Boot SDK monorepo subproject.
// See docs/specs/appfirewall-spring-boot.md (in the repo root) for the design.

plugins {
    java
}

allprojects {
    group = "io.appfirewall"
    // Version is wired from CI on tagged releases; SNAPSHOT for local dev.
    // The Python SDK uses hatch-vcs; for Java we'll wire axion-release or
    // nebula-release in the publishing pass. Until then, snapshot.
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        withSourcesJar()
        withJavadocJar()
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
}
