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

include(":agent-framework-api")
include(":agent-framework-bom")
include(":agent-framework-engine")
include(":agent-framework-testkit")
include(":build-tools:harness-policy")
