# Repository Harness Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the repository-owned engineering harness: portable agent instructions, a Java 17 Maven quality baseline, versioned agent artifact contracts, policy regression tests, and a trusted PR CI workflow on the existing `aks-runners` ARC scale set.

**Architecture:** The repository owns executable development rules and CI entry points; local credentials and agent preferences stay uncommitted; AKS/ARC pod, identity, and network configuration stays platform-managed. A non-published `build-tools/harness-policy` Maven module tests instruction, artifact, and workflow policies before product modules exist.

**Tech Stack:** Git, Java 17, Apache Maven 3.9.16 Wrapper, JUnit 5.14.4, AssertJ 3.27.7, Jackson 2.22.1, Spotless 3.9.0, Checkstyle 12.3.1, PMD 7.26.0, SpotBugs Maven Plugin 4.10.3.0, JaCoCo 0.8.15, GitHub Actions, AKS Actions Runner Controller.

## Global Constraints

- Minimum supported Java is 17; CI validates Eclipse Temurin JDK 17, 21, and 25.
- Product code must remain independent of Spring, Quarkus, Jakarta EE, Micronaut, provider SDKs, DI containers, HTTP servers, executors, and schedulers unless a later approved module contract explicitly permits them.
- `AGENTS.md` is the canonical vendor-neutral repository instruction file.
- Vendor instruction files are thin adapters and must not duplicate repository rules.
- Exact plugin and action versions are pinned; action references use full 40-character commit SHAs.
- General Java CI runs on the existing non-privileged `aks-runners` ARC scale set.
- No secret, credential, local trace, personal model setting, or AKS Helm value is committed.
- External fork code must not gain trusted self-hosted runner credentials or internal network access.
- CI logic must call commands that developers can run locally with `./mvnw`.
- This plan does not create product API/engine modules, empty framework adapters, privileged runner scale sets, release automation, or live-model evaluation.
- Every commit created by an agent includes:

```text
Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

---

## File Map

### Repository governance

- `AGENTS.md`: canonical repository identity, boundaries, workflow, and completion contract.
- `CLAUDE.md`: Claude-specific pointer to `AGENTS.md`.
- `GEMINI.md`: Gemini-specific pointer to `AGENTS.md`.
- `.github/copilot-instructions.md`: GitHub Copilot pointer to `AGENTS.md`.
- `CONTRIBUTING.md`: human contribution workflow and local commands.
- `SECURITY.md`: vulnerability reporting and sensitive-data rules.
- `.github/CODEOWNERS`: review ownership for public contracts, harness, workflows, and upstream snapshots.
- `.gitignore`: excludes build output, IDE files, credentials, local harness runs, and local agent configuration.
- `.gitattributes`: normalizes line endings and marks generated/binary files.
- `.editorconfig`: shared text defaults.
- `build/check-repository-policy.sh`: portable, dependency-free regression check for required repository files and adapter behavior.

### Maven quality foundation

- `pom.xml`: root aggregator/parent, Java and dependency policy, plugin versions, and inherited quality executions.
- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/*`: Maven 3.9.16 Wrapper.
- `.mvn/maven.config`: stable non-interactive local/CI Maven defaults.
- `config/checkstyle/checkstyle.xml`: non-formatting source policy.
- `config/pmd/ruleset.xml`: high-signal source bug rules.
- `config/spotbugs/exclude.xml`: narrow generated-code exclusions.

### Portable harness contracts

- `.harness/schemas/*.schema.json`: versioned node-edge artifact contracts.
- `.harness/examples/*.json`: valid example for every schema.
- `build-tools/harness-policy/pom.xml`: non-published policy-test module.
- `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/RepositoryPaths.java`: repository-root discovery.
- `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ArtifactContractTest.java`: schema/example contract checks.
- `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/WorkflowPolicyTest.java`: GitHub workflow trust and pinning checks.

### CI and ARC contract

- `.github/workflows/ci.yml`: trusted PR/push JDK matrix on `aks-runners`.
- `.github/dependabot.yml`: Maven and GitHub Actions update proposals.
- `scripts/check-arc-runner.sh`: read-only local validation of the connected ARC scale set.
- `docs/operations/github-actions-runner-contract.md`: app/platform ownership and runner assumptions.

---

### Task 1: Initialize Git and repository governance

**Files:**
- Create: `.gitignore`
- Create: `.gitattributes`
- Create: `.editorconfig`
- Create: `build/check-repository-policy.sh`
- Create: `AGENTS.md`
- Create: `CLAUDE.md`
- Create: `GEMINI.md`
- Create: `.github/copilot-instructions.md`
- Create: `CONTRIBUTING.md`
- Create: `SECURITY.md`
- Create: `.github/CODEOWNERS`
- Modify: `README.md`

**Interfaces:**
- Consumes: approved foundation and engineering harness design documents.
- Produces: canonical `AGENTS.md`; `sh build/check-repository-policy.sh` returning zero only when repository governance is complete.

- [ ] **Step 1: Initialize a local Git repository without adding a remote**

Run:

```bash
git init -b main
git status --short
```

Expected: Git reports an empty repository on `main`; existing `README.md` and `docs/` are untracked.

- [ ] **Step 2: Write the failing repository policy check**

Create `build/check-repository-policy.sh`:

