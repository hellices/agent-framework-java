package com.microsoft.agentframework.build.logic

import java.io.File
import java.util.ArrayDeque
import org.gradle.api.Project
import org.gradle.api.file.FileTree

/**
 * Declares the repository files the policy tasks read.
 *
 * The policy tasks read repository files that Gradle cannot infer from a compile classpath. Without
 * declaring them, a workflow, instruction, contract, or documentation edit leaves every policy task
 * UP-TO-DATE and `check` reports success without re-running a single policy.
 *
 * Build output is removed by location, never by name and never by depth. A rule matching a `build`
 * segment anywhere also removes
 * `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness`, where the
 * policies themselves live. A depth-limited rule still removes `docs/build/`, so a canonical
 * document under a `build` path segment would stop invalidating the policies. Only a directory that
 * is a Gradle project root - it holds a `build.gradle.kts` or a `settings.gradle.kts` - contributes
 * an exclusion, and only for its own `build` directory.
 */
object RepositoryPolicyInputs {

    /** Files that mark a directory as the root of a Gradle project or of an included build. */
    private val BUILD_SCRIPT_NAMES = setOf("build.gradle.kts", "settings.gradle.kts")

    /**
     * How deep a project root is searched for. `build-tools/harness-policy` is the deepest project
     * this repository declares, and `.gitignore` pins project output to the same three levels.
     */
    private const val MAXIMUM_PROJECT_DEPTH = 3

    /** Directories never descended into while looking for project roots. */
    private val UNSEARCHED_DIRECTORIES =
        setOf(
            "build",
            "node_modules",
            ".git",
            ".gradle",
            ".kotlin",
            ".gradle-bootstrap",
            ".superpowers",
            ".worktrees"
        )

    /**
     * Generated, vendored, or agent-owned locations the repository does not own as source. They are
     * matched at any depth because a policy never reads from them.
     */
    private val NON_SOURCE_EXCLUSIONS =
        listOf(
            "**/.git/**",
            "**/.gradle/**",
            "**/.kotlin/**",
            "**/.gradle-bootstrap/**",
            ".superpowers/**",
            ".worktrees/**",
            ".harness/runs/**"
        )

    /**
     * Returns the Ant patterns the policy input tree excludes, relative to the repository root.
     *
     * @param repositoryRoot the root of the repository the policies read
     * @return the build output of every Gradle project root, plus the non-source locations
     */
    fun excludePatterns(repositoryRoot: File): List<String> =
        projectOutputExclusions(repositoryRoot) + NON_SOURCE_EXCLUSIONS

    /**
     * Returns the repository files the policy tasks read.
     *
     * @param project the project that owns the policy tasks
     * @return the repository tree without project output and without non-source locations
     */
    fun repositoryPolicySources(project: Project): FileTree {
        val repositoryRoot = project.rootProject.layout.projectDirectory
        return repositoryRoot.asFileTree.matching { exclude(excludePatterns(repositoryRoot.asFile)) }
    }

    private fun projectOutputExclusions(repositoryRoot: File): List<String> {
        val exclusions = mutableListOf<String>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.addLast(repositoryRoot to 0)
        while (queue.isNotEmpty()) {
            val (directory, depth) = queue.removeFirst()
            if (isProjectRoot(directory)) {
                exclusions.add(buildOutputPattern(repositoryRoot, directory))
            }
            if (depth == MAXIMUM_PROJECT_DEPTH) {
                continue
            }
            directory.listFiles()
                ?.filter { it.isDirectory && it.name !in UNSEARCHED_DIRECTORIES }
                ?.forEach { queue.addLast(it to depth + 1) }
        }
        return exclusions.sorted()
    }

    private fun isProjectRoot(directory: File): Boolean =
        BUILD_SCRIPT_NAMES.any { directory.resolve(it).isFile }

    private fun buildOutputPattern(repositoryRoot: File, projectRoot: File): String {
        val relative =
            repositoryRoot
                .toPath()
                .relativize(projectRoot.toPath())
                .joinToString("/") { segment -> segment.toString() }
        return if (relative.isEmpty()) "build/**" else "$relative/build/**"
    }
}
