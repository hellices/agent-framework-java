# Module Composition Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the Gradle harness on `main` and compose the empty product module
skeleton — `agent-framework-api`, `agent-framework-engine`, `agent-framework-testkit`,
`agent-framework-bom` — so that every later behavior change is governed by executable
policy instead of review convention.

**Architecture:** The repository already owns an executable harness: three Gradle
convention plugins, five aggregate verification tasks, four policy test classes, and
eight JSON Schema agent artifact contracts. This plan adds no agent behavior. It
registers product projects that apply the existing conventions, and it extends the
existing policy suite so module identity, dependency direction, and artifact
coordinates become build failures rather than review comments.

**Tech Stack:** Gradle 9.7.0 Kotlin DSL, Java 17 baseline with Temurin 17/21/25
toolchains, JUnit 5.14.4, AssertJ 3.27.7, Jackson 2.22.1, networknt
json-schema-validator 3.0.6, Spotless with google-java-format, Checkstyle, PMD,
SpotBugs, JaCoCo, GitHub Actions on the `arc-java-build` ARC scale set.

## Global Constraints

- Build tool is Gradle Kotlin DSL. Maven build files must never be reintroduced;
  `BuildContractPolicyTest.mavenBuildFilesAreAbsent` fails the build if they are.
- Java baseline is `options.release = 17`; compatibility tests run on 17, 21, and 25.
- Every product project applies exactly these plugin ids:
  `agentframework.java-library-conventions`, `agentframework.test-conventions`,
  `agentframework.quality-conventions`.
- Toolchain auto-provisioning stays disabled. Local runs need Temurin 17, 21, and 25
  installed, or must be limited to `testJava17`.
- Every resolvable classpath is dependency-locked. A new project without a committed
  `gradle.lockfile` fails resolution.
- Dependency versions come only from `gradle/libs.versions.toml`. Inline versions are
  rejected by `BuildContractPolicyTest.versionCatalogPinsEveryToolVersion`.
- Local and CI verification use the same tasks: `./gradlew policyCheck`,
  `./gradlew quality`, `./gradlew testJava17 testJava21 testJava25`, `./gradlew check`.
- CI-only quality logic is prohibited.
- Trusted CI runs on `runs-on: arc-java-build`; fork pull requests run on
  `runs-on: ubuntu-latest`. No other runner label is permitted.
- Product package root is `com.microsoft.agentframework`. Harness build code stays in
  `com.microsoft.agentframework.build.harness`.
- Group id is `com.microsoft.agentframework`; every published artifact carries a single
  repository-wide version.
- No task in this plan implements agent, session, tool, or workflow behavior.

---

## Source of Truth

Behavior for later plans comes from the pinned upstream snapshot
`d0a4165f170193ba1d026a259af40d35bb7eaefe`:

- `docs/upstream/snapshots/d0a4165f/README.md` — feature document index.
- `docs/upstream/snapshots/d0a4165f/compatibility-matrix.md` — 71 rows mapping each
  feature to `Java 소유 모듈` and `목표 release phase`.
- `docs/upstream/snapshots/d0a4165f/coverage-ledger.md` — source coverage proof.

Architecture ownership comes from
`docs/superpowers/specs/2026-08-10-agent-framework-java-foundation-design.md`.

## File Structure

| Path | Responsibility |
| --- | --- |
| `settings.gradle.kts` | Registers every Gradle project. Single source of module identity. |
| `agent-framework-api/build.gradle.kts` | Public contracts. No dependencies outside the JDK. |
| `agent-framework-api/src/main/java/com/microsoft/agentframework/api/package-info.java` | Declares the public API package. |
| `agent-framework-engine/build.gradle.kts` | Execution state machine. Depends only on `agent-framework-api`. |
| `agent-framework-engine/src/main/java/com/microsoft/agentframework/engine/package-info.java` | Declares the engine package. |
| `agent-framework-testkit/build.gradle.kts` | Deterministic fixtures and contract-test bases for other projects. |
| `agent-framework-testkit/src/main/java/com/microsoft/agentframework/testkit/package-info.java` | Declares the testkit package. |
| `agent-framework-bom/build.gradle.kts` | `java-platform` listing every published artifact at one version. |
| `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ModuleCompositionPolicyTest.java` | Enforces registration, conventions, locking, and coordinates. |
| `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DependencyDirectionPolicyTest.java` | Enforces the allowed dependency graph. |
| `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ProjectLayout.java` | Shared reader for `settings.gradle.kts` and project build files. |
| `docs/operations/module-composition.md` | Human-readable module contract that the policy tests enforce. |

Each new product project starts with a package declaration and no behavior. Behavior
arrives in later plans, one feature document at a time.

---

### Task 1: Land the harness on `main`

The `gradle-arc-foundation` branch holds the entire harness. Its own merge gate in
`docs/operations/github-actions-runner-contract.md` forbids merging until a trusted
`ci.yml` run completes on `arc-java-build`. The runner smoke workflow already passed;
the CI run has not happened because `ci.yml` never ran outside a pull request.

**Files:**
- Modify: none in this task. This task only opens, verifies, and merges a pull request.

**Interfaces:**
- Consumes: branch `gradle-arc-foundation` at its pushed head.
- Produces: `main` containing `settings.gradle.kts`, `build-logic/`,
  `build-tools/harness-policy/`, `config/`, `gradle/libs.versions.toml`, `gradlew`,
  `AGENTS.md`, `CONTRIBUTING.md`, `SECURITY.md`, `.harness/schemas/`, and
  `.github/workflows/ci.yml`.