```sh
#!/bin/sh
set -eu

repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
failures=0

require_file() {
  relative_path=$1
  if [ ! -f "$repository_root/$relative_path" ]; then
    printf 'missing required file: %s\n' "$relative_path" >&2
    failures=$((failures + 1))
  fi
}

require_text() {
  relative_path=$1
  expected=$2
  if [ -f "$repository_root/$relative_path" ] &&
     ! grep -Fq "$expected" "$repository_root/$relative_path"; then
    printf '%s must contain: %s\n' "$relative_path" "$expected" >&2
    failures=$((failures + 1))
  fi
}

for required_file in \
  AGENTS.md \
  CLAUDE.md \
  GEMINI.md \
  .github/copilot-instructions.md \
  CONTRIBUTING.md \
  SECURITY.md \
  .github/CODEOWNERS \
  .editorconfig \
  .gitattributes \
  .gitignore
do
  require_file "$required_file"
done

for adapter in CLAUDE.md GEMINI.md .github/copilot-instructions.md
do
  require_text "$adapter" "AGENTS.md"
  if [ -f "$repository_root/$adapter" ] &&
     [ "$(wc -l < "$repository_root/$adapter" | tr -d ' ')" -gt 20 ]; then
    printf '%s must remain a thin adapter of at most 20 lines\n' "$adapter" >&2
    failures=$((failures + 1))
  fi
done

require_text AGENTS.md "## Architecture boundaries"
require_text AGENTS.md "## Standard workflow"
require_text AGENTS.md "## Verification contract"
require_text AGENTS.md "## Prohibited changes"

if [ "$failures" -ne 0 ]; then
  exit 1
fi

printf 'Repository policy check passed.\n'
```

Make it executable:

```bash
chmod +x build/check-repository-policy.sh
```

- [ ] **Step 3: Run the policy check and verify it fails**

Run:

```bash
sh build/check-repository-policy.sh
```

Expected: exit 1 with missing-file messages beginning with `AGENTS.md`.

- [ ] **Step 4: Add repository text and ignore policies**

Create `.editorconfig`:

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true

[*.{java,xml,yml,yaml,json}]
indent_style = space
indent_size = 2

[*.md]
trim_trailing_whitespace = false

[*.{sh,bash}]
indent_style = space
indent_size = 2
```

Create `.gitattributes`:

```gitattributes
* text=auto eol=lf
*.cmd text eol=crlf
*.bat text eol=crlf
*.jar binary
*.png binary
*.jpg binary
*.jpeg binary
*.gif binary
```

Create `.gitignore`:

```gitignore
# Maven and Java
target/
*.class
*.log
.flattened-pom.xml

# Local worktrees
.worktrees/
.superpowers/

