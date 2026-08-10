# Gradle Repository Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the superseded Maven harness with a Gradle Kotlin DSL build that owns the Java 17 baseline, convention plugins, executable repository policy, versioned agent artifact contracts, and trusted/fork CI on the `arc-java-build` ARC scale set.

**Architecture:** The repository root is a Gradle Kotlin DSL build with no product modules. An included build `build-logic` publishes three precompiled convention plugins (`java-library`, `test`, `quality`) that every current and future module applies. A single non-published Gradle project `build-tools/harness-policy` owns executable repository policy: governance files, build contract pins, JSON Schema 2020-12 artifact contracts, and YAML-parsed GitHub workflow policy. Root lifecycle tasks `policyCheck`, `quality`, `testJava17`, `testJava21`, and `testJava25` aggregate the same tasks from every subproject and all hang off `check`, so local and CI verification run identical commands.

**Tech Stack:** Git, Java 17, Gradle 9.7.0 Wrapper (Kotlin DSL), Gradle version catalog, Gradle TestKit, JUnit 5.14.4, AssertJ 3.27.7, Jackson 2.22.1, networknt json-schema-validator 3.0.6, Spotless Gradle 8.9.0 with google-java-format 1.24.0, Checkstyle 12.3.1, PMD 7.26.0, SpotBugs Gradle plugin 6.5.10 with SpotBugs 4.10.3, JaCoCo 0.8.15, GitHub Actions, Actions Runner Controller.

## Global Constraints

- The build is Gradle only. No `pom.xml`, `mvnw`, `mvnw.cmd`, or `.mvn/` directory may exist or be re-created anywhere in the repository.
- All build scripts are Gradle Kotlin DSL (`*.gradle.kts`). Groovy DSL is prohibited.
- Gradle Wrapper version is exactly `9.7.0`.
- Gradle distribution SHA-256 is exactly `84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae`.
- Gradle Wrapper JAR SHA-256 is exactly `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`.
- Gradle runtime JDK is 17. Java `sourceCompatibility` is expressed only through the toolchain (`JavaLanguageVersion.of(17)`) and `options.release = 17`.
- CI compatibility matrix is Eclipse Temurin 17, 21, and 25. Toolchain auto-download is disabled; toolchains come from the runner image tool cache or from local installations.
- Dependency versions live only in `gradle/libs.versions.toml`. No version literal is duplicated in a build script except inside `build-logic/build.gradle.kts`, which reads them from the catalog.
- Pinned versions: JUnit `5.14.4`, AssertJ `3.27.7`, Jackson `2.22.1`, networknt json-schema-validator `3.0.6`, Spotless Gradle plugin `8.9.0`, google-java-format `1.24.0`, Checkstyle `12.3.1`, PMD `7.26.0`, SpotBugs Gradle plugin `6.5.10`, SpotBugs tool `4.10.3`, JaCoCo `0.8.15`.
- google-java-format runs only inside `agentframework.quality-conventions`, only on the Gradle runtime JDK 17, and never inside a JDK 21 or JDK 25 compatibility task. This is a deliberate, reviewed deviation from the design's preference for a formatter that does not touch javac internals: google-java-format 1.24.0 is pinned by decision, so the `--add-exports` flags in `gradle.properties` and the quality/compatibility split in the verification graph are what keep it from breaking the JDK matrix.
- `AGENTS.md` is the canonical vendor-neutral instruction file. `CLAUDE.md`, `GEMINI.md`, and `.github/copilot-instructions.md` are thin adapters of at most 20 lines that point to it and never restate repository rules.
- Every repository policy is a Gradle test wired into `check`. A shell script may exist only as a thin wrapper that a Gradle task invokes; a standalone shell gate that `check` does not run is prohibited.
- Workflow policy is enforced by parsing every `.github/workflows/*.yml` and `*.yaml` file as YAML, not by matching one regular expression against one file.
- External GitHub Actions and reusable workflows are referenced by full 40-character commit SHA. Local composite actions use a `./` path. Pinned action SHAs for this plan:
  - `actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1`
  - `actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961`
  - `gradle/actions/setup-gradle@67621b124fd2e251c5e8a0e6e3b91318f2287669`
  - `actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a`
- `pull_request_target` is prohibited. Allowed runner labels are exactly `arc-java-build` and `ubuntu-latest`. Allowed permission values are exactly `read` and `none`.
- Only pull-request runs may be cancelled by concurrency. `main` pushes must never be cancelled.
- Fork pull requests run the GitHub-hosted minimum verification; trusted jobs run on `arc-java-build`. The two paths are mutually exclusive and fan into one required `verify-result` job so a skipped job can never look green.
- No secret, credential, token, kubeconfig, Helm value, or personal agent setting is committed.
- This plan creates no product modules, no provider adapters, no release automation, and no Azure or Kubernetes resources.
- Every commit created by an agent includes:

```text
Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

---

## File Map

### Root Gradle build

- `settings.gradle.kts`: root project name, plugin repositories, `build-logic` included build, subproject includes, and centralized dependency repositories.
- `build.gradle.kts`: root lifecycle tasks `policyCheck`, `quality`, `testJava17`, `testJava21`, `testJava25`, `buildLogicTest`, and their wiring into `check`.
- `gradle.properties`: Gradle daemon memory, google-java-format JDK exports, toolchain detection policy, and console defaults.
- `gradle/libs.versions.toml`: the single source of truth for every dependency and quality-tool version.
- `gradle/wrapper/gradle-wrapper.properties`: pinned Gradle 9.7.0 distribution URL and SHA-256.
- `gradle/wrapper/gradle-wrapper.jar`: generated wrapper JAR.
- `gradle/wrapper/gradle-wrapper.jar.sha256`: committed checksum of the wrapper JAR.
- `gradlew`, `gradlew.bat`: generated wrapper scripts.

### Convention plugins (included build)

- `build-logic/settings.gradle.kts`: repositories and the shared version catalog for the included build.
- `build-logic/build.gradle.kts`: `kotlin-dsl` plugin, Spotless and SpotBugs plugin dependencies, TestKit test dependencies.
- `build-logic/src/main/kotlin/agentframework.java-library-conventions.gradle.kts`: Java toolchain 17, `release` 17, UTF-8, compiler lint, reproducible archives, dependency locking.
- `build-logic/src/main/kotlin/agentframework.test-conventions.gradle.kts`: JUnit 5 and AssertJ wiring plus `testJava17`, `testJava21`, `testJava25`.
- `build-logic/src/main/kotlin/agentframework.quality-conventions.gradle.kts`: Spotless, Checkstyle, PMD, SpotBugs, JaCoCo, and the project-local `quality` task.
- `build-logic/src/test/kotlin/com/microsoft/agentframework/build/logic/ConventionPluginsTest.kt`: Gradle TestKit regression for plugin application and task graph.

### Static quality configuration

- `config/checkstyle/checkstyle.xml`: non-formatting source policy.
- `config/pmd/ruleset.xml`: high-signal source bug rules.
- `config/spotbugs/exclude.xml`: narrow generated-code exclusions.

### Repository policy project

- `build-tools/harness-policy/build.gradle.kts`: non-published policy project and its `policyCheck` task.
- `build-tools/harness-policy/gradle.lockfile`: generated dependency lock state.
- `build-tools/harness-policy/src/main/java/com/microsoft/agentframework/build/harness/RepositoryPaths.java`: repository-root discovery.
- `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/RepositoryGovernancePolicyTest.java`: instruction and adapter policy.
- `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/BuildContractPolicyTest.java`: wrapper, version catalog, formatter placement, Maven-absence, and lock policy.
- `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ArtifactContractTest.java`: JSON Schema 2020-12 contract and example validation.
- `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/WorkflowDocuments.java`: YAML workflow reader helper.
- `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/WorkflowPolicyTest.java`: workflow trust, pinning, permission, and fan-in policy.

### Agent artifact contracts

- `.harness/schemas/task-intent.schema.json` and `.harness/examples/task-intent.json`
- `.harness/schemas/change-context.schema.json` and `.harness/examples/change-context.json`
- `.harness/schemas/impact-set.schema.json` and `.harness/examples/impact-set.json`
- `.harness/schemas/test-plan.schema.json` and `.harness/examples/test-plan.json`
- `.harness/schemas/change-summary.schema.json` and `.harness/examples/change-summary.json`
- `.harness/schemas/verification-result.schema.json` and `.harness/examples/verification-result.json`
- `.harness/schemas/review-result.schema.json` and `.harness/examples/review-result.json`
- `.harness/schemas/run-score.schema.json` and `.harness/examples/run-score.json`

### Governance, CI, and documentation

- `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md`, `CONTRIBUTING.md`, `SECURITY.md`, `.github/CODEOWNERS`
- `.gitignore`, `.gitattributes`, `.editorconfig`
- `.github/workflows/ci.yml`, `.github/dependabot.yml`
- `docs/operations/github-actions-runner-contract.md`
- `README.md`

---

### Task 1: Establish the Gradle 9.7.0 wrapper and repository hygiene

**Files:**
- Create: `.gitignore`
- Create: `.gitattributes`
- Create: `.editorconfig`
- Create: `settings.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `build.gradle.kts`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.jar.sha256`
- Create: `gradlew`
- Create: `gradlew.bat`

**Interfaces:**
- Consumes: the approved design `docs/superpowers/specs/2026-08-10-gradle-kotlin-arc-foundation-design.md`.
- Produces: `./gradlew` on Gradle 9.7.0; root lifecycle tasks `policyCheck`, `quality`, `testJava17`, `testJava21`, `testJava25` that aggregate the same task name from every subproject and are wired into `check`; version catalog accessor `libs` for every build script.

- [ ] **Step 1: Confirm the repository contains no Maven build files**

Run:

```bash
git ls-files -- pom.xml mvnw mvnw.cmd .mvn '*/pom.xml' '**/pom.xml'
```

Expected: no output. If any path is printed, remove it before continuing:

```bash
maven_paths=$(git ls-files -- pom.xml mvnw mvnw.cmd .mvn '*/pom.xml' '**/pom.xml')
if [ -n "$maven_paths" ]; then
  printf '%s\n' "$maven_paths" | xargs git rm -r --quiet --
fi
git ls-files -- pom.xml mvnw mvnw.cmd .mvn '*/pom.xml' '**/pom.xml'
```

Expected after removal: no output.

- [ ] **Step 2: Create repository hygiene files**

Create `.gitignore`:

```text
build/
.gradle/
.kotlin/
out/
bin/
.gradle-bootstrap/

.idea/
*.iml
*.ipr
*.iws
.vscode/
.settings/
.classpath
.project

.DS_Store
Thumbs.db

*.log
*.hprof
hs_err_pid*
replay_pid*

.harness/runs/
.claude/settings.local.json
.gemini/settings.local.json
.copilot/
*.env
*.pem
*.key
kubeconfig
gradle-local.properties
```

Create `.gitattributes`:

```text
* text=auto eol=lf

*.bat text eol=crlf
gradlew text eol=lf

*.jar binary
*.zip binary
*.png binary
*.jpg binary
*.gif binary
*.ico binary

gradle/wrapper/gradle-wrapper.jar binary linguist-vendored
gradlew linguist-vendored
gradlew.bat linguist-vendored
```

Create `.editorconfig`:

```text
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 2

