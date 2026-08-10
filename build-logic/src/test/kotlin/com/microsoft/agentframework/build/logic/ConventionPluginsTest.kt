package com.microsoft.agentframework.build.logic

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConventionPluginsTest {

    @Test
    fun javaLibraryConventionsPinToolchainAndRelease(@TempDir projectDir: File) {
        writeFixture(
            projectDir,
            listOf("agentframework.java-library-conventions"),
            """
            tasks.register("printJavaContract") {
                val toolchain = java.toolchain.languageVersion.get().asInt()
                val release = tasks.named<JavaCompile>("compileJava").get().options.release.get()
                doLast {
                    println("toolchain=" + toolchain)
                    println("release=" + release)
                }
            }
            """.trimIndent()
        )

        val result = runner(projectDir, "printJavaContract").build()

        assertThat(result.output).contains("toolchain=17")
        assertThat(result.output).contains("release=17")
    }

    @Test
    fun qualityConventionsAggregateFormattingAndStaticAnalysis(@TempDir projectDir: File) {
        writeFixture(
            projectDir,
            listOf(
                "agentframework.java-library-conventions",
                "agentframework.test-conventions",
                "agentframework.quality-conventions"
            ),
            ""
        )

        val result = runner(projectDir, "quality", "--dry-run").build()

        assertThat(result.output).contains(":spotlessCheck SKIPPED")
        assertThat(result.output).contains(":checkstyleMain SKIPPED")
        assertThat(result.output).contains(":checkstyleTest SKIPPED")
        assertThat(result.output).contains(":pmdMain SKIPPED")
        assertThat(result.output).contains(":pmdTest SKIPPED")
        assertThat(result.output).contains(":spotbugsMain SKIPPED")
        assertThat(result.output).contains(":spotbugsTest SKIPPED")
        assertThat(result.output).contains(":jacocoTestReport SKIPPED")
        assertThat(result.output).contains(":quality SKIPPED")
    }

    @Test
    fun testConventionsRegisterOneTaskPerSupportedJdk(@TempDir projectDir: File) {
        writeFixture(
            projectDir,
            listOf("agentframework.java-library-conventions", "agentframework.test-conventions"),
            ""
        )

        val result = runner(projectDir, "tasks", "--all").build()

        assertThat(result.output).contains("testJava17")
        assertThat(result.output).contains("testJava21")
        assertThat(result.output).contains("testJava25")
    }

    @Test
    fun javaLibraryConventionsDoNotApplyFormatting(@TempDir projectDir: File) {
        writeFixture(projectDir, listOf("agentframework.java-library-conventions"), "")

        val result = runner(projectDir, "tasks", "--all").build()

        assertThat(result.output).doesNotContain("spotlessCheck")
        assertThat(result.output).doesNotContain("checkstyleMain")
    }

    @Test
    fun checkRunsCompatibilityTestsAndQuality(@TempDir projectDir: File) {
        writeFixture(
            projectDir,
            listOf(
                "agentframework.java-library-conventions",
                "agentframework.test-conventions",
                "agentframework.quality-conventions"
            ),
            ""
        )

        val result = runner(projectDir, "check", "--dry-run").build()

        assertThat(result.output).contains(":quality SKIPPED")
        assertThat(result.output).contains(":testJava17 SKIPPED")
        assertThat(result.output).contains(":testJava21 SKIPPED")
        assertThat(result.output).contains(":testJava25 SKIPPED")
    }

    private fun runner(projectDir: File, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    private fun writeFixture(projectDir: File, pluginIds: List<String>, extraBuildScript: String) {
        val catalog = File(System.getProperty("agentframework.versionCatalog"))
        projectDir.resolve("gradle").mkdirs()
        catalog.copyTo(projectDir.resolve("gradle/libs.versions.toml"), overwrite = true)
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        val applied = pluginIds.joinToString("\n") { "    id(\"$it\")" }
        projectDir.resolve("build.gradle.kts")
            .writeText("plugins {\n$applied\n}\n\n$extraBuildScript\n")
    }
}