# IDE and operating system
.idea/
*.iml
.vscode/*
!.vscode/extensions.json
.DS_Store

# Local agent settings and run artifacts
CLAUDE.local.md
GEMINI.local.md
AGENTS.local.md
.claude/settings.local.json
.harness/runs/
.harness/local/

# Credentials and environment overrides
.env
.env.*
!.env.example
*.pem
*.key
settings.local.xml
```

- [ ] **Step 5: Create the canonical agent instruction contract**

Create `AGENTS.md`:

```markdown
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

## Standard workflow

1. Read the relevant design, public contract, usages, and neighboring tests.
2. Define the affected modules and observable success criteria.
3. For behavior changes, add a failing unit or contract test first.
4. Make the smallest coherent implementation that passes the test.
5. Run the narrowest relevant Maven Wrapper command, then expand verification by risk.
6. Review the diff for public API, dependency, session-format, telemetry, and compatibility impact.

Reuse existing helpers and fixtures. Keep edits inside the affected modules. Do not hide failures with
broad catches, silent fallbacks, deleted assertions, or `@Disabled`.

## Verification contract

- Local and CI verification uses `./mvnw`; CI-only quality logic is prohibited.
- Deterministic tests do not retry.
- Live provider retries report the first failure separately from eventual success.
- Public API changes require compatibility analysis after the first release.
- Engine, session, streaming, tool, and provider changes run their affected conformance suites.
- Instruction, workflow, or harness schema changes run harness policy regression.
- Final reports list commands run, outcomes, and any checks that could not run.

## Sensitive data

Prompt bodies, model responses, tool arguments, credentials, and personal agent traces are not
recorded by default. Commit only redacted examples. Keep local agent permissions, model choices,
tokens, credentials, and run artifacts in ignored files.

## Prohibited changes

- Do not commit secrets or local credentials.
- Do not automatically release, force-push, or change AKS/ARC infrastructure.
- Do not add framework types to core public APIs.
- Do not add blanket lint, vulnerability, or compatibility suppressions.
- Do not move the upstream pin without a reviewed behavior delta.
- Do not treat exact natural-language output or exact tool-call order as a long-term contract.
```

- [ ] **Step 6: Create thin vendor adapters**

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
Repository architecture, testing, security, and completion rules live there.
```

- [ ] **Step 7: Add human contribution, security, and ownership files**

Create `CONTRIBUTING.md`:

````markdown
# Contributing

Before changing code, read `AGENTS.md`, the approved designs in `docs/superpowers/specs/`, and the
pinned upstream guidance in `docs/upstream/`.

Use the committed Maven Wrapper:

```bash
./mvnw -B -ntp verify
```

Behavior changes require a failing test before implementation. Keep pull requests focused and state:

- the observable behavior changed;
- affected modules and public contracts;
- commands run and their results;
- upstream provenance when implementing MAF-compatible behavior.

Do not include credentials, prompt/model content, personal traces, or local agent configuration.
````

Create `SECURITY.md`:

```markdown
# Security Policy

Do not report vulnerabilities in public issues. Use the repository host's private vulnerability
reporting feature or contact the maintainers through the private channel configured for the project.

Reports should include affected versions, impact, reproduction steps, and suggested mitigations
without including production credentials or sensitive prompt/model data.

The project does not accept committed secrets, default recording of prompt/tool content, broad
vulnerability suppressions, or unreviewed workflow permission increases.
```

Create `.github/CODEOWNERS`:

```text
* @hwang-inhwan
/AGENTS.md @hwang-inhwan
/CLAUDE.md @hwang-inhwan
/GEMINI.md @hwang-inhwan
/.harness/ @hwang-inhwan
/.github/ @hwang-inhwan
/pom.xml @hwang-inhwan
/config/ @hwang-inhwan
/docs/upstream/ @hwang-inhwan
/docs/superpowers/specs/ @hwang-inhwan
```

- [ ] **Step 8: Link contributor entry points from the README**

Add this section after the document links in `README.md`:

```markdown
## 기여와 하네스

- [저장소 작업 지침](AGENTS.md)
- [기여 가이드](CONTRIBUTING.md)
- [보안 정책](SECURITY.md)

모든 로컬·CI 검증은 저장소에 포함된 Maven Wrapper를 기준으로 합니다.
```

- [ ] **Step 9: Run the policy check and verify it passes**

Run:

```bash
sh build/check-repository-policy.sh
```

Expected: `Repository policy check passed.`

- [ ] **Step 10: Commit repository governance**

Run:

```bash
git add README.md docs .editorconfig .gitattributes .gitignore build \
  AGENTS.md CLAUDE.md GEMINI.md CONTRIBUTING.md SECURITY.md .github
git commit -m "docs: establish repository engineering contract

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

Expected: one root commit containing the existing design documents and new governance files.

---

### Task 2: Add the Java 17 Maven Wrapper and parent build

**Files:**
- Modify: `build/check-repository-policy.sh`
- Create: `pom.xml`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `.mvn/wrapper/maven-wrapper.jar`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `.mvn/maven.config`

**Interfaces:**
- Consumes: repository governance from Task 1.
- Produces: `./mvnw -B -ntp validate`; centrally managed Java, Maven, JUnit, Jackson, and plugin versions.

- [ ] **Step 1: Extend the policy check before adding Maven files**

Add these checks before the final failure block in `build/check-repository-policy.sh`:

```sh
for required_file in \
  pom.xml \
  mvnw \
  mvnw.cmd \
  .mvn/wrapper/maven-wrapper.jar \
  .mvn/wrapper/maven-wrapper.properties \
  .mvn/maven.config
do
  require_file "$required_file"
done

require_text .mvn/wrapper/maven-wrapper.properties "apache-maven-3.9.16"
require_text pom.xml "<maven.compiler.release>17</maven.compiler.release>"
```

- [ ] **Step 2: Run the policy check and verify the new assertions fail**

Run:

```bash
sh build/check-repository-policy.sh
```

Expected: exit 1 with missing `pom.xml`, `mvnw`, and `.mvn/wrapper/*`.

- [ ] **Step 3: Create the root parent POM**

Create `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.microsoft.agentframework</groupId>
  <artifactId>agent-framework-java-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <name>Agent Framework for Java</name>
  <description>Framework-neutral Java implementation of Microsoft Agent Framework semantics.</description>
  <url>https://github.com/hwang-inhwan/agent-framework-java</url>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
    <project.build.outputTimestamp>2026-08-10T00:00:00Z</project.build.outputTimestamp>

    <junit.version>5.14.4</junit.version>
    <assertj.version>3.27.7</assertj.version>
    <jackson.version>2.22.1</jackson.version>

    <maven.clean.version>3.5.0</maven.clean.version>
    <maven.compiler.version>3.15.0</maven.compiler.version>
    <maven.deploy.version>3.1.4</maven.deploy.version>
    <maven.enforcer.version>3.6.3</maven.enforcer.version>
    <maven.install.version>3.1.4</maven.install.version>
    <maven.jar.version>3.5.1</maven.jar.version>
    <maven.resources.version>3.5.0</maven.resources.version>
    <maven.site.version>3.22.0</maven.site.version>
    <maven.surefire.version>3.5.6</maven.surefire.version>
    <spotless.version>3.9.0</spotless.version>
    <google-java-format.version>1.24.0</google-java-format.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.junit</groupId>
        <artifactId>junit-bom</artifactId>
        <version>${junit.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>${jackson.version}</version>
      </dependency>
      <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>${assertj.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-clean-plugin</artifactId>
          <version>${maven.clean.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>${maven.compiler.version}</version>
          <configuration>
            <release>${maven.compiler.release}</release>
            <encoding>${project.build.sourceEncoding}</encoding>
            <showWarnings>true</showWarnings>
            <compilerArgs>
              <arg>-Xlint:all</arg>
              <arg>-Xlint:-processing</arg>
            </compilerArgs>
          </configuration>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-deploy-plugin</artifactId>
          <version>${maven.deploy.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-install-plugin</artifactId>
          <version>${maven.install.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-jar-plugin</artifactId>
          <version>${maven.jar.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-resources-plugin</artifactId>
          <version>${maven.resources.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-site-plugin</artifactId>
          <version>${maven.site.version}</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>${maven.surefire.version}</version>
          <configuration>
            <trimStackTrace>false</trimStackTrace>
            <useModulePath>false</useModulePath>
          </configuration>
        </plugin>
      </plugins>
    </pluginManagement>

    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>${maven.enforcer.version}</version>
        <executions>
          <execution>
            <id>enforce-build-environment</id>
            <goals>
              <goal>enforce</goal>
            </goals>
            <configuration>
              <rules>
                <requireJavaVersion>
                  <version>[17,)</version>
                </requireJavaVersion>
                <requireMavenVersion>
                  <version>[3.9.16,4.0.0)</version>
                </requireMavenVersion>
                <banDuplicatePomDependencyVersions />
                <requirePluginVersions />
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>com.diffplug.spotless</groupId>
        <artifactId>spotless-maven-plugin</artifactId>
        <version>${spotless.version}</version>
        <configuration>
          <java>
            <googleJavaFormat>
              <version>${google-java-format.version}</version>
            </googleJavaFormat>
            <removeUnusedImports />
            <trimTrailingWhitespace />
            <endWithNewline />
          </java>
          <pom>
            <sortPom />
          </pom>
        </configuration>
        <executions>
          <execution>
            <id>spotless-check</id>
            <phase>verify</phase>
            <goals>
              <goal>check</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 4: Generate and pin the Maven Wrapper**

Run:

```bash
mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper \
  -Dmaven=3.9.16 \
  -Dtype=bin
```

Expected: `mvnw`, `mvnw.cmd`, wrapper JAR, and wrapper properties are generated. The properties file references `apache-maven-3.9.16-bin.zip` and includes a distribution SHA-256.

Create `.mvn/maven.config`:

```text
--no-transfer-progress
--show-version
```

- [ ] **Step 5: Verify the wrapper and root build**

Run:

```bash
./mvnw -B validate
sh build/check-repository-policy.sh
```

Expected: Maven reports 3.9.16, `BUILD SUCCESS`, then `Repository policy check passed.`

- [ ] **Step 6: Commit the Maven foundation**

Run:

```bash
git add pom.xml mvnw mvnw.cmd .mvn build/check-repository-policy.sh
git commit -m "build: add Java 17 Maven foundation

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Add high-signal static quality configuration

**Files:**
- Modify: `build/check-repository-policy.sh`
- Modify: `pom.xml`
- Create: `config/checkstyle/checkstyle.xml`
- Create: `config/pmd/ruleset.xml`
- Create: `config/spotbugs/exclude.xml`

**Interfaces:**
- Consumes: root Maven lifecycle from Task 2.
- Produces: inherited Checkstyle, PMD, SpotBugs, dependency analysis, and JaCoCo executions under `./mvnw verify`.

- [ ] **Step 1: Require quality configuration before creating it**

Add before the final failure block in `build/check-repository-policy.sh`:

```sh
for required_file in \
  config/checkstyle/checkstyle.xml \
  config/pmd/ruleset.xml \
  config/spotbugs/exclude.xml
do
  require_file "$required_file"
done

require_text pom.xml "<checkstyle.version>12.3.1</checkstyle.version>"
require_text pom.xml "<pmd.version>7.26.0</pmd.version>"
require_text pom.xml "<spotbugs.maven.version>4.10.3.0</spotbugs.maven.version>"
require_text pom.xml "<jacoco.version>0.8.15</jacoco.version>"
```

- [ ] **Step 2: Run the policy check and verify it fails**

Run:

```bash
sh build/check-repository-policy.sh
```

Expected: exit 1 naming the three missing config files and version properties.

- [ ] **Step 3: Create focused Checkstyle rules**

Create `config/checkstyle/checkstyle.xml`:

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
    "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
    "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
  <property name="charset" value="UTF-8"/>
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

- [ ] **Step 4: Create focused PMD and SpotBugs rules**

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
  <rule ref="category/java/bestpractices.xml/UnusedPrivateMethod"/>
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

- [ ] **Step 5: Add exact quality tool versions to the root POM**

Add these properties after `google-java-format.version`:

```xml
<checkstyle.maven.version>3.6.0</checkstyle.maven.version>
<checkstyle.version>12.3.1</checkstyle.version>
<maven.dependency.version>3.11.0</maven.dependency.version>
<maven.pmd.version>3.28.0</maven.pmd.version>
<pmd.version>7.26.0</pmd.version>
<spotbugs.maven.version>4.10.3.0</spotbugs.maven.version>
<jacoco.version>0.8.15</jacoco.version>
```

- [ ] **Step 6: Add inherited quality executions to the root POM**

Add these plugins after the Spotless plugin inside `build/plugins`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-checkstyle-plugin</artifactId>
  <version>${checkstyle.maven.version}</version>
  <dependencies>
    <dependency>
      <groupId>com.puppycrawl.tools</groupId>
      <artifactId>checkstyle</artifactId>
      <version>${checkstyle.version}</version>
    </dependency>
  </dependencies>
  <configuration>
    <configLocation>${maven.multiModuleProjectDirectory}/config/checkstyle/checkstyle.xml</configLocation>
    <includeTestSourceDirectory>true</includeTestSourceDirectory>
    <failOnViolation>true</failOnViolation>
  </configuration>
  <executions>
    <execution>
      <id>checkstyle-check</id>
      <phase>verify</phase>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-pmd-plugin</artifactId>
  <version>${maven.pmd.version}</version>
  <dependencies>
    <dependency>
      <groupId>net.sourceforge.pmd</groupId>
      <artifactId>pmd-java</artifactId>
      <version>${pmd.version}</version>
    </dependency>
  </dependencies>
  <configuration>
    <rulesets>
      <ruleset>${maven.multiModuleProjectDirectory}/config/pmd/ruleset.xml</ruleset>
    </rulesets>
    <failOnViolation>true</failOnViolation>
    <includeTests>true</includeTests>
    <printFailingErrors>true</printFailingErrors>
  </configuration>
  <executions>
    <execution>
      <id>pmd-check</id>
      <phase>verify</phase>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>com.github.spotbugs</groupId>
  <artifactId>spotbugs-maven-plugin</artifactId>
  <version>${spotbugs.maven.version}</version>
  <configuration>
    <effort>Max</effort>
    <threshold>Medium</threshold>
    <failOnError>true</failOnError>
    <excludeFilterFile>${maven.multiModuleProjectDirectory}/config/spotbugs/exclude.xml</excludeFilterFile>
  </configuration>
  <executions>
    <execution>
      <id>spotbugs-check</id>
      <phase>verify</phase>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <version>${maven.dependency.version}</version>
  <executions>
    <execution>
      <id>analyze-dependencies</id>
      <phase>verify</phase>
      <goals>
        <goal>analyze-only</goal>
      </goals>
      <configuration>
        <failOnWarning>true</failOnWarning>
      </configuration>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>${jacoco.version}</version>
  <executions>
    <execution>
      <id>prepare-agent</id>
      <goals>
        <goal>prepare-agent</goal>
      </goals>
    </execution>
    <execution>
      <id>jacoco-report</id>
      <phase>verify</phase>
      <goals>
        <goal>report</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 7: Format and verify the root build**

Run:

```bash
./mvnw -B spotless:apply
./mvnw -B verify
sh build/check-repository-policy.sh
```

Expected: all three commands exit 0. There is no coverage threshold because no product module exists.

- [ ] **Step 8: Commit quality configuration**

Run:

```bash
git add pom.xml build/check-repository-policy.sh config
git commit -m "build: configure static quality gates

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: Add portable harness artifact contracts and tests

**Files:**
- Modify: `pom.xml`
- Create: `build-tools/harness-policy/pom.xml`
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/RepositoryPaths.java`
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/ArtifactContractTest.java`
- Create: `.harness/schemas/task-intent.schema.json`
- Create: `.harness/schemas/change-context.schema.json`
- Create: `.harness/schemas/impact-set.schema.json`
- Create: `.harness/schemas/test-plan.schema.json`
- Create: `.harness/schemas/verification-result.schema.json`
- Create: `.harness/schemas/run-score.schema.json`
- Create: `.harness/examples/task-intent.json`
- Create: `.harness/examples/change-context.json`
- Create: `.harness/examples/impact-set.json`
- Create: `.harness/examples/test-plan.json`
- Create: `.harness/examples/verification-result.json`
- Create: `.harness/examples/run-score.json`

**Interfaces:**
- Consumes: JUnit/Jackson dependency management and inherited quality plugins.
- Produces: six schema/example pairs; `RepositoryPaths.root()`; parameterized artifact contract regression.

- [ ] **Step 1: Add the policy-test module and its failing tests**

Add this immediately after root `packaging` in `pom.xml`:

```xml
<modules>
  <module>build-tools/harness-policy</module>
</modules>
```

Create `build-tools/harness-policy/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.microsoft.agentframework</groupId>
    <artifactId>agent-framework-java-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../..</relativePath>
  </parent>

  <artifactId>agent-framework-harness-policy</artifactId>
  <packaging>jar</packaging>
  <name>Agent Framework for Java - Harness Policy Tests</name>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

Create `RepositoryPaths.java`:

```java
package com.microsoft.agentframework.build.harness;

import java.nio.file.Files;
import java.nio.file.Path;

final class RepositoryPaths {
  private RepositoryPaths() {}

  static Path root() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("AGENTS.md"))
          && Files.isDirectory(candidate.resolve("docs/upstream"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Cannot locate repository root from " + Path.of("").toAbsolutePath());
  }
}
```

Create `ArtifactContractTest.java`:

```java
package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ArtifactContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  static Stream<Arguments> contracts() {
    return Stream.of(
        Arguments.of("task-intent"),
        Arguments.of("change-context"),
        Arguments.of("impact-set"),
        Arguments.of("test-plan"),
        Arguments.of("verification-result"),
        Arguments.of("run-score"));
  }

  @ParameterizedTest(name = "{0} schema has a matching complete example")
  @MethodSource("contracts")
  void schemaHasMatchingCompleteExample(String contractName) throws IOException {
    Path root = RepositoryPaths.root();
    JsonNode schema =
        JSON.readTree(root.resolve(".harness/schemas/" + contractName + ".schema.json").toFile());
    JsonNode example = JSON.readTree(root.resolve(".harness/examples/" + contractName + ".json").toFile());

    assertThat(schema.path("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
    assertThat(schema.path("$id").asText()).endsWith("/" + contractName + ".schema.json");
    assertThat(schema.path("type").asText()).isEqualTo("object");
    assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    assertThat(schema.path("required").isArray()).isTrue();
    assertThat(example.path("schemaVersion").asText()).isEqualTo("1.0");

    for (JsonNode requiredProperty : schema.path("required")) {
      assertThat(example.has(requiredProperty.asText()))
          .as("example contains required property %s", requiredProperty.asText())
          .isTrue();
    }
  }
}
```

- [ ] **Step 2: Run the contract test and verify it fails**

Run:

```bash
./mvnw -B -pl build-tools/harness-policy \
  -Dtest=ArtifactContractTest test
```

Expected: FAIL because `.harness/schemas/task-intent.schema.json` does not exist.

- [ ] **Step 3: Create the task and context schemas**

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
    "nonGoals": { "type": "array", "items": { "type": "string" } },
    "successCriteria": { "type": "array", "minItems": 1, "items": { "type": "string" } },
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
  "required": ["schemaVersion", "repositoryRevision", "relevantFiles", "affectedModules", "evidence"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "repositoryRevision": { "type": "string", "minLength": 1 },
    "relevantFiles": {
      "type": "array",
      "uniqueItems": true,
      "items": { "type": "string", "minLength": 1 }
    },
    "affectedModules": {
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
  "repositoryRevision": "0123456789abcdef",
  "relevantFiles": ["AGENTS.md", "docs/upstream/README.md"],
  "affectedModules": ["build-tools/harness-policy"],
  "evidence": ["AGENTS.md defines the verification contract."]
}
```

- [ ] **Step 4: Create the impact and test-plan schemas**

Create `.harness/schemas/impact-set.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://agent-framework-java.dev/harness/impact-set.schema.json",
  "title": "ImpactSet",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "writablePaths", "affectedModules", "riskTier", "requiredGates"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "writablePaths": {
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": { "type": "string", "minLength": 1 }
    },
    "affectedModules": {
      "type": "array",
      "uniqueItems": true,
      "items": { "type": "string", "minLength": 1 }
    },
    "riskTier": { "enum": ["LOW", "MEDIUM", "HIGH", "CRITICAL"] },
    "requiredGates": {
      "type": "array",
      "minItems": 1,
      "uniqueItems": true,
      "items": { "enum": ["POLICY", "BUILD", "STATIC", "ARCHITECTURE", "CONFORMANCE", "HUMAN"] }
    }
  }
}
```

Create `.harness/examples/impact-set.json`:

```json
{
  "schemaVersion": "1.0",
  "writablePaths": ["build-tools/harness-policy/**", ".harness/**"],
  "affectedModules": ["build-tools/harness-policy"],
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
      "command": "./mvnw -B -pl build-tools/harness-policy -Dtest=ArtifactContractTest test",
      "expectedInitialOutcome": "FAIL",
      "required": true
    }
  ]
}
```

- [ ] **Step 5: Create the verification and score schemas**

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
          "durationMs": { "type": "integer", "minimum": 0 }
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
      "name": "artifact contracts",
      "command": "./mvnw -B -pl build-tools/harness-policy -Dtest=ArtifactContractTest test",
      "exitCode": 0,
      "status": "PASS",
      "durationMs": 1200
    }
  ],
  "overallStatus": "PASS"
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
    "violations": { "type": "array", "items": { "type": "string" } }
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
    "toolchain": "maven-3.9.16-java-17",
    "upstreamRevision": "d0a4165f170193ba1d026a259af40d35bb7eaefe"
  },
  "passed": true,
  "metrics": {
    "durationMs": 1200,
    "toolCalls": 3,
    "scopeViolations": 0
  },
  "violations": []
}
```

- [ ] **Step 6: Run the focused artifact contract test**

Run:

```bash
./mvnw -B -pl build-tools/harness-policy \
  -Dtest=ArtifactContractTest test
```

Expected: 6 parameterized tests pass.

- [ ] **Step 7: Run all inherited quality gates**

Run:

```bash
./mvnw -B spotless:apply
./mvnw -B verify
```

Expected: `BUILD SUCCESS`; Checkstyle, PMD, SpotBugs, dependency analysis, unit tests, and JaCoCo complete without violations.

- [ ] **Step 8: Commit portable artifact contracts**

Run:

```bash
git add pom.xml build-tools .harness
git commit -m "test: add portable harness artifact contracts

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Add policy-tested CI on the existing ARC scale set

**Files:**
- Create: `build-tools/harness-policy/src/test/java/com/microsoft/agentframework/build/harness/WorkflowPolicyTest.java`
- Create: `.github/workflows/ci.yml`
- Create: `.github/dependabot.yml`

**Interfaces:**
- Consumes: `./mvnw verify`, `RepositoryPaths.root()`, existing ARC scale set name `aks-runners`.
- Produces: JDK 17/21/25 CI; workflow regression that forbids unpinned actions, elevated default permissions, unsafe PR trigger, credential persistence, and unexpected runner labels.

- [ ] **Step 1: Write the failing workflow policy test**

Create `WorkflowPolicyTest.java`:

```java
package com.microsoft.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WorkflowPolicyTest {
  private static final Pattern PINNED_ACTION =
      Pattern.compile("\\s*-\\s+uses:\\s+[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}(?:\\s+#.*)?");

  @Test
  void ciUsesTrustedArcRunnerAndPinnedActions() throws IOException {
    Path workflow = RepositoryPaths.root().resolve(".github/workflows/ci.yml");
    List<String> lines = Files.readAllLines(workflow);
    String text = String.join("\n", lines);
    List<String> actionLines = lines.stream().filter(line -> line.trim().startsWith("- uses:")).toList();

    assertThat(text).contains("runs-on: aks-runners");
    assertThat(text).contains("contents: read");
    assertThat(text).contains("persist-credentials: false");
    assertThat(text).contains("java: [17, 21, 25]");
    assertThat(text).contains("github.event.pull_request.head.repo.full_name == github.repository");
    assertThat(text).doesNotContain("pull_request_target");
    assertThat(text).doesNotContain("write-all");
    assertThat(actionLines).isNotEmpty();
    assertThat(actionLines).allMatch(line -> PINNED_ACTION.matcher(line).matches());
  }

  @Test
  void dependabotTracksMavenAndActions() throws IOException {
    String text =
        Files.readString(RepositoryPaths.root().resolve(".github/dependabot.yml"));

    assertThat(text).contains("package-ecosystem: \"maven\"");
    assertThat(text).contains("package-ecosystem: \"github-actions\"");
  }
}
```

- [ ] **Step 2: Run the workflow policy test and verify it fails**

Run:

```bash
./mvnw -B -pl build-tools/harness-policy \
  -Dtest=WorkflowPolicyTest test
```

Expected: FAIL because `.github/workflows/ci.yml` does not exist.

- [ ] **Step 3: Create the pinned CI workflow**

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
  group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

jobs:
  verify:
    name: Verify / Java ${{ matrix.java }}
    if: github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository
    runs-on: aks-runners
    timeout-minutes: 30
    strategy:
      fail-fast: false
      matrix:
        java: [17, 21, 25]

    steps:
      - name: Check out source
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false

      - name: Set up Java
        uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
        with:
          distribution: temurin
          java-version: ${{ matrix.java }}
          cache: maven

      - name: Verify
        run: ./mvnw -B verify

      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: test-reports-java-${{ matrix.java }}
          path: |
            **/target/surefire-reports/
            **/target/site/jacoco/
            **/target/pmd.xml
            **/target/spotbugsXml.xml
          if-no-files-found: ignore
          retention-days: 7
