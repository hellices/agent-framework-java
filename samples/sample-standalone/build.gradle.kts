plugins {
    application
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
}

description = "Runnable standalone Agent.run sample."

dependencies {
    implementation(project(":agent-framework-api"))
    implementation(project(":agent-framework-engine"))
}

application {
    mainClass = "io.github.hellices.agentframework.samples.standalone.StandaloneAgentApplication"
}