- [ ] **Step 1: Confirm the branch is still ahead of `main` and unmerged**

```bash
git fetch origin
git rev-list --left-right --count origin/main...origin/gradle-arc-foundation
```

Expected: a non-zero right-hand count, meaning the branch still carries unmerged work.

- [ ] **Step 2: Open the pull request that triggers trusted CI**

```bash
gh pr create \
  --base main \
  --head gradle-arc-foundation \
  --title "build: land the Gradle engineering harness" \
  --body "Lands the Gradle Kotlin DSL harness, convention plugins, policy regression, agent artifact contracts, and arc-java-build CI. Merge gate: this PR must show a green trusted ci.yml run on arc-java-build."
```

Expected: a pull request URL. A same-repository pull request is trusted, so
`trusted-quality` and `trusted-compatibility` schedule on `arc-java-build` and
`fork-verify` is skipped.

- [ ] **Step 3: Watch the trusted run to completion**

```bash
gh pr checks --watch
```

Expected: `Trusted quality on JDK 17`, `Trusted tests on JDK 17`, `Trusted tests on JDK 21`,
`Trusted tests on JDK 25`, and `Verify result` all report success.

- [ ] **Step 4: Record that the merge gate is satisfied**

Modify `docs/operations/github-actions-runner-contract.md` on the branch, replacing the
merge-gate section body with the satisfied state. Keep the heading text unchanged so
`RepositoryGovernancePolicyTest` still finds it.

```markdown
## Merge gate: satisfied by a trusted `arc-java-build` run

`arc-java-build` is live and this repository completed a trusted `ci.yml` run on it.
The merge order below was followed in full and is retained as the standing rule for any
future change to the trusted runner label.

1. `agent-framework-java-platform` deploys `arc-java-build` and its smoke workflow passes.
2. A trusted run of this repository's `ci.yml` completes on `arc-java-build`.
3. Only then is the harness merged and `verify-result` made a required status check.

The workflow must never be softened to `ubuntu-latest` for a trusted job. Doing that
would delete the only executable evidence that the runner contract below is honoured.
```

- [ ] **Step 5: Verify policy still passes with the edited document**

```bash
./gradlew --no-daemon policyCheck
```

Expected: `BUILD SUCCESSFUL`. If `RepositoryGovernancePolicyTest` fails, restore the exact
heading and section names it asserts, then re-run.

- [ ] **Step 6: Commit and push the gate record**

```bash
git add docs/operations/github-actions-runner-contract.md
git commit -m "docs: record the satisfied arc-java-build merge gate"
git push origin gradle-arc-foundation
```

- [ ] **Step 7: Merge after the re-run is green**

```bash
gh pr checks --watch
gh pr merge --merge --delete-branch=false
```

Expected: the pull request merges. Do not squash: the branch history carries the
policy-by-policy rationale that `docs/operations/` references.

- [ ] **Step 8: Verify `main` builds end to end**

```bash
git checkout main
git pull --ff-only origin main
./gradlew --no-daemon check
```

Expected: `BUILD SUCCESSFUL`. On a machine without Temurin 21 or 25, expect
`testJava21`/`testJava25` to fail with `Cannot find a Java installation`; that is an
environment gap, not a repository defect. Install the missing toolchains or verify with
`./gradlew --no-daemon policyCheck quality testJava17` locally and rely on CI for the rest.

- [ ] **Step 9: Make `Verify result` a required status check**

In the repository settings, add a branch protection rule for `main` requiring the
`Verify result` check. Confirm it with:

```bash
gh api repos/open-play-ground/agent-framework-java/branches/main/protection \
  --jq '.required_status_checks.checks[].context'
```

Expected output includes `Verify result`.

---

### Task 2: Publish the module contract document

The policy tests in Tasks 3 and 4 assert against a written contract. Write it first so
the tests quote a real document instead of inventing rules.

**Files:**
- Create: `docs/operations/module-composition.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: `main` from Task 1.
- Produces: the canonical module table, the allowed dependency edges, and the artifact
  coordinate rule that `ModuleCompositionPolicyTest` and `DependencyDirectionPolicyTest`
  enforce.

- [ ] **Step 1: Write the module contract**

Create `docs/operations/module-composition.md`:

```markdown
# Module composition contract

This repository is a Gradle multi-project monorepo. Module identity, dependency
direction, and artifact coordinates are enforced by
`build-tools/harness-policy`. Update this document and its policy tests together.

## Product projects

| Gradle path | Artifact | Responsibility | Allowed compile dependencies |
| --- | --- | --- | --- |
| `:agent-framework-api` | `agent-framework-api` | Public contracts and value types. | none |
| `:agent-framework-engine` | `agent-framework-engine` | Execution state machine. | `:agent-framework-api` |
| `:agent-framework-testkit` | `agent-framework-testkit` | Deterministic fixtures and contract-test bases. | `:agent-framework-api` |
| `:agent-framework-bom` | `agent-framework-bom` | `java-platform` pinning every published artifact. | none |

## Harness projects

| Gradle path | Published | Responsibility |
| --- | --- | --- |
| `:build-tools:harness-policy` | no | Executable repository, artifact, and workflow policy. |

## Rules