[*.java]
indent_size = 2
max_line_length = 100

[*.kts]
indent_size = 4

[*.md]
trim_trailing_whitespace = false

[Makefile]
indent_style = tab
```

- [ ] **Step 3: Create the root Gradle build definition**

Create `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "agent-framework-java"
```

Create `gradle.properties`:

```text
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=false
org.gradle.console=plain
org.gradle.warning.mode=all
org.gradle.java.installations.auto-detect=true
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.fromEnv=JAVA_HOME_17_X64,JAVA_HOME_21_X64,JAVA_HOME_25_X64
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8 --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
```

The `--add-exports` flags exist only so google-java-format 1.24.0 can run inside the Gradle daemon on JDK 17. No product code may rely on `jdk.compiler` internals.

Create `gradle/libs.versions.toml`:

```toml
[versions]
assertj = "3.27.7"
checkstyle = "12.3.1"
googleJavaFormat = "1.24.0"
jackson = "2.22.1"
jacoco = "0.8.15"
jsonSchemaValidator = "3.0.6"
junit = "5.14.4"
pmd = "7.26.0"
spotbugsPlugin = "6.5.10"
spotbugsTool = "4.10.3"
spotless = "8.9.0"

[libraries]
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
jackson-dataformat-yaml = { module = "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", version.ref = "jackson" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
networknt-json-schema-validator = { module = "com.networknt:json-schema-validator", version.ref = "jsonSchemaValidator" }
```

Create `build.gradle.kts`:

```kotlin
plugins {
    base
}

description = "Agent Framework for Java build harness root project."

val aggregatedVerificationTasks =
    mapOf(
        "policyCheck" to "Runs repository policy regression in every project that owns it.",
        "quality" to "Runs formatting and static analysis once on the Gradle runtime JDK 17.",
        "testJava17" to "Runs tests with the Eclipse Temurin 17 launcher.",
        "testJava21" to "Runs tests with the Eclipse Temurin 21 launcher.",
        "testJava25" to "Runs tests with the Eclipse Temurin 25 launcher."
    )

aggregatedVerificationTasks.forEach { (taskName, taskDescription) ->
    val aggregate =
        tasks.register(taskName) {
            group = "verification"
            description = taskDescription
            dependsOn(
                provider {
                    subprojects.mapNotNull { subproject -> subproject.tasks.findByName(taskName)?.path }
                }
            )
        }
    tasks.named("check") {
        dependsOn(aggregate)
    }
}
```

- [ ] **Step 4: Run the wrapper and verify it fails**

Run:

```bash
./gradlew --version
```

Expected: FAIL with `no such file or directory: ./gradlew`.

- [ ] **Step 5: Bootstrap the wrapper from a checksum-verified distribution**

Run:

```bash
mkdir -p .gradle-bootstrap
curl -fsSL -o .gradle-bootstrap/gradle-9.7.0-bin.zip \
  https://services.gradle.org/distributions/gradle-9.7.0-bin.zip
printf '%s  %s\n' \
  84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae \
  .gradle-bootstrap/gradle-9.7.0-bin.zip | shasum -a 256 -c -
unzip -q .gradle-bootstrap/gradle-9.7.0-bin.zip -d .gradle-bootstrap
.gradle-bootstrap/gradle-9.7.0/bin/gradle wrapper \
  --gradle-version 9.7.0 \
  --distribution-type bin \
  --gradle-distribution-sha256-sum 84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae
rm -rf .gradle-bootstrap
```

Expected: `.gradle-bootstrap/gradle-9.7.0-bin.zip: OK` from `shasum`, then `BUILD SUCCESSFUL` from the `wrapper` task, and `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties` now exist.

- [ ] **Step 6: Pin the wrapper properties and record the wrapper JAR checksum**

Replace `gradle/wrapper/gradle-wrapper.properties` with exactly:

```text
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionSha256Sum=84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae
distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.0-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

Create `gradle/wrapper/gradle-wrapper.jar.sha256`:

```text
7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d
```

Verify the generated JAR matches the pin:

```bash
printf '%s  %s\n' \
  "$(cat gradle/wrapper/gradle-wrapper.jar.sha256)" \
  gradle/wrapper/gradle-wrapper.jar | shasum -a 256 -c -
```

Expected: `gradle/wrapper/gradle-wrapper.jar: OK`.

- [ ] **Step 7: Run the wrapper and verify it now succeeds**

Run:

```bash
./gradlew --version
./gradlew check
```

Expected: the first command prints `Gradle 9.7.0` and `Launcher JVM: 17`; the second prints `BUILD SUCCESSFUL` with no subproject tasks, because no subproject exists yet.

- [ ] **Step 8: Commit the Gradle foundation**

Run:

```bash
git add .gitignore .gitattributes .editorconfig settings.gradle.kts build.gradle.kts \
  gradle.properties gradle gradlew gradlew.bat
git commit -m "build: add Gradle 9.7.0 Kotlin DSL foundation

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

Expected: one commit containing the wrapper, the version catalog, and the root build.

---

### Task 2: Add build-logic convention plugins with TestKit regression

**Files:**
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/build.gradle.kts`
- Create: `build-logic/src/test/kotlin/com/microsoft/agentframework/build/logic/ConventionPluginsTest.kt`
- Create: `build-logic/src/main/kotlin/agentframework.java-library-conventions.gradle.kts`
- Create: `build-logic/src/main/kotlin/agentframework.test-conventions.gradle.kts`
- Create: `build-logic/src/main/kotlin/agentframework.quality-conventions.gradle.kts`
- Create: `config/checkstyle/checkstyle.xml`
- Create: `config/pmd/ruleset.xml`
- Create: `config/spotbugs/exclude.xml`
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes: `gradle/libs.versions.toml`, the root aggregate tasks from Task 1.
- Produces: plugin ids `agentframework.java-library-conventions`, `agentframework.test-conventions`, `agentframework.quality-conventions`; project-local tasks `quality`, `testJava17`, `testJava21`, `testJava25`, `resolveAndLockAll`; root task `buildLogicTest`.

- [ ] **Step 1: Install the local Temurin 21 and 25 toolchains**

Run:

```bash
brew install --cask temurin@21
brew install --cask temurin@25
/usr/libexec/java_home -V
```

Expected: `/usr/libexec/java_home -V` lists Temurin 17, 21, and 25 under `/Library/Java/JavaVirtualMachines`. Gradle auto-detection uses these because `org.gradle.java.installations.auto-detect=true`.

- [ ] **Step 2: Create the included build and its failing TestKit regression**

Create `build-logic/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
```

Create `build-logic/build.gradle.kts`:

```kotlin
plugins {
    `kotlin-dsl`
}

group = "com.microsoft.agentframework.build"
description = "Convention plugins shared by every Agent Framework for Java project."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")
    implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:${libs.versions.spotbugsPlugin.get()}")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
    }
    systemProperty(
        "agentframework.versionCatalog",
        rootDir.resolve("../gradle/libs.versions.toml").canonicalPath
    )
}
```

Create `build-logic/src/test/kotlin/com/microsoft/agentframework/build/logic/ConventionPluginsTest.kt`:

```kotlin
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
```

- [ ] **Step 3: Wire the included build into the root build**

Replace the `pluginManagement` block at the top of `settings.gradle.kts` with:

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Append to `build.gradle.kts`:

```kotlin
val buildLogicTest =
    tasks.register("buildLogicTest") {
        group = "verification"
        description = "Runs Gradle TestKit regression for the included build-logic conventions."
        dependsOn(gradle.includedBuild("build-logic").task(":test"))
    }

tasks.named("check") {
    dependsOn(buildLogicTest)
}
```

- [ ] **Step 4: Run the TestKit regression and verify it fails**

Run:

```bash
./gradlew -p build-logic test
```

Expected: FAIL. Every test fails while applying the fixture build script with `Plugin [id: 'agentframework.java-library-conventions'] was not found`.

- [ ] **Step 5: Create the Java library convention plugin**

Create `build-logic/src/main/kotlin/agentframework.java-library-conventions.gradle.kts`:

```kotlin
plugins {
    id("java-library")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

configurations.matching { it.name.endsWith("Classpath") }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

tasks.register("resolveAndLockAll") {
    group = "build setup"
    description = "Resolves every locked classpath so Gradle can write dependency lock state."
    doLast {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "resolveAndLockAll must be invoked with --write-locks"
        }
        configurations
            .filter { it.isCanBeResolved && it.name.endsWith("Classpath") }
            .forEach { it.resolve() }
    }
}
```

- [ ] **Step 6: Create the test convention plugin**

Create `build-logic/src/main/kotlin/agentframework.test-conventions.gradle.kts`:

```kotlin
plugins {
    java
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaToolchains = extensions.getByType<JavaToolchainService>()

dependencies {
    "testImplementation"(platform(versionCatalog.findLibrary("junit-bom").get()))
    "testImplementation"(versionCatalog.findLibrary("junit-jupiter").get())
    "testImplementation"(versionCatalog.findLibrary("assertj-core").get())
    "testRuntimeOnly"(platform(versionCatalog.findLibrary("junit-bom").get()))
    "testRuntimeOnly"(versionCatalog.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    failFast = false
    systemProperty("java.awt.headless", "true")
    systemProperty("user.language", "en")
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("failed")
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register("testJava17") {
    group = "verification"
    description = "Runs tests with the Eclipse Temurin 17 launcher."
    dependsOn(tasks.named("test"))
}

listOf(21, 25).forEach { javaVersion ->
    val compatibilityTest =
        tasks.register<Test>("testJava$javaVersion") {
            group = "verification"
            description = "Runs tests with the Eclipse Temurin $javaVersion launcher."
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(javaVersion))
                }
            )
            testClassesDirs = sourceSets["test"].output.classesDirs
            classpath = sourceSets["test"].runtimeClasspath
        }
    tasks.named("check") {
        dependsOn(compatibilityTest)
    }
}
```

- [ ] **Step 7: Create the quality convention plugin and its tool configuration**

Create `build-logic/src/main/kotlin/agentframework.quality-conventions.gradle.kts`:

```kotlin
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    java
    checkstyle
    pmd
    jacoco
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(versionCatalog.findVersion("googleJavaFormat").get().requiredVersion)
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("gradleScripts") {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = versionCatalog.findVersion("checkstyle").get().requiredVersion
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

pmd {
    toolVersion = versionCatalog.findVersion("pmd").get().requiredVersion
    isConsoleOutput = true
    ruleSets = emptyList()
    ruleSetFiles = files(rootProject.file("config/pmd/ruleset.xml"))
    isIgnoreFailures = false
}

spotbugs {
    toolVersion.set(versionCatalog.findVersion("spotbugsTool").get().requiredVersion)
    effort.set(Effort.MAX)
    reportLevel.set(Confidence.MEDIUM)
    excludeFilter.set(rootProject.layout.projectDirectory.file("config/spotbugs/exclude.xml"))
    ignoreFailures.set(false)
}

jacoco {
    toolVersion = versionCatalog.findVersion("jacoco").get().requiredVersion
}

tasks.withType<SpotBugsTask>().configureEach {
    reports.create("xml") {
        required.set(true)
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val quality =
    tasks.register("quality") {
        group = "verification"
        description = "Runs formatting, static analysis, and coverage reporting on the Gradle runtime JDK."
        dependsOn(
            tasks.named("spotlessCheck"),
            tasks.named("checkstyleMain"),
            tasks.named("checkstyleTest"),
            tasks.named("pmdMain"),
            tasks.named("pmdTest"),
            tasks.named("spotbugsMain"),
            tasks.named("spotbugsTest"),
            tasks.named("jacocoTestReport")
        )
    }

tasks.named("check") {
    dependsOn(quality)
}
```

Create `config/checkstyle/checkstyle.xml`:

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
    "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
    "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
  <property name="charset" value="UTF-8"/>
  <property name="severity" value="error"/>
  <module name="FileTabCharacter">
    <property name="eachLine" value="true"/>
  </module>
  <module name="LineLength">
    <property name="max" value="120"/>
    <property name="ignorePattern" value="^package|^import|https?://"/>
  </module>
  <module name="TreeWalker">
    <module name="AvoidStarImport"/>
    <module name="UnusedImports"/>
    <module name="OneTopLevelClass"/>
    <module name="NeedBraces"/>
    <module name="EmptyBlock">
      <property name="option" value="text"/>
    </module>
    <module name="MissingSwitchDefault"/>
    <module name="FallThrough"/>
    <module name="EqualsHashCode"/>
    <module name="CovariantEquals"/>
    <module name="IllegalCatch">
      <property name="illegalClassNames" value="java.lang.Throwable"/>
    </module>
    <module name="IllegalThrows">
      <property name="illegalClassNames" value="java.lang.Throwable"/>
    </module>
  </module>
</module>
```

Create `config/pmd/ruleset.xml`:

```xml
<?xml version="1.0"?>
<ruleset name="Agent Framework Java Rules"
         xmlns="http://pmd.sourceforge.net/ruleset/2.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://pmd.sourceforge.net/ruleset/2.0.0 https://pmd.sourceforge.io/ruleset_2_0_0.xsd">
  <description>High-signal source checks that do not duplicate formatting.</description>
  <rule ref="category/java/errorprone.xml/CloseResource"/>
  <rule ref="category/java/errorprone.xml/CompareObjectsWithEquals"/>
  <rule ref="category/java/errorprone.xml/ConstructorCallsOverridableMethod"/>
  <rule ref="category/java/errorprone.xml/EmptyCatchBlock"/>
  <rule ref="category/java/errorprone.xml/ReturnFromFinallyBlock"/>
  <rule ref="category/java/errorprone.xml/UseProperClassLoader"/>
  <rule ref="category/java/bestpractices.xml/AvoidReassigningParameters"/>
  <rule ref="category/java/bestpractices.xml/UnusedFormalParameter"/>
</ruleset>
```

Create `config/spotbugs/exclude.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<FindBugsFilter>
  <Match>
    <Class name="~.*\.generated\..*"/>
  </Match>
</FindBugsFilter>
```

- [ ] **Step 8: Run the TestKit regression and verify it passes**

Run:

```bash
./gradlew -p build-logic test
```

Expected: `BUILD SUCCESSFUL`; five tests in `ConventionPluginsTest` pass.

- [ ] **Step 9: Run the full root check**

Run:

```bash
./gradlew check
```

Expected: `BUILD SUCCESSFUL`. `:build-logic:test` executes through `buildLogicTest`; the aggregate tasks still resolve to nothing because no subproject exists.

- [ ] **Step 10: Commit the convention plugins**

Run:

```bash
git add settings.gradle.kts build.gradle.kts build-logic config
git commit -m "build: add Gradle convention plugins and quality configuration

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Add the policy project and repository governance contract

**Files:**
- Create: `build-tools/harness-policy/build.gradle.kts`
- Create: `build-tools/harness-policy/src/main/java/com/microsoft/agentframework/build/harness/RepositoryPaths.java`
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/RepositoryGovernancePolicyTest.java`
- Create: `AGENTS.md`
- Create: `CLAUDE.md`
- Create: `GEMINI.md`
- Create: `.github/copilot-instructions.md`
- Create: `CONTRIBUTING.md`
- Create: `SECURITY.md`
- Create: `.github/CODEOWNERS`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: the three convention plugin ids from Task 2.
- Produces: Gradle project `:build-tools:harness-policy` with tasks `policyCheck`, `quality`, `testJava17`, `testJava21`, `testJava25`; `RepositoryPaths.root()` returning the repository root `Path`; the canonical `AGENTS.md` contract.

- [ ] **Step 1: Register the policy project**

Append to `settings.gradle.kts`:

```kotlin
include(":build-tools:harness-policy")
```

Create `build-tools/harness-policy/build.gradle.kts`:

```kotlin
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
```

- [ ] **Step 2: Write the failing governance policy test**

Create `build-tools/harness-policy/src/main/java/com/microsoft/agentframework/build/harness/RepositoryPaths.java`:

```java
package com.microsoft.agentframework.build.harness;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the repository root from any Gradle project working directory. */
public final class RepositoryPaths {

  private RepositoryPaths() {}

  /**
   * Returns the repository root directory.
   *
   * @return the closest ancestor directory that contains both {@code settings.gradle.kts} and
   *     {@code gradle/libs.versions.toml}
   * @throws IllegalStateException when no ancestor directory contains both markers
   */
  public static Path root() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      boolean hasSettings = Files.isRegularFile(candidate.resolve("settings.gradle.kts"));
      boolean hasCatalog = Files.isRegularFile(candidate.resolve("gradle/libs.versions.toml"));
      if (hasSettings && hasCatalog) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "Cannot locate the repository root from the working directory.");
  }
}
```

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/RepositoryGovernancePolicyTest.java`:

