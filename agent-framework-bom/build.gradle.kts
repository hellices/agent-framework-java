plugins {
    id("agentframework.platform-conventions")
}

description = "Dependency constraints for every published Agent Framework for Java artifact."

// Entries must be constraints, not dependencies. A `java-platform` with plain `api(project(...))`
// publishes a POM whose <dependencies> block forces every module onto any consumer that imports the
// BOM, which is the opposite of what a BOM is for. Constraints publish under
// <dependencyManagement> and only align versions for modules the consumer actually declares.
dependencies {
    constraints {
        api(project(":agent-framework-api"))
        api(project(":agent-framework-engine"))
        api(project(":agent-framework-testkit"))
        api(project(":integrations:agent-framework-mcp"))
        api(project(":integrations:agent-framework-otel"))
        api(project(":providers:agent-framework-openai"))
    }
}
