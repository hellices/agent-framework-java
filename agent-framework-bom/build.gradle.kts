plugins {
    id("agentframework.platform-conventions")
}

description = "Dependency constraints for every published Agent Framework for Java artifact."

javaPlatform {
    allowDependencies()
}

dependencies {
    api(project(":agent-framework-api"))
    api(project(":agent-framework-engine"))
    api(project(":agent-framework-testkit"))
}