```java
package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RepositoryGovernancePolicyTest {

  private static final List<String> REQUIRED_FILES =
      List.of(
          "AGENTS.md",
          "CLAUDE.md",
          "GEMINI.md",
          ".github/copilot-instructions.md",
          "CONTRIBUTING.md",
          "SECURITY.md",
          ".github/CODEOWNERS",
          ".editorconfig",
          ".gitattributes",
          ".gitignore");

  private static final List<String> VENDOR_ADAPTERS =
      List.of("CLAUDE.md", "GEMINI.md", ".github/copilot-instructions.md");

  private static final List<String> REQUIRED_AGENT_SECTIONS =
      List.of(
          "## Architecture boundaries",
          "## Standard workflow",
          "## Verification contract",
          "## Sensitive data",
          "## Prohibited changes");

  static Stream<String> requiredFiles() {
    return REQUIRED_FILES.stream();
  }

  static Stream<String> vendorAdapters() {
    return VENDOR_ADAPTERS.stream();
  }

  static Stream<String> requiredAgentSections() {
    return REQUIRED_AGENT_SECTIONS.stream();
  }

  @ParameterizedTest(name = "{0} exists")
  @MethodSource("requiredFiles")
  void requiredGovernanceFileExists(String relativePath) {
    assertThat(RepositoryPaths.root().resolve(relativePath)).isRegularFile();
  }

  @ParameterizedTest(name = "AGENTS.md declares {0}")
  @MethodSource("requiredAgentSections")
  void canonicalInstructionsDeclareRequiredSection(String heading) throws IOException {
    String text = read("AGENTS.md");

    assertThat(text).contains(heading);
  }

  @ParameterizedTest(name = "{0} stays a thin adapter")
  @MethodSource("vendorAdapters")
  void vendorAdapterStaysThin(String relativePath) throws IOException {
    Path adapter = RepositoryPaths.root().resolve(relativePath);
    List<String> lines = Files.readAllLines(adapter, StandardCharsets.UTF_8);
    String text = String.join("\n", lines);

    assertThat(lines).hasSizeLessThanOrEqualTo(20);
    assertThat(text).contains("AGENTS.md");
    assertThat(text).doesNotContain("## Architecture boundaries");
    assertThat(text).doesNotContain("## Verification contract");
  }

  @Test
  void canonicalInstructionsDescribeTheGradleVerificationEntryPoints() throws IOException {
    String text = read("AGENTS.md");

    assertThat(text).contains("./gradlew policyCheck");
    assertThat(text).contains("./gradlew quality");
    assertThat(text).contains("./gradlew testJava17 testJava21 testJava25");
    assertThat(text).contains("./gradlew check");
    assertThat(text).doesNotContain("mvnw");
  }

  @Test
  void contributingGuideUsesTheGradleWrapper() throws IOException {
    String text = read("CONTRIBUTING.md");

    assertThat(text).contains("./gradlew check");
    assertThat(text).doesNotContain("mvnw");
  }

  @Test
  void codeOwnersProtectTheHarnessSurface() throws IOException {
    String text = read(".github/CODEOWNERS");

    assertThat(text).contains("/AGENTS.md");
    assertThat(text).contains("/.harness/");
    assertThat(text).contains("/.github/");
    assertThat(text).contains("/build-logic/");
    assertThat(text).contains("/gradle/");
  }

  private static String read(String relativePath) throws IOException {
    return Files.readString(RepositoryPaths.root().resolve(relativePath), StandardCharsets.UTF_8);
  }
}
```

- [ ] **Step 3: Run the governance test and verify it fails**

Run:

```bash
./gradlew :build-tools:harness-policy:test --tests '*RepositoryGovernancePolicyTest*'
```

Expected: FAIL. `requiredGovernanceFileExists` fails for `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md`, `CONTRIBUTING.md`, `SECURITY.md`, and `.github/CODEOWNERS`, and the remaining tests fail with `NoSuchFileException`.

- [ ] **Step 4: Create the canonical instruction contract**

Create `AGENTS.md`:

````markdown
# Agent Framework for Java Repository Instructions

## Repository purpose

This repository implements Microsoft Agent Framework's observable execution semantics for Java.
The core deliverable is an embeddable `AgentEngine`, not an application server or DI container.
Read the approved designs under `docs/superpowers/specs/` and the pinned upstream metadata under
`docs/upstream/` before changing contracts.

## Source of truth

Use evidence in this order:

1. Pinned upstream production source.
2. Pinned upstream conformance and integration tests.
3. Pinned specifications and decision records.
4. Official Microsoft documentation.
5. Samples and README files.

Do not mix current upstream `main` behavior into the pinned snapshot without a reviewed delta.

## Architecture boundaries

- API modules must not depend on Spring, Quarkus, Jakarta EE, Micronaut, or provider SDKs.
- Engine modules depend only on public API and explicitly approved framework-neutral libraries.
- Provider and host adapters implement public ports and never reference engine internals.
- Core code does not create DI containers, HTTP servers, executors, schedulers, shutdown hooks,
  transaction managers, or global registries.
- Samples and testkits are never product dependencies.
- Provider-specific behavior is not promoted to a core contract without cross-provider evidence.

## Build ownership

- The build is Gradle Kotlin DSL only. Maven files must never be reintroduced.
- The Gradle Wrapper pins Gradle 9.7.0 with a verified distribution checksum.
- Dependency and tool versions live only in `gradle/libs.versions.toml`.
- Shared build behavior lives in the `build-logic` included build, never copied into project scripts.
- google-java-format runs only in `agentframework.quality-conventions` on the Gradle runtime JDK 17.

## Standard workflow

