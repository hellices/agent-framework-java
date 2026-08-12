pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "agent-framework-java"

// Core modules stay at the root. Extension families that will grow to many modules live under a
// single grouping directory, which is what Spring AI and Spring Boot do at this scale. Deeper
// nesting is deliberately avoided; it pays off only when a module splits across build phases the
// way a Quarkus extension does.
//
// A grouping directory carries no meaning in published coordinates, so a grouped module sets its
// artifact name from the leaf directory. See docs/design/module-composition.md.
include(":agent-framework-api")
include(":agent-framework-bom")
include(":agent-framework-engine")
include(":agent-framework-testkit")

include(":build-tools:harness-policy")
