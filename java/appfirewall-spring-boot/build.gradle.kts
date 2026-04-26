// Root build file for the AppFirewall Spring Boot SDK monorepo subproject.
// See docs/specs/appfirewall-spring-boot.md (in the repo root) for the design.

plugins {
    java
    // Uploads each subproject's signed artifacts as a single bundle to the
    // Sonatype Central Portal (https://central.sonatype.com/). Replaces the
    // legacy OSSRH Nexus 2 file-by-file deploy, which now returns 402 for
    // accounts provisioned on the Portal.
    id("com.gradleup.nmcp.aggregation") version "1.4.4"
}

// nmcp-aggregation resolves its helper artifacts at the root project level.
repositories {
    mavenCentral()
}

nmcpAggregation {
    centralPortal {
        username.set(providers.environmentVariable("OSSRH_USERNAME"))
        password.set(providers.environmentVariable("OSSRH_PASSWORD"))
        // USER_MANAGED → upload, then a human "Publishes" via the Portal UI.
        // Switch to AUTOMATIC once we trust the pipeline.
        publishingType.set("USER_MANAGED")
    }
    publishAllProjectsProbablyBreakingProjectIsolation()
}

allprojects {
    group = "io.appfirewall"
    // Resolved at publish time from the `appfirewallVersion` Gradle property
    // (set by CI from the git tag), defaulting to a SNAPSHOT for local builds.
    version = (findProperty("appfirewallVersion") as String?) ?: "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

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

    // Javadoc on a multi-module Spring project surfaces a lot of unresolved
    // references when Spring is compileOnly. Don't fail the build on those.
    tasks.withType<Javadoc>().configureEach {
        (options as? StandardJavadocDocletOptions)?.addStringOption("Xdoclint:none", "-quiet")
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("Origin-side abuse signal middleware for Spring Boot. "
                            + "Part of the AppFirewall platform by Sireto.")
                    url.set("https://github.com/cloudfirewall/appfirewall-sdk")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    developers {
                        developer {
                            id.set("sireto")
                            name.set("Sireto")
                            url.set("https://appfirewall.io")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/cloudfirewall/appfirewall-sdk.git")
                        developerConnection.set("scm:git:ssh://github.com/cloudfirewall/appfirewall-sdk.git")
                        url.set("https://github.com/cloudfirewall/appfirewall-sdk")
                    }
                }
            }
        }

        // No `repositories {}` block: uploads go through the Central Portal
        // via the nmcp-aggregation plugin at the root project, which collects
        // each subproject's signed artifacts and ships them as a single bundle.
    }

    extensions.configure<SigningExtension> {
        val signingKey = System.getenv("SIGNING_KEY")
        val signingPassword = System.getenv("SIGNING_PASSWORD")
        if (!signingKey.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPassword ?: "")
            sign(extensions.getByType<PublishingExtension>().publications["maven"])
        } else {
            // No key configured (local dev). Skip signing; CI will sign.
            isRequired = false
        }
    }
}