1. Read the relevant design, public contract, usages, and neighboring tests.
2. Define the affected projects and observable success criteria.
3. For behavior changes, add a failing unit or contract test first.
4. Make the smallest coherent implementation that passes the test.
5. Run the narrowest relevant Gradle task, then expand verification by risk.
6. Review the diff for public API, dependency, session-format, telemetry, and compatibility impact.

Reuse existing helpers and fixtures. Keep edits inside the affected projects. Do not hide failures
with broad catches, silent fallbacks, deleted assertions, or `@Disabled`.

## Verification contract

Local and CI verification use the same Gradle tasks:

```bash
./gradlew policyCheck
./gradlew quality
./gradlew testJava17 testJava21 testJava25
./gradlew check
```

- CI-only quality logic is prohibited.
- Deterministic tests do not retry.
- A quality failure is never covered by a passing compatibility test.
- Public API changes require compatibility analysis after the first release.
- Instruction, workflow, contract, or build changes run `./gradlew policyCheck`.
- Final reports list commands run, outcomes, and any checks that could not run.

## Sensitive data

Prompt bodies, model responses, tool arguments, credentials, and personal agent traces are not
recorded by default. Commit only redacted examples. Keep local agent permissions, model choices,
tokens, credentials, and run artifacts in ignored files.

## Prohibited changes

- Do not commit secrets or local credentials.
- Do not reintroduce Maven build files.
- Do not automatically release, force-push, or change AKS/ARC infrastructure.
- Do not add framework types to core public APIs.
- Do not add blanket lint, vulnerability, or compatibility suppressions.
- Do not use `pull_request_target` or an unpinned external action.
- Do not move the upstream pin without a reviewed behavior delta.
- Do not treat exact natural-language output or exact tool-call order as a long-term contract.
````

- [ ] **Step 5: Create the thin vendor adapters**

Create `CLAUDE.md`:

```markdown
# Claude Repository Entry Point

Read and follow `AGENTS.md` as the canonical repository instruction contract.
Keep personal permissions, model selection, telemetry endpoints, and cost limits in ignored local
settings, not in this file.
```

Create `GEMINI.md`:

```markdown
# Gemini Repository Entry Point

Read and follow `AGENTS.md` as the canonical repository instruction contract.
Keep personal permissions, model selection, telemetry endpoints, and cost limits in ignored local
settings, not in this file.
```

Create `.github/copilot-instructions.md`:

```markdown
# GitHub Copilot Repository Entry Point

Read and follow `/AGENTS.md` as the canonical repository instruction contract.
Repository architecture, build, testing, security, and completion rules live there.
```

- [ ] **Step 6: Create the human contribution, security, and ownership files**

Create `CONTRIBUTING.md`:

````markdown
# Contributing

Before changing code, read `AGENTS.md`, the approved designs in `docs/superpowers/specs/`, and the
pinned upstream guidance in `docs/upstream/`.

Use the committed Gradle Wrapper:

```bash
./gradlew check
```

Narrower entry points during development:

```bash
./gradlew policyCheck
./gradlew quality
./gradlew testJava17 testJava21 testJava25
```

Formatting is applied, not argued about:

```bash
./gradlew spotlessApply
```

Behavior changes require a failing test before implementation. Keep pull requests focused and state:

- the observable behavior changed;
- affected projects and public contracts;
- commands run and their results;
- upstream provenance when implementing MAF-compatible behavior.

Do not include credentials, prompt/model content, personal traces, or local agent configuration.
Java 21 and Java 25 compatibility tasks need locally installed Eclipse Temurin toolchains; Gradle
never downloads a JDK for you.
````

Create `SECURITY.md`:

```markdown
# Security Policy

Do not report vulnerabilities in public issues. Use the repository host's private vulnerability
reporting feature or contact the maintainers through the private channel configured for the project.

Reports should include affected versions, impact, reproduction steps, and suggested mitigations
without including production credentials or sensitive prompt/model data.

The project does not accept committed secrets, default recording of prompt/tool content, broad
vulnerability suppressions, unpinned GitHub Actions, or unreviewed workflow permission increases.
```

Create `.github/CODEOWNERS`:

```text
* @hwang-inhwan
/AGENTS.md @hwang-inhwan
/CLAUDE.md @hwang-inhwan
/GEMINI.md @hwang-inhwan
/.harness/ @hwang-inhwan
/.github/ @hwang-inhwan
/build-logic/ @hwang-inhwan
/build-tools/ @hwang-inhwan
/config/ @hwang-inhwan
/gradle/ @hwang-inhwan
/settings.gradle.kts @hwang-inhwan
/build.gradle.kts @hwang-inhwan
/docs/upstream/ @hwang-inhwan
/docs/superpowers/specs/ @hwang-inhwan
```

- [ ] **Step 7: Run the governance test and verify it passes**

Run:

```bash
./gradlew spotlessApply
./gradlew :build-tools:harness-policy:test --tests '*RepositoryGovernancePolicyTest*'
```

Expected: `BUILD SUCCESSFUL`; 10 required-file cases, 5 section cases, 3 adapter cases, and 3 single tests pass.

- [ ] **Step 8: Run the full check**

Run:

```bash
./gradlew check
```

Expected: `BUILD SUCCESSFUL`. `:build-tools:harness-policy:quality`, `:build-tools:harness-policy:testJava17`, `:testJava21`, and `:testJava25` all execute.

- [ ] **Step 9: Commit the governance contract**

Run:

```bash
git add settings.gradle.kts build-tools AGENTS.md CLAUDE.md GEMINI.md \
  CONTRIBUTING.md SECURITY.md .github
git commit -m "docs: establish Gradle repository engineering contract

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: Add the eight agent artifact contracts with real JSON Schema validation

**Files:**
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ArtifactContractTest.java`
- Create: `.harness/schemas/task-intent.schema.json`
- Create: `.harness/schemas/change-context.schema.json`
- Create: `.harness/schemas/impact-set.schema.json`
- Create: `.harness/schemas/test-plan.schema.json`
- Create: `.harness/schemas/change-summary.schema.json`
- Create: `.harness/schemas/verification-result.schema.json`
- Create: `.harness/schemas/review-result.schema.json`
- Create: `.harness/schemas/run-score.schema.json`
- Create: `.harness/examples/task-intent.json`
- Create: `.harness/examples/change-context.json`
- Create: `.harness/examples/impact-set.json`
- Create: `.harness/examples/test-plan.json`
- Create: `.harness/examples/change-summary.json`
- Create: `.harness/examples/verification-result.json`
- Create: `.harness/examples/review-result.json`
- Create: `.harness/examples/run-score.json`

**Interfaces:**
- Consumes: `RepositoryPaths.root()`, `libs.networknt.json.schema.validator`, `libs.jackson.databind`.
- Produces: eight `$id`-addressed JSON Schema 2020-12 contracts under `https://agent-framework-java.dev/harness/`, one valid example each, and four parameterized regressions per contract.

`com.networknt:json-schema-validator:3.0.6` is built on Jackson 3 (`tools.jackson.*`) and exposes
`SchemaRegistry`, `Schema`, `Error`, `SpecificationVersion`, and `InputFormat`. The test therefore
feeds it raw JSON text and uses Jackson 2 (`com.fasterxml.jackson.*`) only for structural
inspection. Do not attempt to pass a Jackson 2 `JsonNode` into the validator.

