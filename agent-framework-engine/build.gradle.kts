plugins {
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
}

description = "Embedded agent execution engine for Agent Framework for Java."

dependencies {
    api(project(":agent-framework-api"))
}
