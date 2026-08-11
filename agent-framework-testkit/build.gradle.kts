plugins {
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
}

description = "Deterministic fixtures and contract-test bases for Agent Framework for Java."

dependencies {
    api(project(":agent-framework-api"))
}