```

- [ ] **Step 4: Create dependency update policy**

Create `.github/dependabot.yml`:

```yaml
version: 2
updates:
  - package-ecosystem: "maven"
    directory: "/"
    schedule:
      interval: "weekly"
      day: "monday"
      time: "02:00"
      timezone: "Asia/Seoul"
    open-pull-requests-limit: 5
    groups:
      maven-quality-tools:
        patterns:
          - "org.apache.maven.plugins:*"
          - "com.diffplug.spotless:*"
          - "com.puppycrawl.tools:*"
          - "net.sourceforge.pmd:*"
          - "com.github.spotbugs:*"
          - "org.jacoco:*"
      test-tools:
        patterns:
          - "org.junit:*"
          - "org.junit.jupiter:*"
          - "org.junit.platform:*"
          - "org.assertj:*"
          - "org.mockito:*"

  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "weekly"
      day: "monday"
      time: "02:30"
      timezone: "Asia/Seoul"
    open-pull-requests-limit: 5
```

- [ ] **Step 5: Run focused and full local verification**

Run:

```bash
./mvnw -B -pl build-tools/harness-policy \
  -Dtest=WorkflowPolicyTest test
./mvnw -B verify
```

Expected: both workflow tests pass and the full reactor reports `BUILD SUCCESS`.

- [ ] **Step 6: Commit baseline CI**

Run:

```bash
git add .github/workflows/ci.yml .github/dependabot.yml build-tools/harness-policy
git commit -m "ci: verify harness on ARC Java matrix

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: Document and validate the local ARC runner contract

