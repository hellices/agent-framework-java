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

// The policy tests read repository files that Gradle cannot infer from the compile classpath.
// Without declaring them, a workflow, instruction, contract, or documentation edit leaves every
// policy task UP-TO-DATE and `check` reports success without re-running a single policy.
val repositoryPolicySources =
    rootProject.layout.projectDirectory.asFileTree.matching {
        exclude(
            "**/build/**",
            "**/.git/**",
            "**/.gradle/**",
            "**/.kotlin/**",
            "**/.gradle-bootstrap/**",
            ".worktrees/**",
            ".harness/runs/**"
        )
    }

tasks.withType<Test>().configureEach {
    inputs.files(repositoryPolicySources)
        .withPropertyName("repositoryPolicySources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register("policyCheck") {
    group = "verification"
    description = "Runs repository, artifact, and workflow policy regression."
    dependsOn(tasks.named("test"))
}