- [ ] **Step 1: Write the failing artifact contract test**

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ArtifactContractTest.java`:

```java
package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ArtifactContractTest {

  private static final String SCHEMA_ID_PREFIX = "https://agent-framework-java.dev/harness/";

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final SchemaRegistry SCHEMA_REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  private static final List<String> CONTRACTS =
      List.of(
          "task-intent",
          "change-context",
          "impact-set",
          "test-plan",
          "change-summary",
          "verification-result",
          "review-result",
          "run-score");

  static Stream<String> contracts() {
    return CONTRACTS.stream();
  }

  @ParameterizedTest(name = "{0} declares a closed 2020-12 schema")
  @MethodSource("contracts")
  void schemaIsClosedAndUsesDraft202012(String contractName) throws IOException {
    JsonNode schema = JSON.readTree(schemaText(contractName));

    assertThat(textOf(schema, "$schema")).isEqualTo("https://json-schema.org/draft/2020-12/schema");
    assertThat(textOf(schema, "$id")).isEqualTo(SCHEMA_ID_PREFIX + contractName + ".schema.json");
    assertThat(textOf(schema, "type")).isEqualTo("object");
    assertThat(schema.path("required").isArray()).isTrue();
    assertThat(schema.path("required")).isNotEmpty();
    assertClosedObjectSchemas(contractName, schema);
  }

  @ParameterizedTest(name = "{0} example validates against its schema")
  @MethodSource("contracts")
  void exampleValidatesAgainstSchema(String contractName) throws IOException {
    Schema schema = SCHEMA_REGISTRY.getSchema(schemaText(contractName));

    List<Error> errors = schema.validate(exampleText(contractName), InputFormat.JSON);

    assertThat(errors).isEmpty();
  }

  @ParameterizedTest(name = "{0} rejects an undeclared property")
  @MethodSource("contracts")
  void undeclaredPropertyIsRejected(String contractName) throws IOException {
    Schema schema = SCHEMA_REGISTRY.getSchema(schemaText(contractName));
    ObjectNode mutated = (ObjectNode) JSON.readTree(exampleText(contractName));
    mutated.put("undeclaredPolicyProbe", "rejected");

    List<Error> errors = schema.validate(JSON.writeValueAsString(mutated), InputFormat.JSON);

    assertThat(errors).isNotEmpty();
    assertThat(errors).anyMatch(error -> "additionalProperties".equals(error.getKeyword()));
  }

  @ParameterizedTest(name = "{0} example pins schemaVersion 1.0")
  @MethodSource("contracts")
  void examplePinsSchemaVersion(String contractName) throws IOException {
    JsonNode example = JSON.readTree(exampleText(contractName));

    assertThat(textOf(example, "schemaVersion")).isEqualTo("1.0");
  }

  private static void assertClosedObjectSchemas(String contractName, JsonNode root) {
    Deque<JsonNode> pending = new ArrayDeque<>();
    pending.push(root);
    while (!pending.isEmpty()) {
      JsonNode node = pending.pop();
      if (node.isObject() && "object".equals(textOf(node, "type"))) {
        JsonNode additionalProperties = node.path("additionalProperties");
        assertThat(additionalProperties.isBoolean())
            .as("%s declares additionalProperties on every object schema", contractName)
            .isTrue();
        assertThat(additionalProperties.booleanValue())
            .as("%s sets additionalProperties to false on every object schema", contractName)
            .isFalse();
      }
      node.forEach(pending::push);
    }
  }

  private static String textOf(JsonNode node, String fieldName) {
    return node.path(fieldName).textValue();
  }

  private static String schemaText(String contractName) throws IOException {
    return read(".harness/schemas/" + contractName + ".schema.json");
  }

  private static String exampleText(String contractName) throws IOException {
    return read(".harness/examples/" + contractName + ".json");
  }

  private static String read(String relativePath) throws IOException {
    return Files.readString(RepositoryPaths.root().resolve(relativePath), StandardCharsets.UTF_8);
  }
}
```

- [ ] **Step 2: Run the contract test and verify it fails**

Run:

```bash
./gradlew :build-tools:harness-policy:test --tests '*ArtifactContractTest*'
```

Expected: FAIL. Every case fails with `NoSuchFileException` on `.harness/schemas/task-intent.schema.json`.

- [ ] **Step 3: Create the intent and context contracts**

Create `.harness/schemas/task-intent.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://agent-framework-java.dev/harness/task-intent.schema.json",
  "title": "TaskIntent",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "request", "nonGoals", "successCriteria", "risk"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "request": { "type": "string", "minLength": 1 },
    "nonGoals": { "type": "array", "items": { "type": "string", "minLength": 1 } },
    "successCriteria": {
      "type": "array",
      "minItems": 1,
      "items": { "type": "string", "minLength": 1 }
    },
    "risk": { "enum": ["LOW", "MEDIUM", "HIGH", "CRITICAL"] }
  }
}
```

Create `.harness/examples/task-intent.json`:

```json
{
  "schemaVersion": "1.0",
  "request": "Add a deterministic tool-loop regression test.",
  "nonGoals": ["Change the public API", "Call a live model"],
  "successCriteria": ["The new test fails before the fix and passes after it."],
  "risk": "MEDIUM"
}
```

Create `.harness/schemas/change-context.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://agent-framework-java.dev/harness/change-context.schema.json",
  "title": "ChangeContext",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "schemaVersion",
    "repositoryRevision",
    "relevantFiles",
    "affectedProjects",
    "evidence"
  ],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "repositoryRevision": { "type": "string", "minLength": 1 },
    "relevantFiles": {
      "type": "array",
      "uniqueItems": true,
      "items": { "type": "string", "minLength": 1 }
    },
    "affectedProjects": {
      "type": "array",
      "uniqueItems": true,
      "items": { "type": "string", "minLength": 1 }
    },
    "evidence": {
      "type": "array",
      "minItems": 1,
      "items": { "type": "string", "minLength": 1 }
    }
  }
}
```

Create `.harness/examples/change-context.json`:

```json
{
  "schemaVersion": "1.0",
  "repositoryRevision": "0123456789abcdef0123456789abcdef01234567",
  "relevantFiles": ["AGENTS.md", "docs/upstream/README.md"],
  "affectedProjects": [":build-tools:harness-policy"],
  "evidence": ["AGENTS.md defines the verification contract."]
}
```

- [ ] **Step 4: Create the impact and test-plan contracts**

Create `.harness/schemas/impact-set.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://agent-framework-java.dev/harness/impact-set.schema.json",
  "title": "ImpactSet",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "writablePaths", "affectedProjects", "riskTier", "requiredGates"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "writablePaths": {
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": { "type": "string", "minLength": 1 }
    },
    "affectedProjects": {
      "type": "array",
      "uniqueItems": true,
      "items": { "type": "string", "minLength": 1 }
    },
    "riskTier": { "enum": ["LOW", "MEDIUM", "HIGH", "CRITICAL"] },
    "requiredGates": {
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": {
        "enum": ["POLICY", "BUILD", "STATIC", "ARCHITECTURE", "CONFORMANCE", "HUMAN"]
      }
    }
  }
}
```

Create `.harness/examples/impact-set.json`:

```json
{
  "schemaVersion": "1.0",
  "writablePaths": ["build-tools/harness-policy/**", ".harness/**"],
  "affectedProjects": [":build-tools:harness-policy"],
  "riskTier": "MEDIUM",
  "requiredGates": ["POLICY", "BUILD", "STATIC"]
}
```

Create `.harness/schemas/test-plan.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://agent-framework-java.dev/harness/test-plan.schema.json",
  "title": "TestPlan",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "tests"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "tests": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["id", "command", "expectedInitialOutcome", "required"],
        "properties": {
          "id": { "type": "string", "minLength": 1 },
          "command": { "type": "string", "minLength": 1 },
          "expectedInitialOutcome": { "enum": ["PASS", "FAIL"] },
          "required": { "type": "boolean" }
        }
      }
    }
  }
}
```

Create `.harness/examples/test-plan.json`:

```json
{
  "schemaVersion": "1.0",
  "tests": [
    {
      "id": "artifact-contract",
      "command": "./gradlew :build-tools:harness-policy:test --tests '*ArtifactContractTest*'",
      "expectedInitialOutcome": "FAIL",
      "required": true
    }
  ]
}
```

- [ ] **Step 5: Create the change-summary and verification contracts**

Create `.harness/schemas/change-summary.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://agent-framework-java.dev/harness/change-summary.schema.json",
  "title": "ChangeSummary",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "changedFiles", "publicContractImpact", "migrationNotes"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "changedFiles": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["path", "changeType"],
        "properties": {
          "path": { "type": "string", "minLength": 1 },
          "changeType": { "enum": ["ADDED", "MODIFIED", "DELETED", "RENAMED"] }
        }
      }
    },
    "publicContractImpact": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "publicApiChanged",
        "sessionFormatChanged",
        "telemetryChanged",
        "dependencyChanged"
      ],
      "properties": {
        "publicApiChanged": { "type": "boolean" },
        "sessionFormatChanged": { "type": "boolean" },
        "telemetryChanged": { "type": "boolean" },
        "dependencyChanged": { "type": "boolean" }
      }
    },
    "migrationNotes": {
      "type": "array",
      "items": { "type": "string", "minLength": 1 }
    }
  }
}
```

Create `.harness/examples/change-summary.json`:

```json
{
  "schemaVersion": "1.0",
  "changedFiles": [
    { "path": "build-tools/harness-policy/build.gradle.kts", "changeType": "MODIFIED" },
    { "path": ".harness/schemas/change-summary.schema.json", "changeType": "ADDED" }
  ],
  "publicContractImpact": {
    "publicApiChanged": false,
    "sessionFormatChanged": false,
    "telemetryChanged": false,
    "dependencyChanged": true
  },
  "migrationNotes": []
}
```

Create `.harness/schemas/verification-result.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://agent-framework-java.dev/harness/verification-result.schema.json",
  "title": "VerificationResult",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "checks", "overallStatus"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "checks": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["name", "command", "exitCode", "status", "durationMs"],
        "properties": {
          "name": { "type": "string", "minLength": 1 },
          "command": { "type": "string", "minLength": 1 },
          "exitCode": { "type": "integer", "minimum": 0 },
          "status": { "enum": ["PASS", "FAIL", "BLOCKED"] },
          "durationMs": { "type": "integer", "minimum": 0 },
          "artifactPath": { "type": "string", "minLength": 1 }
        }
      }
    },
    "overallStatus": { "enum": ["PASS", "FAIL", "BLOCKED"] }
  }
}
```

Create `.harness/examples/verification-result.json`:

```json
{
  "schemaVersion": "1.0",
  "checks": [
    {
      "name": "repository policy",
      "command": "./gradlew policyCheck",
      "exitCode": 0,
      "status": "PASS",
      "durationMs": 4200,
      "artifactPath": "build-tools/harness-policy/build/reports/tests/test/index.html"
    },
    {
      "name": "quality",
      "command": "./gradlew quality",
      "exitCode": 0,
      "status": "PASS",
      "durationMs": 18300
    }
  ],
  "overallStatus": "PASS"
}
```

- [ ] **Step 6: Create the review and score contracts**

Create `.harness/schemas/review-result.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://agent-framework-java.dev/harness/review-result.schema.json",
  "title": "ReviewResult",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "findings", "overallDisposition"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "findings": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["id", "category", "severity", "location", "summary", "disposition"],
        "properties": {
          "id": { "type": "string", "minLength": 1 },
          "category": {
            "enum": ["CORRECTNESS", "SECURITY", "PERFORMANCE", "MAINTAINABILITY", "CONTRACT"]
          },
          "severity": { "enum": ["LOW", "MEDIUM", "HIGH", "CRITICAL"] },
          "location": { "type": "string", "minLength": 1 },
          "summary": { "type": "string", "minLength": 1 },
          "disposition": { "enum": ["ACCEPTED", "REJECTED", "DEFERRED", "FIXED"] }
        }
      }
    },
    "overallDisposition": { "enum": ["APPROVED", "CHANGES_REQUESTED", "BLOCKED"] }
  }
}
```

Create `.harness/examples/review-result.json`:

```json
{
  "schemaVersion": "1.0",
  "findings": [
    {
      "id": "review-1",
      "category": "SECURITY",
      "severity": "HIGH",
      "location": ".github/workflows/ci.yml",
      "summary": "A trusted job must never run fork-authored code.",
      "disposition": "FIXED"
    }
  ],
  "overallDisposition": "APPROVED"
}
```

Create `.harness/schemas/run-score.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://agent-framework-java.dev/harness/run-score.schema.json",
  "title": "RunScore",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "suite", "configuration", "passed", "metrics", "violations"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "suite": { "type": "string", "minLength": 1 },
    "configuration": {
      "type": "object",
      "additionalProperties": false,
      "required": ["instructionHash", "harnessVersion", "toolchain", "upstreamRevision"],
      "properties": {
        "instructionHash": { "type": "string", "minLength": 1 },
        "harnessVersion": { "type": "string", "minLength": 1 },
        "toolchain": { "type": "string", "minLength": 1 },
        "upstreamRevision": { "type": "string", "minLength": 1 }
      }
    },
    "passed": { "type": "boolean" },
    "metrics": {
      "type": "object",
      "additionalProperties": false,
      "required": ["durationMs", "toolCalls", "scopeViolations"],
      "properties": {
        "durationMs": { "type": "integer", "minimum": 0 },
        "toolCalls": { "type": "integer", "minimum": 0 },
        "scopeViolations": { "type": "integer", "minimum": 0 }
      }
    },
    "violations": { "type": "array", "items": { "type": "string", "minLength": 1 } }
  }
}
```

Create `.harness/examples/run-score.json`:

```json
{
  "schemaVersion": "1.0",
  "suite": "repository-policy",
  "configuration": {
    "instructionHash": "sha256:0123456789abcdef",
    "harnessVersion": "1.0",
    "toolchain": "gradle-9.7.0-java-17",
    "upstreamRevision": "d0a4165f170193ba1d026a259af40d35bb7eaefe"
  },
  "passed": true,
  "metrics": {
    "durationMs": 4200,
    "toolCalls": 3,
    "scopeViolations": 0
  },
  "violations": []
}
```

- [ ] **Step 7: Run the contract test and verify it passes**

Run:

```bash
./gradlew spotlessApply
./gradlew :build-tools:harness-policy:test --tests '*ArtifactContractTest*'
```

Expected: `BUILD SUCCESSFUL`; 32 parameterized cases pass (8 contracts times 4 checks).

- [ ] **Step 8: Run the full check**

Run:

```bash
./gradlew check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit the artifact contracts**

Run:

```bash
git add build-tools/harness-policy .harness
git commit -m "test: add eight validated agent artifact contracts

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Lock and regression-test the build contract

**Files:**
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/BuildContractPolicyTest.java`
- Create: `build-tools/harness-policy/gradle.lockfile`

**Interfaces:**
- Consumes: `RepositoryPaths.root()`, the `resolveAndLockAll` task from Task 2.
- Produces: regression that fails when the wrapper pin, tool versions, formatter placement, Java baseline, toolchain policy, dependency lock state, or Maven absence changes.