**Files:**
- Create: `scripts/check-arc-runner.sh`
- Create: `docs/operations/github-actions-runner-contract.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: connected `kubectl` context and `autoscalingrunnersets.actions.github.com` CRD.
- Produces: read-only `scripts/check-arc-runner.sh` validating namespace `arc-runners`, scale set `aks-runners`, minimum zero, positive maximum, non-privileged runner container, and a non-default service account.

- [ ] **Step 1: Write the ARC preflight script**

Create `scripts/check-arc-runner.sh`:

```sh
#!/bin/sh
set -eu

namespace=${ARC_RUNNER_NAMESPACE:-arc-runners}
scale_set=${ARC_RUNNER_SCALE_SET:-aks-runners}
resource="autoscalingrunnersets.actions.github.com"

if ! command -v kubectl >/dev/null 2>&1; then
  printf 'kubectl is required for ARC preflight.\n' >&2
  exit 1
fi

context=$(kubectl config current-context)
if [ -z "$context" ]; then
  printf 'No kubectl context is selected.\n' >&2
  exit 1
fi

kubectl get crd autoscalingrunnersets.actions.github.com >/dev/null
kubectl get "$resource" "$scale_set" -n "$namespace" >/dev/null

minimum=$(kubectl get "$resource" "$scale_set" -n "$namespace" -o jsonpath='{.spec.minRunners}')
maximum=$(kubectl get "$resource" "$scale_set" -n "$namespace" -o jsonpath='{.spec.maxRunners}')
service_account=$(
  kubectl get "$resource" "$scale_set" -n "$namespace" \
    -o jsonpath='{.spec.template.spec.serviceAccountName}'
)
privileged=$(
  kubectl get "$resource" "$scale_set" -n "$namespace" \
    -o jsonpath='{range .spec.template.spec.containers[*]}{.securityContext.privileged}{"\n"}{end}'
)

