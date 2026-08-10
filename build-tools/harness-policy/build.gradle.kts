plugins {
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
}

description = "Executable repository, artifact, and workflow policy for the Agent Framework for Java harness."

dependencies {
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.dataformat.yaml)
    testImplementation(libs.networknt.json.schema.validator)
}

tasks.register("policyCheck") {
    group = "verification"
    description = "Runs repository, artifact, and workflow policy regression."
    dependsOn(tasks.named("test"))
}