- [ ] **Step 1: Write the failing build contract test**

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/BuildContractPolicyTest.java`:

```java
package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildContractPolicyTest {

  private static final String DISTRIBUTION_SHA256 =
      "84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae";

  private static final String WRAPPER_JAR_SHA256 =
      "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d";

  private static final String QUALITY_CONVENTION =
      "build-logic/src/main/kotlin/agentframework.quality-conventions.gradle.kts";

  private static final String JAVA_LIBRARY_CONVENTION =
      "build-logic/src/main/kotlin/agentframework.java-library-conventions.gradle.kts";

  private static final String TEST_CONVENTION =
      "build-logic/src/main/kotlin/agentframework.test-conventions.gradle.kts";

  private static final List<String> FORBIDDEN_MAVEN_NAMES =
      List.of("pom.xml", "mvnw", "mvnw.cmd", ".mvn");

  private static final Set<String> IGNORED_DIRECTORIES =
      Set.of(".git", "build", ".gradle", ".kotlin", ".worktrees");

  @Test
  void wrapperPinsGradle970WithAVerifiedDistribution() throws IOException {
    String properties = read("gradle/wrapper/gradle-wrapper.properties");

    assertThat(properties).contains("gradle-9.7.0-bin.zip");
    assertThat(properties).contains("distributionSha256Sum=" + DISTRIBUTION_SHA256);
    assertThat(properties).contains("validateDistributionUrl=true");
  }

  @Test
  void wrapperJarMatchesThePinnedChecksum() throws IOException {
    Path wrapperJar = RepositoryPaths.root().resolve("gradle/wrapper/gradle-wrapper.jar");
    String recorded = read("gradle/wrapper/gradle-wrapper.jar.sha256").trim();

    assertThat(recorded).isEqualTo(WRAPPER_JAR_SHA256);
    assertThat(sha256(wrapperJar)).isEqualTo(WRAPPER_JAR_SHA256);
  }

  @Test
  void versionCatalogPinsEveryToolVersion() throws IOException {
    String catalog = read("gradle/libs.versions.toml");

    assertThat(catalog).contains("assertj = \"3.27.7\"");
    assertThat(catalog).contains("checkstyle = \"12.3.1\"");
    assertThat(catalog).contains("googleJavaFormat = \"1.24.0\"");
    assertThat(catalog).contains("jackson = \"2.22.1\"");
    assertThat(catalog).contains("jacoco = \"0.8.15\"");
    assertThat(catalog).contains("jsonSchemaValidator = \"3.0.6\"");
    assertThat(catalog).contains("junit = \"5.14.4\"");
    assertThat(catalog).contains("pmd = \"7.26.0\"");
    assertThat(catalog).contains("spotbugsPlugin = \"6.5.10\"");
    assertThat(catalog).contains("spotbugsTool = \"4.10.3\"");
    assertThat(catalog).contains("spotless = \"8.9.0\"");
  }

  @Test
  void javaBaselineIsPinnedToRelease17() throws IOException {
    String conventions = read(JAVA_LIBRARY_CONVENTION);

    assertThat(conventions).contains("JavaLanguageVersion.of(17)");
    assertThat(conventions).contains("options.release.set(17)");
    assertThat(conventions).contains("-Werror");
  }

  @Test
  void formatterRunsOnlyInsideTheQualityConvention() throws IOException {
    String quality = read(QUALITY_CONVENTION);
    String javaLibrary = read(JAVA_LIBRARY_CONVENTION);
    String testConvention = read(TEST_CONVENTION);

    assertThat(quality).contains("id(\"com.diffplug.spotless\")");
    assertThat(quality).contains("googleJavaFormat(");
    assertThat(javaLibrary).doesNotContain("spotless");
    assertThat(testConvention).doesNotContain("spotless");
  }

  @Test
  void toolchainProvisioningIsDisabled() throws IOException {
    String properties = read("gradle.properties");
    String fromEnv = "org.gradle.java.installations.fromEnv=";

    assertThat(properties).contains("org.gradle.java.installations.auto-download=false");
    assertThat(properties).contains(fromEnv + "JAVA_HOME_17_X64,JAVA_HOME_21_X64,JAVA_HOME_25_X64");
  }

  @Test
  void policyProjectLocksItsResolvedClasspaths() throws IOException {
    String lockfile = read("build-tools/harness-policy/gradle.lockfile");

    assertThat(lockfile).contains("com.networknt:json-schema-validator:3.0.6");
    assertThat(lockfile).contains("org.assertj:assertj-core:3.27.7");
    assertThat(lockfile).contains("org.junit.jupiter:junit-jupiter:5.14.4");
    assertThat(lockfile).contains("com.fasterxml.jackson.core:jackson-databind:2.22.1");
  }

  @Test
  void mavenBuildFilesAreAbsent() throws IOException {
    Path root = RepositoryPaths.root();
    List<String> found = new ArrayList<>();

    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            String name = fileNameOf(directory);
            if (!directory.equals(root) && IGNORED_DIRECTORIES.contains(name)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            if (FORBIDDEN_MAVEN_NAMES.contains(name)) {
              found.add(root.relativize(directory).toString());
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (FORBIDDEN_MAVEN_NAMES.contains(fileNameOf(file))) {
              found.add(root.relativize(file).toString());
            }
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(found).isEmpty();
  }

  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }

  private static String sha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    } catch (NoSuchAlgorithmException cause) {
      throw new IllegalStateException("SHA-256 must be available", cause);
    }
  }

  private static String read(String relativePath) throws IOException {
    return Files.readString(RepositoryPaths.root().resolve(relativePath), StandardCharsets.UTF_8);
  }
}
```

- [ ] **Step 2: Run the build contract test and verify it fails**

Run:

```bash
./gradlew :build-tools:harness-policy:test --tests '*BuildContractPolicyTest*'
```

Expected: FAIL. `policyProjectLocksItsResolvedClasspaths` fails with `NoSuchFileException` on `build-tools/harness-policy/gradle.lockfile`; the other seven tests pass.

- [ ] **Step 3: Write the dependency lock state**

Run:

```bash
./gradlew :build-tools:harness-policy:resolveAndLockAll --write-locks
```

Expected: `BUILD SUCCESSFUL`, and `build-tools/harness-policy/gradle.lockfile` now exists listing `compileClasspath`, `runtimeClasspath`, `testCompileClasspath`, and `testRuntimeClasspath` entries.

Verify the generated content:

```bash
grep -c 'com.networknt:json-schema-validator:3.0.6' build-tools/harness-policy/gradle.lockfile
```

Expected: a count of `1` or greater.

- [ ] **Step 4: Run the build contract test and verify it passes**

Run:

```bash
./gradlew :build-tools:harness-policy:test --tests '*BuildContractPolicyTest*'
```

Expected: `BUILD SUCCESSFUL`; eight tests pass.

- [ ] **Step 5: Run the full check**

Run:

```bash
./gradlew spotlessApply
./gradlew check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit the locked build contract**

Run:

```bash
git add build-tools/harness-policy
git commit -m "test: lock and regression-test the Gradle build contract

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: Add YAML-parsed workflow policy and the trusted/fork CI graph

**Files:**
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/WorkflowDocuments.java`
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/WorkflowPolicyTest.java`
- Create: `.github/workflows/ci.yml`
- Create: `.github/dependabot.yml`

**Interfaces:**
- Consumes: `RepositoryPaths.root()`, `libs.jackson.dataformat.yaml`, the root tasks `policyCheck`, `quality`, `testJava17`, `testJava21`, `testJava25`.
- Produces: `WorkflowDocuments` helpers (`files`, `read`, `triggerNames`, `jobNames`, `jobs`, `steps`, `runnerLabels`, `actionReferences`, `permissionValues`); a workflow policy regression that parses every workflow file; a CI graph whose trusted jobs run on `arc-java-build`, whose fork job runs on `ubuntu-latest`, and which fans in to the required `verify-result` job.

- [ ] **Step 1: Write the failing workflow policy helper and test**

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/WorkflowDocuments.java`:

```java
package com.microsoft.agentframework.build.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class WorkflowDocuments {

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  private WorkflowDocuments() {}

  static List<Path> files() throws IOException {
    Path directory = RepositoryPaths.root().resolve(".github/workflows");
    try (Stream<Path> entries = Files.list(directory)) {
      return entries
          .filter(Files::isRegularFile)
          .filter(WorkflowDocuments::isYamlFile)
          .sorted(Comparator.comparing(WorkflowDocuments::fileNameOf))
          .toList();
    }
  }

  static JsonNode read(Path workflow) throws IOException {
    return YAML_MAPPER.readTree(workflow.toFile());
  }

  static List<String> triggerNames(JsonNode workflow) {
    JsonNode triggers = workflow.has("on") ? workflow.get("on") : workflow.path("true");
    List<String> names = new ArrayList<>();
    if (triggers.isObject()) {
      triggers.fieldNames().forEachRemaining(names::add);
    } else if (triggers.isArray()) {
      triggers.forEach(trigger -> names.add(trigger.textValue()));
    } else if (triggers.isTextual()) {
      names.add(triggers.textValue());
    }
    return names;
  }

  static List<String> jobNames(JsonNode workflow) {
    List<String> names = new ArrayList<>();
    workflow.path("jobs").fieldNames().forEachRemaining(names::add);
    return names;
  }

  static List<JsonNode> jobs(JsonNode workflow) {
    List<JsonNode> jobs = new ArrayList<>();
    workflow.path("jobs").forEach(jobs::add);
    return jobs;
  }

  static List<JsonNode> steps(JsonNode job) {
    List<JsonNode> steps = new ArrayList<>();
    job.path("steps").forEach(steps::add);
    return steps;
  }

  static List<String> runnerLabels(JsonNode job) {
    JsonNode runsOn = job.path("runs-on");
    List<String> labels = new ArrayList<>();
    if (runsOn.isTextual()) {
      labels.add(runsOn.textValue());
    } else if (runsOn.isArray()) {
      runsOn.forEach(label -> labels.add(label.textValue()));
    }
    return labels;
  }

  static List<String> actionReferences(JsonNode workflow) {
    List<String> references = new ArrayList<>();
    for (JsonNode job : jobs(workflow)) {
      JsonNode jobUses = job.path("uses");
      if (jobUses.isTextual()) {
        references.add(jobUses.textValue());
      }
      for (JsonNode step : steps(job)) {
        JsonNode stepUses = step.path("uses");
        if (stepUses.isTextual()) {
          references.add(stepUses.textValue());
        }
      }
    }
    return references;
  }

  static List<String> permissionValues(JsonNode workflow) {
    List<String> values = new ArrayList<>();
    collectPermissionValues(workflow.path("permissions"), values);
    for (JsonNode job : jobs(workflow)) {
      collectPermissionValues(job.path("permissions"), values);
    }
    return values;
  }

  private static void collectPermissionValues(JsonNode permissions, List<String> values) {
    if (permissions.isObject()) {
      permissions.forEach(value -> values.add(value.textValue()));
    } else if (permissions.isTextual()) {
      values.add(permissions.textValue());
    }
  }

  private static boolean isYamlFile(Path path) {
    String name = fileNameOf(path);
    return name.endsWith(".yml") || name.endsWith(".yaml");
  }

  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }
}
```

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/WorkflowPolicyTest.java`:

```java
package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WorkflowPolicyTest {

  private static final Pattern PINNED_EXTERNAL_REFERENCE =
      Pattern.compile("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._/-]+)?@[0-9a-f]{40}$");

  private static final Set<String> ALLOWED_RUNNER_LABELS =
      Set.of("arc-java-build", "ubuntu-latest");

  private static final Set<String> ALLOWED_PERMISSION_VALUES = Set.of("read", "none");

  private static final String TRUSTED_CONDITION =
      "github.event_name != 'pull_request' "
          + "|| github.event.pull_request.head.repo.full_name == github.repository";

  private static final String FORK_CONDITION =
      "github.event_name == 'pull_request' "
          + "&& github.event.pull_request.head.repo.full_name != github.repository";

  private static final String CANCEL_EXPRESSION = "${{ github.event_name == 'pull_request' }}";

  private static final String TRUSTED_LABEL = "arc-java-build";

  private static final String HOSTED_LABEL = "ubuntu-latest";

  private static final String RESULT_JOB = "verify-result";

  static Stream<Path> workflows() throws IOException {
    return WorkflowDocuments.files().stream();
  }

  @Test
  void repositoryDefinesAtLeastOneWorkflow() throws IOException {
    assertThat(WorkflowDocuments.files()).isNotEmpty();
  }

  @ParameterizedTest(name = "{0} never uses pull_request_target")
  @MethodSource("workflows")
  void workflowNeverUsesPullRequestTarget(Path workflow) throws IOException {
    List<String> triggers = WorkflowDocuments.triggerNames(WorkflowDocuments.read(workflow));

    assertThat(triggers).doesNotContain("pull_request_target");
  }

  @ParameterizedTest(name = "{0} pins every external reference")
  @MethodSource("workflows")
  void workflowPinsEveryExternalReference(Path workflow) throws IOException {
    List<String> references = WorkflowDocuments.actionReferences(WorkflowDocuments.read(workflow));

    assertThat(references).isNotEmpty();
    for (String reference : references) {
      boolean local = reference.startsWith("./");
      boolean pinned = PINNED_EXTERNAL_REFERENCE.matcher(reference).matches();
      assertThat(local || pinned)
          .as("%s must be a ./ local action or pinned to a full commit SHA", reference)
          .isTrue();
    }
  }

  @ParameterizedTest(name = "{0} uses only allowed runner labels")
  @MethodSource("workflows")
  void workflowUsesOnlyAllowedRunnerLabels(Path workflow) throws IOException {
    List<String> labels = new ArrayList<>();
    for (JsonNode job : WorkflowDocuments.jobs(WorkflowDocuments.read(workflow))) {
      labels.addAll(WorkflowDocuments.runnerLabels(job));
    }

    assertThat(labels).isNotEmpty();
    assertThat(labels).isSubsetOf(ALLOWED_RUNNER_LABELS);
  }

  @ParameterizedTest(name = "{0} declares least-privilege permissions")
  @MethodSource("workflows")
  void workflowDeclaresLeastPrivilegePermissions(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);

    assertThat(document.path("permissions").isObject()).isTrue();
    assertThat(WorkflowDocuments.permissionValues(document)).isSubsetOf(ALLOWED_PERMISSION_VALUES);
  }

  @ParameterizedTest(name = "{0} never persists checkout credentials")
  @MethodSource("workflows")
  void workflowNeverPersistsCheckoutCredentials(Path workflow) throws IOException {
    for (JsonNode job : WorkflowDocuments.jobs(WorkflowDocuments.read(workflow))) {
      for (JsonNode step : WorkflowDocuments.steps(job)) {
        JsonNode uses = step.path("uses");
        if (uses.isTextual() && uses.textValue().startsWith("actions/checkout@")) {
          JsonNode persist = step.path("with").path("persist-credentials");
          assertThat(persist.isBoolean()).isTrue();
          assertThat(persist.booleanValue()).isFalse();
        }
      }
    }
  }

  @ParameterizedTest(name = "{0} cancels only pull request runs")
  @MethodSource("workflows")
  void workflowCancelsOnlyPullRequestRuns(Path workflow) throws IOException {
    JsonNode concurrency = WorkflowDocuments.read(workflow).path("concurrency");

    assertThat(concurrency.isObject()).isTrue();
    assertThat(concurrency.path("cancel-in-progress").textValue()).isEqualTo(CANCEL_EXPRESSION);
  }

  @ParameterizedTest(name = "{0} gates every trusted runner job")
  @MethodSource("workflows")
  void trustedJobsRequireTheSameRepositoryCondition(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);
    List<String> jobNames = WorkflowDocuments.jobNames(document);

    for (String jobName : jobNames) {
      JsonNode job = document.path("jobs").path(jobName);
      if (WorkflowDocuments.runnerLabels(job).contains(TRUSTED_LABEL)) {
        assertThat(job.path("if").textValue())
            .as("%s must only run trusted code", jobName)
            .isEqualTo(TRUSTED_CONDITION);
      }
    }
  }

  @ParameterizedTest(name = "{0} isolates fork verification on hosted runners")
  @MethodSource("workflows")
  void forkVerificationRunsOnHostedRunnersOnly(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);
    assumeTrue(WorkflowDocuments.triggerNames(document).contains("pull_request"));

    List<String> forkJobs = new ArrayList<>();
    for (String jobName : WorkflowDocuments.jobNames(document)) {
      JsonNode job = document.path("jobs").path(jobName);
      if (FORK_CONDITION.equals(job.path("if").textValue())) {
        forkJobs.add(jobName);
        assertThat(WorkflowDocuments.runnerLabels(job)).containsExactly(HOSTED_LABEL);
      }
    }

    assertThat(forkJobs).isNotEmpty();
  }

  @ParameterizedTest(name = "{0} fans in to the required result job")
  @MethodSource("workflows")
  void pullRequestWorkflowFansInToTheRequiredResultJob(Path workflow) throws IOException {
    JsonNode document = WorkflowDocuments.read(workflow);
    assumeTrue(WorkflowDocuments.triggerNames(document).contains("pull_request"));

    List<String> jobNames = new ArrayList<>(WorkflowDocuments.jobNames(document));
    assertThat(jobNames).contains(RESULT_JOB);

    JsonNode resultJob = document.path("jobs").path(RESULT_JOB);
    assertThat(resultJob.path("if").textValue()).isEqualTo("always()");

    List<String> needs = new ArrayList<>();
    resultJob.path("needs").forEach(need -> needs.add(need.textValue()));
    jobNames.remove(RESULT_JOB);

    assertThat(needs).containsExactlyInAnyOrderElementsOf(jobNames);
  }

  @Test
  void dependencyUpdatesTrackGradleAndActions() throws IOException {
    String text =
        Files.readString(
            RepositoryPaths.root().resolve(".github/dependabot.yml"), StandardCharsets.UTF_8);

    assertThat(text).contains("package-ecosystem: \"gradle\"");
    assertThat(text).contains("directory: \"/build-logic\"");
    assertThat(text).contains("package-ecosystem: \"github-actions\"");
  }
}
```

- [ ] **Step 2: Run the workflow policy test and verify it fails**

Run:

```bash
./gradlew :build-tools:harness-policy:test --tests '*WorkflowPolicyTest*'
```

Expected: FAIL. `repositoryDefinesAtLeastOneWorkflow` and every parameterized source fail with `NoSuchFileException` on `.github/workflows`, and `dependencyUpdatesTrackGradleAndActions` fails on `.github/dependabot.yml`.

- [ ] **Step 3: Create the CI workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  pull_request:
  push:
    branches:
      - main
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: ci-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  fork-verify:
    name: Fork minimum verification
    if: github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name != github.repository
    runs-on: ubuntu-latest
    timeout-minutes: 30
    permissions:
      contents: read
    steps:
      - name: Check out source
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false

      - name: Install Eclipse Temurin 17
        uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@67621b124fd2e251c5e8a0e6e3b91318f2287669 # v5.1.0
        with:
          validate-wrappers: true
          cache-read-only: true

      - name: Run policy, quality, and Java 17 tests
        run: ./gradlew --no-daemon policyCheck quality testJava17

      - name: Upload verification reports
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: fork-verification-reports
          path: |
            **/build/reports/
            **/build/test-results/
          if-no-files-found: ignore
          retention-days: 7

  trusted-quality:
    name: Trusted quality on JDK 17
    if: github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository
    runs-on: arc-java-build
    timeout-minutes: 30
    permissions:
      contents: read
    steps:
      - name: Check out source
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false

      - name: Select preinstalled Eclipse Temurin toolchains
        uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
        with:
          distribution: temurin
          java-version: |
            17
            21
            25

      - name: Record runner tool cache provenance
        run: |
          set -eu
          printf '### Runner tool cache provenance\n\n' >> "$GITHUB_STEP_SUMMARY"
          for version in 17 21 25; do
            home_variable="JAVA_HOME_${version}_X64"
            home_path=$(printenv "$home_variable")
            case "$home_path" in
              /opt/hostedtoolcache/*) provenance=tool-cache ;;
              *) provenance=downloaded ;;
            esac
            printf -- '- %s=%s (%s)\n' "$home_variable" "$home_path" "$provenance" \
              >> "$GITHUB_STEP_SUMMARY"
            printf '%s=%s (%s)\n' "$home_variable" "$home_path" "$provenance"
          done

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@67621b124fd2e251c5e8a0e6e3b91318f2287669 # v5.1.0
        with:
          validate-wrappers: true

      - name: Run repository policy and quality
        env:
          JAVA_HOME: ${{ env.JAVA_HOME_17_X64 }}
        run: ./gradlew --no-daemon policyCheck quality

      - name: Upload verification reports
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: trusted-quality-reports
          path: |
            **/build/reports/
            **/build/test-results/
          if-no-files-found: ignore
          retention-days: 7

  trusted-compatibility:
    name: Trusted tests on JDK ${{ matrix.java }}
    if: github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository
    runs-on: arc-java-build
    timeout-minutes: 30
    permissions:
      contents: read
    strategy:
      fail-fast: false
      matrix:
        java: ['17', '21', '25']
    steps:
      - name: Check out source
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false

      - name: Select preinstalled Eclipse Temurin toolchains
        uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
        with:
          distribution: temurin
          java-version: |
            17
            21
            25

      - name: Record runner tool cache provenance
        run: |
          set -eu
          printf '### Runner tool cache provenance\n\n' >> "$GITHUB_STEP_SUMMARY"
          for version in 17 21 25; do
            home_variable="JAVA_HOME_${version}_X64"
            home_path=$(printenv "$home_variable")
            case "$home_path" in
              /opt/hostedtoolcache/*) provenance=tool-cache ;;
              *) provenance=downloaded ;;
            esac
            printf -- '- %s=%s (%s)\n' "$home_variable" "$home_path" "$provenance" \
              >> "$GITHUB_STEP_SUMMARY"
            printf '%s=%s (%s)\n' "$home_variable" "$home_path" "$provenance"
          done

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@67621b124fd2e251c5e8a0e6e3b91318f2287669 # v5.1.0
        with:
          validate-wrappers: true

      - name: Run Java ${{ matrix.java }} compatibility tests
        env:
          JAVA_HOME: ${{ env.JAVA_HOME_17_X64 }}
        run: ./gradlew --no-daemon testJava${{ matrix.java }}

      - name: Upload verification reports
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: trusted-compatibility-reports-java-${{ matrix.java }}
          path: |
            **/build/reports/
            **/build/test-results/
          if-no-files-found: ignore
          retention-days: 7

  verify-result:
    name: Verify result
    if: always()
    needs:
      - fork-verify
      - trusted-quality
      - trusted-compatibility
    runs-on: ubuntu-latest
    timeout-minutes: 5
    permissions:
      contents: read
    steps:
      - name: Require one complete verification path
        env:
          FORK_RESULT: ${{ needs.fork-verify.result }}
          TRUSTED_QUALITY_RESULT: ${{ needs.trusted-quality.result }}
          TRUSTED_COMPATIBILITY_RESULT: ${{ needs.trusted-compatibility.result }}
        run: |
          set -eu
          printf 'fork-verify=%s\n' "$FORK_RESULT"
          printf 'trusted-quality=%s\n' "$TRUSTED_QUALITY_RESULT"
          printf 'trusted-compatibility=%s\n' "$TRUSTED_COMPATIBILITY_RESULT"
          if [ "$TRUSTED_QUALITY_RESULT" = "success" ] \
            && [ "$TRUSTED_COMPATIBILITY_RESULT" = "success" ] \
            && [ "$FORK_RESULT" = "skipped" ]; then
            printf 'Trusted verification path completed.\n'
            exit 0
          fi
          if [ "$FORK_RESULT" = "success" ] \
            && [ "$TRUSTED_QUALITY_RESULT" = "skipped" ] \
            && [ "$TRUSTED_COMPATIBILITY_RESULT" = "skipped" ]; then
            printf 'Fork minimum verification path completed.\n'
            exit 0
          fi
          printf 'No verification path completed successfully.\n' >&2
          exit 1
```