if [ "${minimum:-0}" -ne 0 ]; then
  printf 'Expected %s/%s minRunners=0, found %s.\n' "$namespace" "$scale_set" "$minimum" >&2
  exit 1
fi

if [ -z "$maximum" ] || [ "$maximum" -le 0 ]; then
  printf 'Expected %s/%s to have a positive maxRunners value.\n' "$namespace" "$scale_set" >&2
  exit 1
fi

if [ -z "$service_account" ] || [ "$service_account" = "default" ]; then
  printf 'Expected %s/%s to use a dedicated service account.\n' "$namespace" "$scale_set" >&2
  exit 1
fi

if printf '%s\n' "$privileged" | grep -Fxq true; then
  printf 'Expected %s/%s runner containers to be non-privileged.\n' "$namespace" "$scale_set" >&2
  exit 1
fi

printf 'ARC runner contract passed: context=%s namespace=%s scaleSet=%s max=%s serviceAccount=%s\n' \
  "$context" "$namespace" "$scale_set" "$maximum" "$service_account"
```

Make it executable:

```bash
chmod +x scripts/check-arc-runner.sh
```

- [ ] **Step 2: Run the read-only ARC preflight**

Run:

```bash
sh scripts/check-arc-runner.sh
```

Expected on the currently connected cluster: PASS for context `evalollama`, namespace `arc-runners`,
scale set `aks-runners`, `minRunners=0`, `maxRunners=5`, and service account
`aks-runners-gha-rs-no-permission`.

- [ ] **Step 3: Document the app/platform runner contract**

Create `docs/operations/github-actions-runner-contract.md`:

````markdown
# GitHub Actions Runner Contract

## Application repository contract

The repository references `runs-on: aks-runners` for ordinary Maven verification. Jobs must not
assume Docker, privileged mode, Azure credentials, Kubernetes API permissions, internal service
access, persistent workspace storage, or a pre-warmed runner.

The repository owns workflow triggers, pinned actions, token permissions, Maven commands, caches,
artifacts, and timeouts.

## Platform contract

The platform owns ARC Helm releases, GitHub App credentials, runner images, service accounts,
namespaces, Pod Security Admission, network policy, node pools, autoscaling, log export, runner
groups, and Entra federated identities.

The `aks-runners` scale set is expected to:

- live in namespace `arc-runners`;
- be ephemeral and non-privileged;
- use a dedicated service account with no application permissions;
- scale from zero with a finite positive maximum;
- reach GitHub Actions and Maven Central over HTTPS;
- export controller, listener, and runner logs before pod deletion.

Run the read-only local preflight with:

```bash
sh scripts/check-arc-runner.sh
```

Override only for inspection of another approved scale set:

```bash
ARC_RUNNER_NAMESPACE=arc-runners \
ARC_RUNNER_SCALE_SET=another-scale-set \
sh scripts/check-arc-runner.sh
```

## Trust boundary

Do not run untrusted fork code on a runner that can reach internal services or obtain repository,
Azure, or Kubernetes credentials. Never combine `pull_request_target`, checkout of pull-request
head code, and execution of repository scripts.

Docker/Testcontainers jobs require a separately reviewed scale set, namespace, runner group, and
network policy. This repository does not create that privileged runner.
````

- [ ] **Step 4: Link the runner contract from the README**

Add under the README contribution/harness section:

```markdown
- [GitHub Actions runner 계약](docs/operations/github-actions-runner-contract.md)
```

- [ ] **Step 5: Run all foundation verification**

Run:

```bash
sh build/check-repository-policy.sh
./mvnw -B spotless:apply
./mvnw -B clean verify
sh scripts/check-arc-runner.sh
```

Expected: repository policy passes, Maven reactor reports `BUILD SUCCESS`, six artifact contract
cases and two workflow policy tests pass, and ARC runner contract passes.

- [ ] **Step 6: Commit the ARC contract**

Run:

```bash
git add README.md docs/operations scripts/check-arc-runner.sh
git commit -m "docs: define ARC runner contract

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Post-Foundation Plans