1. Every product project applies `agentframework.java-library-conventions`,
   `agentframework.test-conventions`, and `agentframework.quality-conventions`.
2. `:agent-framework-api` declares no project or external compile dependency.
3. `:agent-framework-engine` depends on `:agent-framework-api` only.
4. `:agent-framework-testkit` depends on `:agent-framework-api` only.
5. No project depends on `:agent-framework-bom`, and the BOM depends on no project.
6. No product project depends on `:build-tools:harness-policy`.
7. Every project registered in `settings.gradle.kts` exists on disk with a build file.
8. Every project with a resolvable classpath commits a `gradle.lockfile`.
9. Group is `com.microsoft.agentframework` and the version is repository-wide.
10. Java packages start with `com.microsoft.agentframework`; harness build code uses
    `com.microsoft.agentframework.build.harness`.

## Adding a project

1. Add the row to the table above.
2. Add the assertion to `ModuleCompositionPolicyTest` or
   `DependencyDirectionPolicyTest` and watch it fail.
3. Register the project in `settings.gradle.kts` and add its build file.
4. Run `./gradlew :<project>:resolveAndLockAll --write-locks` and commit the lockfile.
5. Run `./gradlew policyCheck quality testJava17`.
```

- [ ] **Step 2: Link the document from the README harness section**

`RepositoryGovernancePolicyTest.readmeLinksEveryHarnessEntryPoint` requires the README to
link every harness entry point. Add the new line to the `기여와 하네스` list in `README.md`,
directly after the runner contract line:

```markdown
- [모듈 구성 계약](docs/operations/module-composition.md)
```

- [ ] **Step 3: Verify policy and links**

```bash
./gradlew --no-daemon policyCheck
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add docs/operations/module-composition.md README.md
git commit -m "docs: define the module composition contract"
```

---

### Task 3: Enforce module registration, then add `agent-framework-api`

**Files:**
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ProjectLayout.java`
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ModuleCompositionPolicyTest.java`
- Create: `agent-framework-api/build.gradle.kts`
- Create: `agent-framework-api/src/main/java/com/microsoft/agentframework/api/package-info.java`
- Create: `agent-framework-api/src/test/java/com/microsoft/agentframework/api/ApiPackageTest.java`
- Create: `agent-framework-api/gradle.lockfile`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `RepositoryPaths.root()` from
  `build-tools/harness-policy/src/main/java/com/microsoft/agentframework/build/harness/RepositoryPaths.java`,
  which returns the closest ancestor `Path` holding both `settings.gradle.kts` and
  `gradle/libs.versions.toml`.
- Produces: `ProjectLayout.includedProjects()` returning `List<String>` of Gradle paths
  such as `:agent-framework-api`; `ProjectLayout.buildFileText(String gradlePath)`
  returning the build file contents; the `:agent-framework-api` project.

- [ ] **Step 1: Write the shared layout reader**

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ProjectLayout.java`:

```java
package com.microsoft.agentframework.build.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads Gradle project registration and build files for policy tests. */
final class ProjectLayout {

  private static final Pattern INCLUDE =
      Pattern.compile("^\\s*include\\(\"(:[A-Za-z0-9:_-]+)\"\\)\\s*$", Pattern.MULTILINE);

  private ProjectLayout() {}

  static List<String> includedProjects() {
    String settings = read(RepositoryPaths.root().resolve("settings.gradle.kts"));
    Matcher matcher = INCLUDE.matcher(settings);
    List<String> projects = new ArrayList<>();
    while (matcher.find()) {
      projects.add(matcher.group(1));
    }
    return List.copyOf(projects);
  }

  static Path projectDirectory(String gradlePath) {
    String relative = gradlePath.substring(1).replace(':', '/');
    return RepositoryPaths.root().resolve(relative);
  }

  static Path buildFile(String gradlePath) {
    return projectDirectory(gradlePath).resolve("build.gradle.kts");
  }

  static String buildFileText(String gradlePath) {
    return read(buildFile(gradlePath));
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException cause) {
      throw new UncheckedIOException("Cannot read " + path, cause);
    }
  }
}
```

- [ ] **Step 2: Write the failing composition policy test**

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ModuleCompositionPolicyTest.java`:

```java
package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Proves every registered project matches the module composition contract. */
class ModuleCompositionPolicyTest {

  private static final List<String> PRODUCT_PROJECTS = List.of(":agent-framework-api");

  private static final List<String> REQUIRED_PLUGINS =
      List.of(
          "agentframework.java-library-conventions",
          "agentframework.test-conventions",
          "agentframework.quality-conventions");

  static List<String> productProjects() {
    return PRODUCT_PROJECTS;
  }

  @Test
  void settingsRegistersEveryProductProject() {
    assertThat(ProjectLayout.includedProjects()).containsAll(PRODUCT_PROJECTS);
  }

  @ParameterizedTest
  @MethodSource("productProjects")
  void productProjectHasABuildFile(String gradlePath) {
    assertThat(ProjectLayout.buildFile(gradlePath)).exists();
  }

  @ParameterizedTest
  @MethodSource("productProjects")
  void productProjectAppliesEveryConvention(String gradlePath) {
    String buildFile = ProjectLayout.buildFileText(gradlePath);
    for (String plugin : REQUIRED_PLUGINS) {
      assertThat(buildFile).contains("id(\"" + plugin + "\")");
    }
  }