- [ ] **Step 4: Create the dependency update policy**

Create `.github/dependabot.yml`:

```yaml
version: 2
updates:
  - package-ecosystem: "gradle"
    directory: "/"
    schedule:
      interval: "weekly"
      day: "monday"
      time: "02:00"
      timezone: "Asia/Seoul"
    open-pull-requests-limit: 5
    groups:
      quality-tools:
        patterns:
          - "com.diffplug.spotless:*"
          - "com.github.spotbugs:*"
          - "com.github.spotbugs.snom:*"
          - "com.google.googlejavaformat:*"
          - "com.puppycrawl.tools:*"
          - "net.sourceforge.pmd:*"
          - "org.jacoco:*"
      test-tools:
        patterns:
          - "org.assertj:*"
          - "org.junit:*"
          - "org.junit.jupiter:*"
          - "org.junit.platform:*"
      serialization-tools:
        patterns:
          - "com.fasterxml.jackson.core:*"
          - "com.fasterxml.jackson.dataformat:*"
          - "com.networknt:*"

  - package-ecosystem: "gradle"
    directory: "/build-logic"
    schedule:
      interval: "weekly"
      day: "monday"
      time: "02:15"
      timezone: "Asia/Seoul"
    open-pull-requests-limit: 5

  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "weekly"
      day: "monday"
      time: "02:30"
      timezone: "Asia/Seoul"
    open-pull-requests-limit: 5
```

- [ ] **Step 5: Run the workflow policy test and verify it passes**

Run:

```bash
./gradlew spotlessApply
./gradlew :build-tools:harness-policy:test --tests '*WorkflowPolicyTest*'
```

Expected: `BUILD SUCCESSFUL`; both plain tests and all nine parameterized cases for `ci.yml` pass.

- [ ] **Step 6: Run the full check**

Run:

```bash
./gradlew check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit the policy-tested CI graph**

Run:

```bash
git add build-tools/harness-policy .github/workflows/ci.yml .github/dependabot.yml
git commit -m "ci: verify the Gradle harness on arc-java-build with a fork fan-in gate

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: Document the runner contract and complete foundation verification

**Files:**
- Create: `docs/operations/github-actions-runner-contract.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: the CI graph from Task 6 and the `arc-java-build` scale-set contract owned by the platform plan.
- Produces: the application-side runner contract document and README entry points; a fully green `./gradlew clean check`.

- [ ] **Step 1: Document the application and platform runner contract**

Create `docs/operations/github-actions-runner-contract.md`:

````markdown
# GitHub Actions Runner Contract

## Application repository contract

Trusted Java verification runs on `runs-on: arc-java-build`. Fork pull requests run the minimum
verification on `runs-on: ubuntu-latest`. No other runner label is permitted.

Jobs on `arc-java-build` must not assume Docker, privileged mode, Azure credentials, Kubernetes API
access, internal service access, persistent workspace storage, or a pre-warmed runner. They may
assume:

- Eclipse Temurin 17, 21, and 25 in the GitHub tool cache under `/opt/hostedtoolcache`;
- `JAVA_HOME_17_X64`, `JAVA_HOME_21_X64`, and `JAVA_HOME_25_X64`;
- outbound HTTPS to GitHub Actions, the GitHub API, Maven Central, the Gradle Plugin Portal, the
  Gradle distribution service, and the approved container registry;
- a non-root `runner` user and `/home/runner/run.sh`.

The repository owns workflow triggers, pinned action SHAs, token permissions, Gradle commands,
caches, artifacts, and timeouts. Gradle itself is owned by the committed wrapper, never by the image.

## Platform contract

The platform repository `agent-framework-java-platform` owns the runner image, the Azure Container
Registry repository, the `arc-java-build` Helm release, namespace `arc-runners-java`, the service
account, Pod Security Admission labels, the Cilium network policy, and log export.

`arc-java-build` is expected to:

- live in namespace `arc-runners-java`;
- be ephemeral and non-privileged with `allowPrivilegeEscalation: false` and all capabilities dropped;
- use a dedicated service account with no application permissions;
- scale from `minRunners: 0` to a finite `maxRunners`;
- reference the runner image by immutable digest;
- never enable Docker-in-Docker or Kubernetes container mode.

The existing general-purpose `aks-runners` scale set is not modified by this repository or by the
`arc-java-build` rollout.

## Tool cache regression

`actions/setup-java` must select the preinstalled toolchains without downloading. The trusted CI jobs
record each `JAVA_HOME_*_X64` value and whether it came from the tool cache in the job summary, so a
regression is visible without failing unrelated work. The hard block lives in the image tests: the
in-image verifier that runs during the registry build, and the manual `runner-smoke.yml` workflow,
which fails when any toolchain is not served from
`/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/`.

## Trust boundary

Fork pull requests never reach `arc-java-build`. The trusted and fork jobs use mutually exclusive
conditions, and the required `verify-result` job fails unless exactly one complete path succeeded, so
a skipped job can never be reported as green.

Never combine `pull_request_target`, checkout of pull-request head code, and execution of repository
scripts. Docker or Testcontainers work requires a separately reviewed scale set, namespace, runner
group, and network policy; this repository does not create one.
````

- [ ] **Step 2: Link the new entry points from the README**

Add this section to `README.md` immediately after the `## 문서` list:

````markdown
## 기여와 하네스

- [저장소 작업 지침](AGENTS.md)
- [기여 가이드](CONTRIBUTING.md)
- [보안 정책](SECURITY.md)
- [GitHub Actions runner 계약](docs/operations/github-actions-runner-contract.md)

모든 로컬·CI 검증은 저장소에 포함된 Gradle Wrapper를 사용합니다.

```bash
./gradlew check
```
````

- [ ] **Step 3: Run the complete foundation verification**

Run:

```bash
./gradlew spotlessApply
./gradlew clean check
```

Expected: `BUILD SUCCESSFUL`. The build runs `:build-logic:test` (5 TestKit tests),
`:build-tools:harness-policy:test` (governance, build contract, artifact contract, and workflow
policy tests), `:build-tools:harness-policy:quality`, and `testJava17`, `testJava21`, `testJava25`.

- [ ] **Step 4: Verify the individual entry points named in AGENTS.md**

Run:

```bash
./gradlew policyCheck
./gradlew quality
./gradlew testJava17 testJava21 testJava25
```

Expected: all three commands report `BUILD SUCCESSFUL`, proving the documented commands exist and are
not CI-only.

- [ ] **Step 5: Verify no Maven artifact was reintroduced**

Run:

```bash
git ls-files -- pom.xml mvnw mvnw.cmd .mvn '*/pom.xml' '**/pom.xml'
```

Expected: no output.

- [ ] **Step 6: Commit the runner contract and README entry points**

Run:

```bash
git add README.md docs/operations
git commit -m "docs: define the arc-java-build runner contract

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Post-Foundation Plans

After this plan is implemented and reviewed, execute these independent plans:

1. `2026-08-10-java-arc-platform.md`
   - Java runner image, image verification, Azure Container Registry, `arc-java-build` Helm release,
     namespace, Cilium egress policy, deploy/verify/rollback scripts, and the smoke workflow that
     proves the tool cache is used.
2. `2026-08-10-maf-conformance-harness.md`
   - upstream compatibility matrix and provenance, abstract Java conformance fixture API,
     deterministic response/tool/session/streaming/telemetry vectors, and the upstream delta workflow.
3. `2026-08-10-agent-harness-regression.md`
   - artifact store, deterministic repository fixtures, graders and scorecards, and
     old/new instruction differential runs.
4. `2026-08-10-release-security-harness.md`
   - Revapi after the first published API baseline, CycloneDX SBOM, CodeQL, dependency review,
     Scorecard, reproducible artifact comparison, signing, OIDC, and attestations.

## Plan Verification Checklist

- Every requirement in `docs/superpowers/specs/2026-08-10-gradle-kotlin-arc-foundation-design.md`
  sections 3, 4, 7, 8, 9, and 11 that belongs to the application repository is implemented by
  Tasks 1-7; sections 5, 6, and 10's platform half is assigned to `2026-08-10-java-arc-platform.md`.
- Maven is removed and its reintroduction is blocked by `BuildContractPolicyTest`.
- Gradle 9.7.0, the distribution SHA-256, and the wrapper JAR SHA-256 are pinned and regression-tested.
- All build scripts are Kotlin DSL; shared behavior lives in the `build-logic` included build.
- Java toolchain 17 and `options.release = 17` are enforced by the convention plugin and tested by
  both Gradle TestKit and `BuildContractPolicyTest`.
- google-java-format 1.24.0 runs only in `agentframework.quality-conventions`, verified by TestKit
  and by `formatterRunsOnlyInsideTheQualityConvention`.
- Eight artifact contracts exist with real JSON Schema 2020-12 validation, closed-object checks, and a
  negative test that proves `additionalProperties: false` is enforced.
- Workflow policy parses every workflow file and covers `pull_request_target`, SHA pinning, local
  `./` actions, runner labels, permissions, credential persistence, pull-request-only cancellation,
  trusted/fork exclusivity, and required-result fan-in.
- Every policy runs through `./gradlew check`; no standalone shell gate exists.
- Trusted CI records tool-cache provenance without failing; the hard tool-cache block lives in the
  runner image tests owned by `2026-08-10-java-arc-platform.md`, matching the design's error contract.
- No task creates product modules, provider adapters, Azure resources, Kubernetes resources, or
  release automation.
- No step contains TBD, TODO, placeholder text, an undefined type, or an unnamed file.

---
