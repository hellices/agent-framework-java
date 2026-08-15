plugins {
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
    id("agentframework.library-publishing-conventions")
}

description = "Embedded agent execution engine for Agent Framework for Java."

dependencies {
    api(project(":agent-framework-api"))
    implementation(libs.jackson.databind)
}