After this plan is implemented and reviewed, create and execute these independent plans in order:

1. `2026-08-10-maf-conformance-harness.md`
   - upstream compatibility matrix and provenance;
   - abstract Java conformance fixture API;
   - deterministic response, tool, session, streaming, and telemetry vectors;
   - upstream delta workflow.
2. `2026-08-10-agent-harness-regression.md`
   - full JSON Schema validation and artifact store;
   - deterministic repository fixtures;
   - grader and scorecard implementation;
   - old/new instruction differential runs, flake tracking, and canary workflow.
3. `2026-08-10-release-security-harness.md`
   - Revapi after the first published API baseline;
   - CycloneDX SBOM, CodeQL, dependency review, Scorecard, and scheduled vulnerability scan;
   - reproducible artifact comparison, protected release, signing, OIDC, and attestations.
4. `2026-08-10-privileged-integration-runner.md`, only when Testcontainers becomes necessary
   - dedicated ARC scale set, namespace, runner group, node pool, PSA, NetworkPolicy, and trust policy;
   - application workflow uses only the approved runner label and never manages cluster resources.

## Plan Verification Checklist

- Every engineering harness design requirement is either implemented by Tasks 1-6 or assigned to one
  named post-foundation plan.
- Java 17 compatibility is preserved: JUnit remains on 5.14.4, Checkstyle is pinned to the last
  Java-17-compatible 12.3.1 line, and no `--add-opens` or javac internal export is added.
- Repository rules are vendor-neutral; vendor adapters only point to `AGENTS.md`.
- The CI workflow uses the observed `aks-runners` scale-set name and full action SHAs.
- ARC validation is read-only; this plan does not modify AKS.
- No task creates product modules or placeholder framework adapters.
- No placeholder text, unresolved type, or unnamed file remains in Tasks 1-6.
