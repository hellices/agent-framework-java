package com.microsoft.agentframework.build.logic

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The repository policy tasks read repository files instead of a compile classpath, so the declared
 * input tree decides whether an edit re-runs a policy at all. Excluding build output by name or by
 * depth also drops documentation that legitimately lives under a `build` path segment, which turns
 * a documentation change into a silent UP-TO-DATE.
 */
class RepositoryPolicyInputsTest {

    @Test
    fun onlyGradleProjectRootsContributeABuildOutputExclusion(@TempDir root: File) {
        writeRepository(root)

        val patterns = RepositoryPolicyInputs.excludePatterns(root)

        assertThat(patterns)
            .contains("build/**", "module/build/**", "group/leaf/build/**")
            .doesNotContain(
                "docs/build/**",
                "group/build/**",
                "**/build/**",
                "*/build/**",
                "*/*/build/**"
            )
    }

    @Test
    fun untrackedAndGeneratedNoiseStaysOutsideTheInputTree(@TempDir root: File) {
        writeRepository(root)

        val patterns = RepositoryPolicyInputs.excludePatterns(root)

        assertThat(patterns)
            .contains(
                "**/.git/**",
                "**/.gradle/**",
                "**/.kotlin/**",
                "**/.gradle-bootstrap/**",
                ".superpowers/**",
                ".worktrees/**",
                ".harness/runs/**"
            )
    }

    @Test
    fun documentationUnderABuildPathSegmentInvalidatesThePolicyTask(@TempDir root: File) {
        writeRepository(root)
        val documentation = root.resolve("docs/build/reference.md")
        val projectOutput = root.resolve("module/build/generated.md")

        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

        documentation.writeText("# Reference\n\nA canonical document that changed.\n")

        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.SUCCESS)

        projectOutput.writeText("# Generated\n\nProject output that changed.\n")

        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun policySourcesUnderABuildPackageInvalidateThePolicyTask(@TempDir root: File) {
        writeRepository(root)
        val policySource = root.resolve("module/src/test/java/com/example/build/harness/Policy.java")

        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

        policySource.writeText("class Policy { int rules = 2; }\n")

        assertThat(runProbe(root).outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    private fun runProbe(root: File) =
        GradleRunner.create()
            .withProjectDir(root)
            .withArguments("policyProbe", "--stacktrace")
            .build()
            .task(":policyProbe")!!

    private fun writeRepository(root: File) {
        write(root, "settings.gradle.kts", "rootProject.name = \"fixture\"\ninclude(\":module\")\n")
        write(
            root,
            "build.gradle.kts",
            """
            buildscript {
                dependencies {
                    classpath(files("$BUILD_LOGIC_CLASSES"))
                }
            }

            val repositoryPolicySources =
                com.microsoft.agentframework.build.logic.RepositoryPolicyInputs
                    .repositoryPolicySources(project)
            val marker = layout.buildDirectory.file("policy-probe.txt")

            tasks.register("policyProbe") {
                inputs.files(repositoryPolicySources)
                    .withPropertyName("repositoryPolicySources")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                outputs.file(marker)
                doLast {
                    marker.get().asFile.writeText("ran")
                }
            }
            """.trimIndent() + "\n"
        )
        write(root, "module/build.gradle.kts", "")
        write(root, "group/leaf/build.gradle.kts", "")
        write(root, "docs/build/reference.md", "# Reference\n")
        write(root, "docs/README.md", "# Documentation\n")
        write(root, "module/build/generated.md", "# Generated\n")
        write(
            root,
            "module/src/test/java/com/example/build/harness/Policy.java",
            "class Policy {}\n"
        )
    }

    private fun write(root: File, relativePath: String, content: String) {
        val file = root.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private companion object {

        /**
         * Where this test loaded the helper from. The fixture build script puts exactly that on its
         * buildscript classpath, so the probe exercises the production code rather than a copy of
         * the patterns.
         */
        val BUILD_LOGIC_CLASSES: String =
            File(
                    RepositoryPolicyInputs::class
                        .java
                        .protectionDomain
                        .codeSource
                        .location
                        .toURI()
                )
                .invariantSeparatorsPath
    }
}
