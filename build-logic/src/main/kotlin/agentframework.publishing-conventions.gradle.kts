plugins {
    id("maven-publish")
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
        maven {
            name = "buildDirectory"
            url = uri(rootProject.layout.buildDirectory.dir("maven-repository"))
        }
    }
}
