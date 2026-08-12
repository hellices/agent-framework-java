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
// default. A release build passes `-Pagentframework.release=true`, and a missing key then fails the
// publish task instead of the upload, where the failure would surface after the release is already
// in motion.
// The key may arrive as a value or as a path to a file holding it. A file keeps the key out of the
// process environment, which GitHub Actions prints when it logs a step's `env` block.
//
// The path is resolved against the settings-time root, not the subproject: this convention runs in
// every module, so a repository-relative path would otherwise resolve differently in each one and
// find the key in none of them.
val signingKeyFile =
    providers.environmentVariable("SIGNING_KEY_FILE").orNull?.takeIf(String::isNotBlank)
val signingKey =
    (signingKeyFile
            ?.let { path ->
                val file = java.io.File(path)
                val resolved =
                    if (file.isAbsolute) file
                    else isolated.rootProject.projectDirectory.file(path).asFile
                if (resolved.isFile) resolved.readText() else null
            }
            ?: providers.environmentVariable("SIGNING_KEY").orNull)
        ?.takeIf(String::isNotBlank)
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull

// Read the value, not merely its presence: `-Pagentframework.release=false` must mean "not a
// release". A bare `-Pagentframework.release` carries the empty string in Gradle and is the usual
// way to write a boolean flag, so it means "release"; anything else is rejected rather than
// silently treated as false, because failing open here publishes unsigned artifacts.
val releaseBuild =
    providers.gradleProperty("agentframework.release").map { raw ->
        when (raw.trim().lowercase()) {
            "", "true" -> true
            "false" -> false
            else ->
                throw GradleException(
                    "agentframework.release must be true or false, but was '$raw'. " +
                        "An unrecognised value would otherwise disable release signing silently."
                )
        }
    }.getOrElse(false)

if (signingKey == null && releaseBuild) {
    // Fail when publishing runs, not while configuring. A release branch must still be able to run
    // `test` or `quality` with this flag set and no key present.
    tasks.withType<PublishToMavenRepository>().configureEach {
        doFirst {
            throw GradleException(
                "A release build must sign its artifacts, but SIGNING_KEY is absent. " +
                    "Maven Central rejects unsigned uploads, so this fails before the upload " +
                    "rather than after the release has started."
            )
        }
    }
}

if (signingKey != null) {
    signing {
        isRequired = releaseBuild

        // `useInMemoryPgpKeys` builds no signatory when the password is null, and the failure
        // surfaces later as "No configured signatory" on the signing task rather than here. An
        // unprotected key is a legitimate CI setup, so an absent password means an empty one.
        useInMemoryPgpKeys(signingKey, signingPassword ?: "")

        // Publications are created by each module after this convention applies. Reacting to them
        // as they appear keeps signing correct regardless of that ordering.
        publishing.publications.configureEach { sign(this) }
    }
}