  @ParameterizedTest
  @MethodSource("productProjects")
  void productProjectCommitsItsDependencyLock(String gradlePath) {
    assertThat(ProjectLayout.projectDirectory(gradlePath).resolve("gradle.lockfile")).exists();
  }

  @ParameterizedTest
  @MethodSource("productProjects")
  void productProjectDeclaresNoInlineDependencyVersion(String gradlePath) {
    assertThat(ProjectLayout.buildFileText(gradlePath)).doesNotContain("version = \"");
  }

  @Test
  void documentedProjectsMatchRegisteredProjects() throws IOException {
    Path contract = RepositoryPaths.root().resolve("docs/operations/module-composition.md");
    String text = Files.readString(contract, StandardCharsets.UTF_8);
    for (String gradlePath : PRODUCT_PROJECTS) {
      assertThat(text).contains("`" + gradlePath + "`");
    }
  }
}
```

- [ ] **Step 3: Run the test and watch it fail**

```bash
./gradlew --no-daemon :build-tools:harness-policy:test --tests '*ModuleCompositionPolicyTest*'
```

Expected: FAIL. `settingsRegistersEveryProductProject` reports that
`[":build-tools:harness-policy"]` does not contain `":agent-framework-api"`.

- [ ] **Step 4: Register the project**

Modify `settings.gradle.kts`, replacing the single include line with:

```kotlin
include(":agent-framework-api")
include(":build-tools:harness-policy")
```

- [ ] **Step 5: Add the API build file**

Create `agent-framework-api/build.gradle.kts`:

```kotlin
plugins {
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
}

description = "Public contracts and value types for Agent Framework for Java."
```

- [ ] **Step 6: Add the package declaration**

Create `agent-framework-api/src/main/java/com/microsoft/agentframework/api/package-info.java`:

```java
/**
 * Public contracts for Agent Framework for Java.
 *
 * <p>Types in this package are host-neutral and must not depend on an application framework.
 */
package com.microsoft.agentframework.api;
```

- [ ] **Step 7: Add a test that proves the package is compiled and loadable**

Create `agent-framework-api/src/test/java/com/microsoft/agentframework/api/ApiPackageTest.java`:

```java
package com.microsoft.agentframework.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiPackageTest {

  @Test
  void packageIsPresentOnTheTestRuntimeClasspath() {
    assertThat(getClass().getPackage().getName()).isEqualTo("com.microsoft.agentframework.api");
  }
}
```

- [ ] **Step 8: Write the dependency lock**

```bash
./gradlew --no-daemon :agent-framework-api:resolveAndLockAll --write-locks
```

Expected: `agent-framework-api/gradle.lockfile` is created and lists the JUnit, AssertJ,
Checkstyle, PMD, SpotBugs, and JaCoCo classpaths.

- [ ] **Step 9: Run the policy test and the new project**

```bash
./gradlew --no-daemon policyCheck quality testJava17
```

Expected: `BUILD SUCCESSFUL`, with `ModuleCompositionPolicyTest` passing and
`:agent-framework-api:testJava17` executing `ApiPackageTest`.

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts agent-framework-api build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ProjectLayout.java build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ModuleCompositionPolicyTest.java
git commit -m "build: compose the agent-framework-api project under policy"
```

---

### Task 4: Enforce dependency direction, then add `agent-framework-engine`

**Files:**
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DependencyDirectionPolicyTest.java`
- Create: `agent-framework-engine/build.gradle.kts`
- Create: `agent-framework-engine/src/main/java/com/microsoft/agentframework/engine/package-info.java`
- Create: `agent-framework-engine/src/test/java/com/microsoft/agentframework/engine/EngineDependencyTest.java`
- Create: `agent-framework-engine/gradle.lockfile`
- Modify: `settings.gradle.kts`
- Modify: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ModuleCompositionPolicyTest.java:16`

**Interfaces:**
- Consumes: `ProjectLayout.includedProjects()`, `ProjectLayout.buildFileText(String)`.
- Produces: `:agent-framework-engine`, and the standing rule that `:agent-framework-api`
  declares no project dependency.

- [ ] **Step 1: Write the failing dependency direction test**

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DependencyDirectionPolicyTest.java`:

```java
package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Proves project dependencies only point in the documented direction. */
class DependencyDirectionPolicyTest {

  private static final Pattern PROJECT_DEPENDENCY =
      Pattern.compile("project\\(\"(:[A-Za-z0-9:_-]+)\"\\)");

  private static final Map<String, List<String>> ALLOWED_DEPENDENCIES =
      Map.of(
          ":agent-framework-api", List.of(),
          ":agent-framework-engine", List.of(":agent-framework-api"));

  static List<String> governedProjects() {
    return List.copyOf(ALLOWED_DEPENDENCIES.keySet());
  }

  @ParameterizedTest
  @MethodSource("governedProjects")
  void projectOnlyDependsOnAllowedProjects(String gradlePath) {
    assertThat(projectDependenciesOf(gradlePath))
        .containsOnlyElementsOf(ALLOWED_DEPENDENCIES.get(gradlePath));
  }

  @Test
  void noProductProjectDependsOnTheHarnessPolicyProject() {
    for (String gradlePath : ALLOWED_DEPENDENCIES.keySet()) {
      assertThat(projectDependenciesOf(gradlePath)).doesNotContain(":build-tools:harness-policy");
    }
  }

