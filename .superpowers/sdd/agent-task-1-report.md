# Agent Task 1 Report: Immutable AgentDefinition

## Scope
Implemented Agent/Engine Separation Task 1 in `.worktrees/core-convergence` by adding an immutable API-level `AgentDefinition` with builder/toBuilder semantics and targeted API tests.

## Requirements addressed
- Added final `AgentDefinition` under `api.agent` with `builder()` and `toBuilder()`.
- Defaults now match the task clarifications:
  - omitted `id` generates a UUID;
  - omitted `name` defaults to `agent`;
  - omitted `description` and `instructions` default to `""`;
  - omitted `defaultRunOptions` creates a new empty `AgentRunOptions`;
  - omitted `attributes` defaults to `ContextAttributes.empty()`.
- Explicit `null` passed to builder setters is rejected.
- Tool declarations are defensively copied, immutable to callers, and duplicate tool names fail at build time.
- `AgentDefinition` implements structural equality/hashCode without introducing runtime/provider collaborator fields.
- `defaultRunOptions` rejects runtime collaborator content by forbidding `modelClientFactory`.

## RED/GREEN log

### RED: failing test before implementation
Command:
```bash
cd /Users/hwang-inhwan/workspace/agent-framework-java/.worktrees/core-convergence && ./gradlew :agent-framework-api:test --tests '*AgentDefinitionTest'
```
Result: **RED**
- Build failed during test compilation.
- Failure matched the task expectation: `AgentDefinitionTest` could not resolve `AgentDefinition` because the type did not exist yet.

### GREEN: targeted test after implementation
Command:
```bash
cd /Users/hwang-inhwan/workspace/agent-framework-java/.worktrees/core-convergence && ./gradlew :agent-framework-api:test --tests '*AgentDefinitionTest'
```
Result: **GREEN**
- `BUILD SUCCESSFUL`
- `AgentDefinitionTest` passed after implementing the new immutable contract.

## Verification

### Formatting fix
Command:
```bash
cd /Users/hwang-inhwan/workspace/agent-framework-java/.worktrees/core-convergence && ./gradlew :agent-framework-api:spotlessApply
```
Result: **GREEN**
- Applied the only required formatting fix after `spotlessJavaCheck` reported wrapping issues in the new test.

### Final verification
Command:
```bash
cd /Users/hwang-inhwan/workspace/agent-framework-java/.worktrees/core-convergence && ./gradlew policyCheck :agent-framework-api:test --tests '*AgentDefinitionTest' :agent-framework-api:quality
```
Result: **GREEN**
- `policyCheck` passed.
- Targeted `AgentDefinitionTest` passed.
- `:agent-framework-api:quality` passed.

## Files changed
- `agent-framework-api/src/main/java/io/github/hellices/agentframework/api/agent/AgentDefinition.java`
- `agent-framework-api/src/test/java/io/github/hellices/agentframework/api/agent/AgentDefinitionTest.java`
- `.superpowers/sdd/agent-task-1-report.md`

## Implementation notes
- `AgentDefinition` stores only declarative state: id, name, description, instructions, immutable tool declarations, default run options, and context attributes.
- The builder preserves omission defaults while rejecting explicit `null` for all object setters.
- Equality/hashCode compare `AgentRunOptions` structurally rather than by object identity so logically equivalent definitions compare equal.
- Reflection-based tests enforce the absence of extra runtime collaborator fields in the new type.

## Self-review
- Reviewed the task brief, convergence plan, and design notes before implementation.
- Confirmed the new type introduces no `ModelClient`, handler, session-store, interceptor, or provider object fields.
- Rechecked builder defaults, duplicate tool validation, immutability, and `toBuilder()` round-trip behavior against the clarifications.
- Per sub-agent constraints, performed manual self-review instead of dispatching a nested reviewer.

## Concerns
- No functional concerns after targeted verification.
- Full repository-wide multi-JDK verification was not run because Task 1 requested targeted API verification; the executed checks are listed above.

## Commit
- Pending at report write time; updated after commit below.

## Commit
- Created commit `5b018bc74e07fdb82347b2c208d4bcac42d71434` with message `api: add immutable agent definition`.
- Included the required `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>` trailer.

## Post-commit verification
Command:
```bash
cd /Users/hwang-inhwan/workspace/agent-framework-java/.worktrees/core-convergence && ./gradlew policyCheck :agent-framework-api:test --tests '*AgentDefinitionTest' :agent-framework-api:quality && git rev-parse HEAD && git status --short --ignored
```
Result:
- `policyCheck`: `BUILD SUCCESSFUL`
- targeted API test: `BUILD SUCCESSFUL`
- `:agent-framework-api:quality`: `BUILD SUCCESSFUL`
- verified committed HEAD: `5b018bc74e07fdb82347b2c208d4bcac42d71434`
- remaining status output is ignored build/session artifacts only.
