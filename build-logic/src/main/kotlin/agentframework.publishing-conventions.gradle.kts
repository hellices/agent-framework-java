plugins {
    id("maven-publish")
    id("signing")
}

// Publishing is a convention rather than per-module boilerplate so that every artifact carries the
// same coordinates, license, and developer metadata. A consumer resolving one module and a consumer
// resolving the BOM must see identical provenance.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(provider { "${project.group}:${project.name}" })
            description.set(provider { project.description ?: project.name })
            url.set("https://github.com/hellices/agent-framework-java")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://github.com/hellices/agent-framework-java/blob/main/LICENSE")
                    distribution.set("repo")
                }
            }

            developers {
                developer {
                    id.set("agent-framework-java")
                    name.set("Agent Framework for Java contributors")
                    url.set("https://github.com/hellices/agent-framework-java")
                }
            }

            scm {
                url.set("https://github.com/hellices/agent-framework-java")
                connection.set("scm:git:https://github.com/hellices/agent-framework-java.git")
                developerConnection.set("scm:git:ssh://git@github.com/hellices/agent-framework-java.git")
            }
        }
    }

    repositories {
        // A local repository keeps `publish` verifiable in CI and in a fork without credentials.
        // Release repositories are added by the release workflow, not by this convention.
        //
        // The path is derived from the settings-time root directory rather than
        // `rootProject.layout`, because reading another project's model during configuration breaks
        // project isolation and the configuration cache. With twenty or more modules planned, that
        // is a feature this build will need.
        maven {
            name = "buildDirectory"
            url = isolated.rootProject.projectDirectory.dir("build/maven-repository").asFile.toURI()
        }
    }
}

// Maven Central rejects an upload whose artifacts carry no PGP signature, so signing belongs to the
// publishing contract rather than to a release script.
//
// A contributor and a fork must still be able to publish locally, so signing stays optional by
// default. A release build passes `-Pagentframework.release=true`, which makes a missing key fail
// the build immediately instead of at the upload step, where the failure would surface after the
// release is already in motion.
val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
val releaseBuild = providers.gradleProperty("agentframework.release").isPresent

if (signingKey == null && releaseBuild) {
    throw GradleException(
        "A release build must sign its artifacts, but SIGNING_KEY is absent. " +
            "Maven Central rejects unsigned uploads, so this fails now rather than after " +
            "the release has started."
    )
}

if (signingKey != null) {
    signing {
        isRequired = releaseBuild

        // `useInMemoryPgpKeys` builds no signatory when the password is null, which surfaces later
        // as "No configured signatory" on the signing task rather than here. An unprotected key is
        // a legitimate CI setup, so an absent password means an empty one.
        useInMemoryPgpKeys(signingKey, signingPassword ?: "")

        // Each module creates its publication after this convention is applied, so the collection
        // is empty right now. Reacting to publications as they appear keeps the signatory attached
        // to every signing task.
        publishing.publications.configureEach { sign(this) }
    }
}