  private static List<String> projectDependenciesOf(String gradlePath) {
    Matcher matcher = PROJECT_DEPENDENCY.matcher(ProjectLayout.buildFileText(gradlePath));
    List<String> dependencies = new ArrayList<>();
    while (matcher.find()) {
      dependencies.add(matcher.group(1));
    }
    return dependencies;
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew --no-daemon :build-tools:harness-policy:test --tests '*DependencyDirectionPolicyTest*'
```

Expected: FAIL with `UncheckedIOException: Cannot read .../agent-framework-engine/build.gradle.kts`,
because the engine project does not exist yet.

- [ ] **Step 3: Register the engine project**

Modify `settings.gradle.kts`:

```kotlin
include(":agent-framework-api")
include(":agent-framework-engine")
include(":build-tools:harness-policy")
```

- [ ] **Step 4: Add the engine build file**

Create `agent-framework-engine/build.gradle.kts`:

```kotlin
plugins {
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
}

description = "Embedded agent execution state machine for Agent Framework for Java."

dependencies {
    api(project(":agent-framework-api"))
}
```

- [ ] **Step 5: Add the engine package declaration**

Create `agent-framework-engine/src/main/java/com/microsoft/agentframework/engine/package-info.java`:

```java
/**
 * Embedded agent execution engine.
 *
 * <p>The engine owns run and turn state transitions. It never owns dependency injection, thread
 * pools, servers, or configuration; a host runtime supplies those.
 */
package com.microsoft.agentframework.engine;
```

- [ ] **Step 6: Add a test that proves the API is visible from the engine**

Create `agent-framework-engine/src/test/java/com/microsoft/agentframework/engine/EngineDependencyTest.java`:

```java
package com.microsoft.agentframework.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EngineDependencyTest {

  @Test
  void apiPackageIsReachableFromTheEngineRuntimeClasspath() {
    Package apiPackage = com.microsoft.agentframework.api.package_marker();
    assertThat(apiPackage.getName()).isEqualTo("com.microsoft.agentframework.api");
  }
}
```

This test needs a reachable API type, so add it in the next step before running.

- [ ] **Step 7: Add the API marker the engine test uses**

Create `agent-framework-api/src/main/java/com/microsoft/agentframework/api/ApiPackages.java`:

```java
package com.microsoft.agentframework.api;

/** Exposes the public API package for cross-project visibility checks. */
public final class ApiPackages {

  private ApiPackages() {}

  /**
   * Returns the public API package.
   *
   * @return the {@code com.microsoft.agentframework.api} package
   */
  public static Package publicApi() {
    return ApiPackages.class.getPackage();
  }
}
```

Then replace the body of `EngineDependencyTest` with the call that compiles:

```java
package com.microsoft.agentframework.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agentframework.api.ApiPackages;
import org.junit.jupiter.api.Test;

class EngineDependencyTest {

  @Test
  void apiPackageIsReachableFromTheEngineRuntimeClasspath() {
    assertThat(ApiPackages.publicApi().getName()).isEqualTo("com.microsoft.agentframework.api");
  }
}
```

- [ ] **Step 8: Extend the composition policy to cover the engine**

Modify `ModuleCompositionPolicyTest.java` line 16, replacing the constant with:

```java
  private static final List<String> PRODUCT_PROJECTS =
      List.of(":agent-framework-api", ":agent-framework-engine");
```

- [ ] **Step 9: Add the engine row to the contract**

Modify `docs/operations/module-composition.md` so the product table row for
`:agent-framework-engine` is present exactly as written in Task 2. It already is; confirm
the row is unchanged and that no row was lost.

- [ ] **Step 10: Write the engine dependency lock**

```bash
./gradlew --no-daemon :agent-framework-engine:resolveAndLockAll --write-locks
```

Expected: `agent-framework-engine/gradle.lockfile` is created.

- [ ] **Step 11: Verify**

```bash
./gradlew --no-daemon policyCheck quality testJava17
```

Expected: `BUILD SUCCESSFUL` with `DependencyDirectionPolicyTest` and
`EngineDependencyTest` passing.

- [ ] **Step 12: Commit**

```bash
git add settings.gradle.kts agent-framework-api agent-framework-engine build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness
git commit -m "build: compose the agent-framework-engine project with an enforced dependency direction"
```

---

### Task 5: Add `agent-framework-testkit`

**Files:**
- Create: `agent-framework-testkit/build.gradle.kts`
- Create: `agent-framework-testkit/src/main/java/com/microsoft/agentframework/testkit/package-info.java`
- Create: `agent-framework-testkit/src/main/java/com/microsoft/agentframework/testkit/DeterministicClock.java`
- Create: `agent-framework-testkit/src/test/java/com/microsoft/agentframework/testkit/DeterministicClockTest.java`
- Create: `agent-framework-testkit/gradle.lockfile`
- Modify: `settings.gradle.kts`
- Modify: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ModuleCompositionPolicyTest.java:16`
- Modify: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/DependencyDirectionPolicyTest.java:19`

**Interfaces:**
- Consumes: `:agent-framework-api`.
- Produces: `DeterministicClock.fixedAt(Instant)` returning a `java.time.Clock` that never
  advances, and `DeterministicClock.steppingFrom(Instant, Duration)` returning a `Clock`
  that advances by a fixed step on every read. Later plans use these to keep golden
  scenarios reproducible.

- [ ] **Step 1: Extend both policy constants and watch them fail**

Modify `ModuleCompositionPolicyTest.java` line 16:

```java
  private static final List<String> PRODUCT_PROJECTS =
      List.of(":agent-framework-api", ":agent-framework-engine", ":agent-framework-testkit");
```

Modify `DependencyDirectionPolicyTest.java` line 19:

```java
  private static final Map<String, List<String>> ALLOWED_DEPENDENCIES =
      Map.of(
          ":agent-framework-api", List.of(),
          ":agent-framework-engine", List.of(":agent-framework-api"),
          ":agent-framework-testkit", List.of(":agent-framework-api"));
```

```bash
./gradlew --no-daemon :build-tools:harness-policy:test --tests '*PolicyTest*'
```

Expected: FAIL, because `agent-framework-testkit` is not registered and has no build file.

- [ ] **Step 2: Register the project**

Modify `settings.gradle.kts`:

```kotlin
include(":agent-framework-api")
include(":agent-framework-engine")
include(":agent-framework-testkit")
include(":build-tools:harness-policy")
```

- [ ] **Step 3: Add the testkit build file**

Create `agent-framework-testkit/build.gradle.kts`:

```kotlin
plugins {
    id("agentframework.java-library-conventions")
    id("agentframework.test-conventions")
    id("agentframework.quality-conventions")
}

description = "Deterministic fixtures and contract-test bases for Agent Framework for Java."

dependencies {
    api(project(":agent-framework-api"))
}
```

- [ ] **Step 4: Add the package declaration**

Create `agent-framework-testkit/src/main/java/com/microsoft/agentframework/testkit/package-info.java`:

```java
/**
 * Deterministic fixtures for Agent Framework for Java.
 *
 * <p>Everything in this package must be reproducible: no wall-clock reads, no random seeds, and no
 * network access.
 */
package com.microsoft.agentframework.testkit;
```

- [ ] **Step 5: Write the failing clock test**

Create `agent-framework-testkit/src/test/java/com/microsoft/agentframework/testkit/DeterministicClockTest.java`:

```java
package com.microsoft.agentframework.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeterministicClockTest {

  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void fixedClockNeverAdvances() {
    var clock = DeterministicClock.fixedAt(START);
    assertThat(clock.instant()).isEqualTo(START);
    assertThat(clock.instant()).isEqualTo(START);
  }

  @Test
  void steppingClockAdvancesByTheConfiguredStep() {
    var clock = DeterministicClock.steppingFrom(START, Duration.ofMillis(250));
    assertThat(clock.instant()).isEqualTo(START);
    assertThat(clock.instant()).isEqualTo(START.plusMillis(250));
    assertThat(clock.instant()).isEqualTo(START.plusMillis(500));
  }
}
```

- [ ] **Step 6: Run it and watch it fail**

```bash
./gradlew --no-daemon :agent-framework-testkit:compileTestJava
```

Expected: FAIL with `cannot find symbol: class DeterministicClock`.

- [ ] **Step 7: Implement the clock**

Create `agent-framework-testkit/src/main/java/com/microsoft/agentframework/testkit/DeterministicClock.java`:

```java
package com.microsoft.agentframework.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Reproducible clocks for golden scenarios. */
public final class DeterministicClock {

  private DeterministicClock() {}

  /**
   * Returns a clock that always reports the same instant.
   *
   * @param instant the instant every read returns
   * @return a fixed UTC clock
   */
  public static Clock fixedAt(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  /**
   * Returns a clock that advances by a fixed step after each read.
   *
   * @param start the instant the first read returns
   * @param step the amount added before each later read
   * @return a stepping UTC clock
   */
  public static Clock steppingFrom(Instant start, Duration step) {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(step, "step");
    return new SteppingClock(start, step, ZoneOffset.UTC);
  }

  private static final class SteppingClock extends Clock {

    private final Instant start;
    private final Duration step;
    private final ZoneId zone;
    private final AtomicLong reads = new AtomicLong();

    private SteppingClock(Instant start, Duration step, ZoneId zone) {
      this.start = start;
      this.step = step;
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId targetZone) {
      return new SteppingClock(start, step, Objects.requireNonNull(targetZone, "targetZone"));
    }

    @Override
    public Instant instant() {
      return start.plus(step.multipliedBy(reads.getAndIncrement()));
    }
  }
}
```

- [ ] **Step 8: Write the lock and verify**

```bash
./gradlew --no-daemon :agent-framework-testkit:resolveAndLockAll --write-locks
./gradlew --no-daemon policyCheck quality testJava17
```

Expected: `BUILD SUCCESSFUL` with both `DeterministicClockTest` cases passing.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts agent-framework-testkit build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness
git commit -m "build: compose the agent-framework-testkit project with deterministic clocks"
```

---

### Task 6: Add `agent-framework-bom` and pin artifact coordinates

**Files:**
- Create: `agent-framework-bom/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `gradle.properties`
- Modify: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ModuleCompositionPolicyTest.java`

**Interfaces:**
- Consumes: the three product projects from Tasks 3 through 5.
- Produces: a `java-platform` project listing every published artifact at the single
  repository version, and `ModuleCompositionPolicyTest.bomListsEveryPublishedArtifact`.

- [ ] **Step 1: Write the failing BOM policy test**

Append to `ModuleCompositionPolicyTest.java`, inside the class:

```java
  @Test
  void bomListsEveryPublishedArtifact() {
    String bom = ProjectLayout.buildFileText(":agent-framework-bom");
    for (String gradlePath : PRODUCT_PROJECTS) {
      assertThat(bom).contains("api(project(\"" + gradlePath + "\"))");
    }
  }

  @Test
  void repositoryDeclaresASingleGroupAndVersion() throws IOException {
    Path properties = RepositoryPaths.root().resolve("gradle.properties");
    String text = Files.readString(properties, StandardCharsets.UTF_8);
    assertThat(text).contains("group=com.microsoft.agentframework");
    assertThat(text).containsPattern("(?m)^version=\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?$");
  }
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew --no-daemon :build-tools:harness-policy:test --tests '*ModuleCompositionPolicyTest*'
```

Expected: FAIL. `bomListsEveryPublishedArtifact` cannot read the missing BOM build file and
`repositoryDeclaresASingleGroupAndVersion` finds no `group=` line.

- [ ] **Step 3: Declare the coordinates**

Modify `gradle.properties`, appending two lines to the existing content:

```properties
group=com.microsoft.agentframework
version=0.1.0-SNAPSHOT
```

- [ ] **Step 4: Register and add the BOM**

Modify `settings.gradle.kts`:

```kotlin
include(":agent-framework-api")
include(":agent-framework-bom")
include(":agent-framework-engine")
include(":agent-framework-testkit")
include(":build-tools:harness-policy")
```

Create `agent-framework-bom/build.gradle.kts`:

```kotlin
plugins {
    `java-platform`
}

description = "Dependency constraints for every published Agent Framework for Java artifact."

javaPlatform {
    allowDependencies()
}

dependencies {
    api(project(":agent-framework-api"))
    api(project(":agent-framework-engine"))
    api(project(":agent-framework-testkit"))
}
```

- [ ] **Step 5: Exclude the BOM from Java-project assertions**

The BOM is a `java-platform`, so it must not apply the Java conventions and has no
lockfile. Modify `ModuleCompositionPolicyTest.java` so the platform is checked separately.
Replace the constants block with:

```java
  private static final List<String> PRODUCT_PROJECTS =
      List.of(":agent-framework-api", ":agent-framework-engine", ":agent-framework-testkit");

  private static final String PLATFORM_PROJECT = ":agent-framework-bom";
```

Then add:

```java
  @Test
  void platformProjectIsRegisteredAndUsesJavaPlatform() {
    assertThat(ProjectLayout.includedProjects()).contains(PLATFORM_PROJECT);
    assertThat(ProjectLayout.buildFileText(PLATFORM_PROJECT)).contains("`java-platform`");
  }
```

- [ ] **Step 6: Verify**

```bash
./gradlew --no-daemon policyCheck quality testJava17
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts gradle.properties agent-framework-bom build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ModuleCompositionPolicyTest.java
git commit -m "build: publish artifact coordinates through a java-platform BOM"
```

---

### Task 7: Bind the composition to the upstream compatibility matrix

The compatibility matrix already assigns every upstream feature a `Java 소유 모듈`. Bind
those names to the modules that now exist, so a future feature cannot be assigned to a
module nobody created.

**Files:**
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/CompatibilityMatrixPolicyTest.java`
- Modify: `docs/upstream/snapshots/d0a4165f/compatibility-matrix.md`

**Interfaces:**
- Consumes: `ProjectLayout.includedProjects()`, the matrix table rows.
- Produces: a proof that every `Java 소유 모듈` value maps to a known module role.

- [ ] **Step 1: Write the failing matrix policy test**

Create `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/CompatibilityMatrixPolicyTest.java`:

```java
package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Proves the upstream compatibility matrix only assigns features to known module roles. */
class CompatibilityMatrixPolicyTest {

  private static final Path MATRIX =
      Path.of("docs/upstream/snapshots/d0a4165f/compatibility-matrix.md");

  private static final Set<String> KNOWN_ROLES =
      Set.of("API", "Engine", "Host", "Adapter", "Testkit");

  @Test
  void everyOwningModuleValueUsesAKnownRole() throws IOException {
    List<String> rows =
        Files.readAllLines(RepositoryPaths.root().resolve(MATRIX), StandardCharsets.UTF_8).stream()
            .filter(line -> line.startsWith("| ") && line.chars().filter(c -> c == '|').count() > 12)
            .toList();

    Set<String> roles = new LinkedHashSet<>();
    for (String row : rows) {
      String[] cells = row.split("\\|");
      String owner = cells[11].trim();
      if (owner.isEmpty() || owner.equals("Java 소유 모듈") || owner.equals("---")) {
        continue;
      }
      Arrays.stream(owner.split("\\+")).map(String::trim).forEach(roles::add);
    }

    assertThat(roles).isNotEmpty();
    assertThat(roles).isSubsetOf(KNOWN_ROLES);
  }

  @Test
  void everyEngineOwnedFeatureHasAnEngineProject() {
    assertThat(ProjectLayout.includedProjects()).contains(":agent-framework-engine");
  }
}
```

- [ ] **Step 2: Run it and read the failure**

```bash
./gradlew --no-daemon :build-tools:harness-policy:test --tests '*CompatibilityMatrixPolicyTest*'
```

Expected: either PASS, or FAIL listing role values outside `KNOWN_ROLES`.

- [ ] **Step 3: Normalize any unknown role in the matrix**

If the failure lists a role that is not in `KNOWN_ROLES`, edit only the offending cells in
`docs/upstream/snapshots/d0a4165f/compatibility-matrix.md` so each cell uses one or more of
`API`, `Engine`, `Host`, `Adapter`, `Testkit`, joined with ` + `. Do not change any other
column; the matrix is the reviewed record of upstream behavior.

- [ ] **Step 4: Verify**

```bash
./gradlew --no-daemon policyCheck
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/CompatibilityMatrixPolicyTest.java docs/upstream/snapshots/d0a4165f/compatibility-matrix.md
git commit -m "test: bind the compatibility matrix to the composed module roles"
```

---

### Task 8: Record the composition in the repository entry documents

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/operations/module-composition.md`

**Interfaces:**
- Consumes: every project registered in Tasks 3 through 6.
- Produces: the human-readable statement of the composed structure that
  `RepositoryGovernancePolicyTest` and `ModuleCompositionPolicyTest` already enforce.

- [ ] **Step 1: State the composed structure in the README**

Add this section to `README.md` immediately before the `## 구현 계획` section:

```markdown
## 모듈 구성

| Gradle 프로젝트 | 책임 |
| --- | --- |
| `:agent-framework-api` | 호스트 중립 공개 계약 |
| `:agent-framework-engine` | 임베디드 실행 상태 머신 |
| `:agent-framework-testkit` | 재현 가능한 테스트 fixture |
| `:agent-framework-bom` | published artifact 버전 고정 |
| `:build-tools:harness-policy` | 실행 가능한 저장소 정책 |

모듈 규칙은 [모듈 구성 계약](docs/operations/module-composition.md)이 정의하고
`./gradlew policyCheck`가 강제합니다.
```

- [ ] **Step 2: Point agent instructions at the contract**

Add this line to the `## Architecture boundaries` section of `AGENTS.md`:

```markdown
- Module registration, dependency direction, and artifact coordinates are defined in
  `docs/operations/module-composition.md` and enforced by `./gradlew policyCheck`.
```

- [ ] **Step 3: Close the contract's open questions**

In `docs/operations/module-composition.md`, confirm the product table now lists all four
product projects and that the `Adding a project` procedure matches what Tasks 3 through 6
actually did. Correct any step that diverged.

- [ ] **Step 4: Verify the full harness**

```bash
./gradlew --no-daemon check
```

Expected: `BUILD SUCCESSFUL` when Temurin 17, 21, and 25 are installed. Otherwise run
`./gradlew --no-daemon policyCheck quality testJava17` locally and let CI run the rest.

- [ ] **Step 5: Commit and open the pull request**

```bash
git add README.md AGENTS.md docs/operations/module-composition.md
git commit -m "docs: record the composed module structure"
git push origin HEAD
gh pr create --base main --title "build: compose the product module foundation" --body "Registers agent-framework-api, engine, testkit, and bom under the existing convention plugins, and extends the policy suite with module registration, dependency direction, and compatibility matrix rules."
gh pr checks --watch
```

Expected: `Verify result` reports success.

---

## Out of Scope

These belong to later plans, one per subsystem, each driven by its own upstream feature
documents:

- Message, content, response, and usage types (`02-message-content.md`).
- Model client ports and streaming execution (`03-model-execution.md`).
- Function tool definitions and the tool invocation loop (`05-function-tools.md`).
- Session state, stores, and serialization (`08-sessions.md`).
- Typed interceptors (`10-middleware.md`).
- Golden scenario execution and `RunScore` emission (`29-evaluation-testing.md`).
- Provider adapters, hosting, and protocol modules (`20-hosting.md` and the protocol
  documents).

The first follow-on plan should be the `agent-framework-api` type model, because every
other module depends on it and the compatibility matrix marks it `필수` in `MVP/Core+`.

## Self-Review

**Spec coverage.** The module table in
`docs/superpowers/specs/2026-08-10-agent-framework-java-foundation-design.md` section 5
lists `agent-framework-bom`, `agent-framework-api`, `agent-framework-engine`,
`agent-framework-testkit`, `providers/`, `integrations/`, `starters/`,
`compatibility-tests/`, and `samples/`. Tasks 3 through 6 create the first four. The
remaining directories are deliberately not created: section 5 states that empty modules
must not be created ahead of the features that need them, and the Out of Scope section
records where they arrive.

**Placeholder scan.** No step contains TBD, TODO, "implement later", or an unnamed file.
Every code step shows the full file or the exact replacement block, and every command step
states its expected outcome.

**Type consistency.** `ProjectLayout.includedProjects()`, `ProjectLayout.buildFile()`,
`ProjectLayout.projectDirectory()`, and `ProjectLayout.buildFileText()` are defined in
Task 3 Step 1 and used unchanged in Tasks 4, 6, and 7.
`ModuleCompositionPolicyTest.PRODUCT_PROJECTS` is extended in Tasks 4, 5, and 6, and
`DependencyDirectionPolicyTest.ALLOWED_DEPENDENCIES` is extended in Task 5.
`DeterministicClock.fixedAt(Instant)` and `DeterministicClock.steppingFrom(Instant, Duration)`
are declared in the Task 5 interface block and implemented in Task 5 Step 7.
`ApiPackages.publicApi()` is introduced in Task 4 Step 7 and is the only API type the
engine test uses.
