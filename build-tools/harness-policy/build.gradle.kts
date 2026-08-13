import io.github.hellices.agentframework.build.logic.RepositoryPolicyInputs

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
//
// `RepositoryPolicyInputs` removes build output by location: only a directory that actually is a
// Gradle project root loses its own `build` directory. Removing it by name would also remove
// `build-tools/harness-policy/src/test/java/io/github/hellices/agentframework/build/harness`, where
// these policies live; removing it by depth would also remove `docs/build/` and `docs/*/build/`,
// so a canonical document under a `build` path segment would stop invalidating the policies.
val repositoryPolicySources = RepositoryPolicyInputs.repositoryPolicySources(project)

tasks.withType<Test>().configureEach {
    inputs.files(repositoryPolicySources)
        .withPropertyName("repositoryPolicySources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The published-BOM and signing policies read artifacts under `build/maven-repository`, which
    // the tree above excludes along with every other build directory. Without declaring it,
    // republishing changed artifacts leaves this task UP-TO-DATE and the checks silently do not
    // run. CI passes `--rerun-tasks` and would not notice; a local run or another workflow would.
    //
    // The directory is absent until something publishes, so it is declared through a provider that
    // yields nothing in that case rather than a path Gradle would demand exist.
    val publishedArtifacts =
        rootProject.layout.buildDirectory.dir("maven-repository").map { directory ->
            if (directory.asFile.isDirectory) files(directory) else files()
        }
    inputs.files(publishedArtifacts)
        .withPropertyName("publishedArtifacts")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The published-BOM policy must read the pom this build produced, not whichever filename sorts
    // highest in a directory that accumulates across versions. Passing the version removes the
    // guesswork.
    systemProperty("agentframework.version", project.version.toString())

    // Locally the published output may legitimately be absent, and the policy skips. CI publishes
    // first and then sets these, turning absence into a failure so the contracts are unconditional.
    //
    // Both flags are normalised here so a bare `-Pagentframework.requireSignatures` means "yes",
    // matching `agentframework.release`. Left to `Boolean.parseBoolean` they would mean "no": asking
    // for enforcement would silently switch the suite to skipping, and the build would still pass.
    listOf("agentframework.requirePublishedBom", "agentframework.requireSignatures").forEach { flag ->
        providers.gradleProperty(flag).orNull?.let { raw ->
            val enabled =
                when (raw.trim().lowercase()) {
                    "", "true" -> true
                    "false" -> false
                    else ->
                        throw GradleException(
                            "$flag must be true or false, but was '$raw'. An unrecognised value " +
                                "would otherwise turn the check off without any signal."
                        )
                }
            systemProperty(flag, enabled.toString())
        }
    }
}

tasks.register("policyCheck") {
    group = "verification"
    description = "Runs repository, artifact, and workflow policy regression."
    dependsOn(tasks.named("test"))
}
