# 08 Harness features

**Prefix** `HAR` · **Upstream features** [12 harness](../upstream/snapshots/d0a4165f/features/12-harness.md),
[13 skills-background-code](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

Defines the contracts for harness assembly, the iteration policy, the built-in providers, skills,
the default approval assembly, and the optional execution modules. The harness is not an execution
kernel but an opinionated assembly layer. Model calls, sessions, and the workflow kernel are handled
by their own owning documents, and the approval state machine, the meaning of a standing rule, and
budget accounting are owned by [04 Tool definitions and the tool call loop](04-tools.md) and [07 Interceptors and context management](07-interceptors.md).
This document covers only which default combination the harness switches those contracts on with.

## Adoption scope

The `Grade` column in this document is, as [README](README.md#requirement-grades) defines it, how binding a requirement is once the decision to build the feature has been made; whether the feature is adopted at all follows the [compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md).

- The harness (`HAR01`, `HAR02`), skills (`SKL01`), and background (`BKG01`) all have adoption `Deferred`.
- This whole document is therefore deferred scope that is reviewed only after the MVP.

## Summary

| ID | Requirement | Adoption | Grade | Phase |
| --- | --- | --- | --- | --- |
| HAR-001 | The harness is kept as an assembly layer only | Deferred | Required | Core+ |
| HAR-002 | The default harness assembly follows a conservative opt-in policy | Deferred | Required | Core+ |
| HAR-003 | The harness rejects an invalid combination at creation time | Deferred | Required | Core+ |
| HAR-004 | Automatic iteration starts from a predicate middleware seam | Deferred | Recommended | Core+ |
| HAR-005 | Iteration stops immediately on a pending approval | Deferred | Required | Core+ |
| HAR-006 | The Todo provider includes only session state storage in the core | Deferred | Required | Core+ |
| HAR-007 | Todo manipulation results have a stable contract | Deferred | Recommended | Core+ |
| HAR-008 | The Mode provider offers a default `plan` mode and external change notifications | Deferred | Required | Core+ |
| HAR-009 | File memory defaults to session scope and blocks reserved names | Deferred | Required | Core+ |
| HAR-010 | File access is provided only as a separate optional module | Deferred | Optional | Optional |
| HAR-011 | Tool approval keeps the queue and the standing rules in the session | Deferred | Required | Core+ |
| HAR-012 | Tool approval rules distinguish arguments and host boundaries exactly | Deferred | Required | Core+ |
| HAR-013 | Approval re-entry is counted within the same request budget | Deferred | Recommended | Core+ |
| HAR-014 | Skills keep progressive disclosure and the three tool surfaces | Deferred | Recommended | Optional |
| HAR-015 | Skill script execution requires approval by default | Deferred | Required | Optional |
| HAR-016 | File-based skills and detailed errors are handled so they do not cross the trust boundary | Deferred | Required | Optional |
| HAR-017 | Background agents are left out of the MVP and start from a polling registry even later | Deferred | Recommended | Optional |
| HAR-018 | Shell execution is assembled manually from a separate tools module | Deferred | Optional | Optional |
| HAR-019 | The denylists of shell and local execution are documented as guardrails only | Deferred | Required | Optional |
| HAR-020 | LocalCodeAct is not treated as a sandbox and is excluded from the core | Deferred | Optional | Optional |
| HAR-021 | Sandboxed CodeAct backends are split into a separate optional module | Deferred | Optional | Optional |

---

## HAR-001 The harness is kept as an assembly layer only

**Requirement.** The Java harness must not reimplement the execution kernel and must be provided
only as a surface that assembles the existing agent, context providers, middleware, and approval
layer.

**Upstream comparison**

- .NET: `HarnessAgent` assembles the chat client stack, context providers, and decorators.
- Python: `create_harness_agent` assembles history, providers, middleware, and tools.

**Decision.** Both upstreams keep the harness as an opinionated assembly layer rather than an
orchestration kernel. Java must hold the same boundary so that `AgentEngine` does not take on host
responsibilities. If the harness absorbs the kernel too, the session, workflow, and provider
boundaries blur.

**Acceptance criteria**

- The harness API returns the result of combining an existing `Agent` with context providers.
- The harness module does not newly define its own model call loop or session storage format.
- The same core `Agent` can be assembled and run directly without the harness.

**Evidence** [12 harness assembly](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-002 The default harness assembly follows a conservative opt-in policy

**Requirement.** The default harness assembly must include only todo, mode, file memory, and
approval automatically, and must attach skills, file access, background agents, shell, and code
execution only when they are explicitly requested.

**Upstream comparison**

- .NET: Attaches skills based on the current working directory by default and keeps file access and background as options.
- Python: Keeps skills, file access, background, and shell all as explicit opt-ins.

**Decision.** Java takes the Python defaults. The safer default comes first, and because the harness
is a convenience layer for research, minimizing automatic expansion is better. Enabling file access
and script execution by default creates a trust boundary the host does not know about.

**Acceptance criteria**

- Creating the default harness alone does not expose the skills, file access, background, or shell tools.
- Those features are assembled only when a dedicated option or module dependency is given.
- The default assembly result includes todo, mode, file memory, and approval.

**Evidence** [12 harness assembly](../upstream/snapshots/d0a4165f/features/12-harness.md), [13 shared comparison](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-003 The harness rejects an invalid combination at creation time

**Requirement.** When harness options contradict each other, Java must fail at creation time rather
than before the first run.

**Upstream comparison**

- .NET: Rejects an invalid context/output token combination at the constructor stage.
- Python: Rejects an invalid token combination at the harness assembly stage.

**Decision.** Both upstreams agree. Because the harness is an assembly layer, there is no reason to
surface the error late. Letting it fail during execution means the misconfiguration is discovered
only after user messages and state have been consumed.

**Acceptance criteria**

- Creating a harness with an invalid option combination raises an exception immediately.
- No model call or session change happens before the failure.
- The same invalid combination is reported in the same exception category in both completion and streaming execution.

**Evidence** [12 harness assembly](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-004 Automatic iteration starts from a predicate middleware seam

**Requirement.** Automatic iteration in the Java harness must be provided as a single predicate
middleware seam in the MVP, and an ordered evaluator chain must be left to a later stage.

**Upstream comparison**

- .NET: Decides whether to iterate with an ordered evaluator chain.
- Python: Implements iteration with a `should_continue` predicate combined with helper functions.

**Decision.** Java takes the Python model first, for implementation cost and explainability. The
harness is not the core execution kernel, so there is no need to introduce a complex evaluator
priority scheme early. The seam is nevertheless left open so it can grow into the chain model in a
later stage.

**Acceptance criteria**

- The public seam that decides whether to iterate exists as a single predicate interface.
- The iteration seam can return the next input message or choose to stop.
- A public type dedicated to an evaluator chain is not required in the early Core+ stage.

**Evidence** [12 automatic iteration policy](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-005 Iteration stops immediately on a pending approval

**Requirement.** When a pending approval request appears during iteration, the harness must stop
execution immediately and return that request to the caller.

**Upstream comparison**

- .NET: The loop evaluator stops iterating when it meets a pending approval.
- Python: The loop helper returns the approval request to the caller when it meets a pending approval.

**Decision.** Both upstreams agree. Continuing to run the next iteration while an approval is
pending piles new state on top of a side effect that has not been decided yet. The approval layer is
the harness's safety coordinator, so it takes precedence over iteration.

**Acceptance criteria**

- A run in which an approval request appeared does not start the next iteration inside the same run.
- The caller can immediately observe one pending approval request.
- No further automatic iteration for the same request happens until an approval response is given.

**Evidence** [12 automatic iteration policy](../upstream/snapshots/d0a4165f/features/12-harness.md), [12 tool approval](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-006 The Todo provider includes only session state storage in the core

**Requirement.** The Java core harness must include only the session-state-based Todo provider, and
must leave file-based Todo storage as a separate optional feature.

**Upstream comparison**

- .NET: The todo provider and its tests have a stable surface.
- Python: Provides both session state storage and file storage, but the file-backed store has a wider operational surface.

**Decision.** Java puts only the session state path in the core. A file-backed store has to be
designed together with path safety, rename failures, and crash safety, so it goes beyond the Core+
scope of the harness. The session state model alone is enough for loop and mode integration.

**Acceptance criteria**

- The core harness module stores the Todo list in the session state.
- The core harness module does not require a file path.
- File-based Todo storage cannot be used without a separate module.

**Evidence** [12 Todo provider](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-007 Todo manipulation results have a stable contract

**Requirement.** The Todo add and complete tools must have increasing identifier assignment and a
zero-count completion return for non-existent items as a fixed contract.

**Upstream comparison**

- .NET: Fixes the increasing `todos_add` ID and the 0 return of `todos_complete` with tests.
- Python: Follows the same behavior and additionally verifies the durability of file-backed storage.

**Decision.** Both upstreams agree. The harness loop and the prompts treat the Todo tools like a
deterministic state machine. Turning a non-existent ID into an exception makes it a failure the
model cannot easily correct.

**Acceptance criteria**

- Todo IDs created in a single add call are not duplicated.
- The ID of a Todo added later is greater than the earlier IDs.
- Completing a set of non-existent IDs returns 0.

**Evidence** [12 Todo provider](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-008 The Mode provider offers a default `plan` mode and external change notifications

**Requirement.** The Mode provider must start with `plan` as the default mode and, when the mode is
changed externally, must inject that change as a user-role notification on the next turn.

**Upstream comparison**

- .NET: Keeps the default mode as `plan` and rejects an invalid mode setting.
- Python: Injects a notification before the next run when an external helper changes the mode.

**Decision.** Java combines the contracts of the two upstreams. Fixing the default mode creates a
prompt anchor, and an external change notification is what lets the model observe a mode transition
that a system instruction alone does not convey.

**Acceptance criteria**

- Reading the mode in a new session gives `plan`.
- Setting a mode that is not allowed fails and preserves the existing mode.
- When an external helper changes the mode, the next turn's input includes a change notification message.

**Evidence** [12 Mode provider](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-009 File memory defaults to session scope and blocks reserved names

**Requirement.** The file memory provider must set its default namespace to session scope and must
refuse to store internal reserved file names.

**Upstream comparison**

- .NET: Provides default namespace initialization and reserved name validation.
- Python: Fixes the `session_id`-scoped namespace, the `memories.md` injection, and reserved name rejection with tests.

**Decision.** Java takes Python's session-scoped default. It gives better reproducibility and
debugging than a timestamp+GUID default. Leaving the internal index and description file names open
would also let the model contaminate the memory system files.

**Acceptance criteria**

- Without a separate shared scope, the same memory namespace is visible only inside the same session.
- After a memory is stored, the `memories.md` index is injected as context on the next run.
- Storing under the names `memories.md` and `*_description.md` is not possible.

**Evidence** [12 File memory](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-010 File access is provided only as a separate optional module

**Requirement.** The file access provider must not be included in the Java core harness and must be
provided only as an explicit opt-in from a dedicated optional module.

**Upstream comparison**

- .NET: The file access options are experimental and the default is approval-required.
- Python: File access is opt-in and implements path safety together with regular expression guards.

**Decision.** Both have the feature, but its surface is wide. Shared mutable state, the approval
boundary, symlink blocking, and regex timeouts are all bundled together, which makes it risky to put
in the core harness. Java splits it into a separate module.

**Acceptance criteria**

- The file access tools cannot be used with the core harness dependency alone.
- Using file access requires a separate module and explicit configuration.
- The file access module default is approval-required.

**Evidence** [12 File access](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-011 Tool approval keeps the queue and the standing rules in the session

**Requirement.** The default harness approval assembly must connect the approval rule state and the pending request state defined by [04 Tool definitions and the tool call loop](04-tools.md) and [07 Interceptors and context management](07-interceptors.md) to the session storage path, and must expose only one pending request at a time on the caller surface.

**Upstream comparison**

- .NET: The approval agent stores rules and queued state in the session and uses a one-by-one surface.
- Python: The approval middleware stores `rules`, `queued_approval_requests`, and `collected_approval_responses` in the state.

**Decision.** The surface that does not expose several approval requests at once is kept, but the meaning of the state machine itself does not redefine the owning documents. The harness has only the assembly responsibility of putting the approval component on the session backing store and the default UI surface.

**Acceptance criteria**

- When approval is switched on through the default harness preset, the core approval state is included in the session serialization path.
- Even when several pending requests exist at once, the harness call surface returns only the earliest single request.
- Reading the same pending approval run again after saving and restoring the session surfaces the same request again.

**Evidence** [12 tool approval](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-012 Tool approval rules distinguish arguments and host boundaries exactly

**Requirement.** The automatic approval wiring of the default harness must use the exact-argument and host-boundary rules defined by [04 Tool definitions and the tool call loop](04-tools.md) as they are, and must not add a looser harness-only shortcut that approves whenever the name alone matches.

**Upstream comparison**

- .NET: Warns about the collision risk of name-based auto-approval and limits the all-tools rule to trusted environments.
- Python: Fixes argument-scoped rules and the hosted `server_label` scope with tests.

**Decision.** The meaning of exact-argument and host-boundary is already defined by the core approval document. The harness does not restate that meaning and must only fix the assembly so that the default preset switches on the same rule set.

**Acceptance criteria**

- The default approval preset does not additionally create a name-only standing rule mode with exact-argument matching turned off.
- When assembling a hosted tool, the harness also passes `server_label` or an equivalent host boundary identifier into the core approval state.
- Even for the same tool name, the default harness preset surfaces a new approval request again when the arguments or the host boundary differ.

**Evidence** [12 tool approval](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-013 Approval re-entry is counted within the same request budget

**Requirement.** The approval re-entry wiring of the default harness must share, as it is, the common request budget defined by [04 Tool definitions and the tool call loop](04-tools.md) and [07 Interceptors and context management](07-interceptors.md), and the harness layer must not create a separate budget counter dedicated to approval re-entry.

**Upstream comparison**

- .NET: Puts a separate `MaxAutoApprovalIterations` cap on the approval loop.
- Python: Shares the function invocation budget state with approval re-entry.

**Decision.** The meaning of the budget is owned by the core execution loop and the harness does not layer a separate state machine on top of it. An outer cap may be kept as an operational safeguard when needed, but the default assembly must follow the core budget semantics as they are for explainability to hold.

**Acceptance criteria**

- An approval-enabled harness preset does not additionally create an independent budget counter for automatic approval re-entry.
- A tool call that follows an automatic approval consumes the same remaining core budget.
- Even when an optional outer cap exists, it does not change the core usage accounting and deduction rules and acts only as an additional stop condition.

**Evidence** [12 Invocation budget](../upstream/snapshots/d0a4165f/features/12-harness.md), [12 tool approval](../upstream/snapshots/d0a4165f/features/12-harness.md)

---

## HAR-014 Skills keep progressive disclosure and the three tool surfaces

**Requirement.** The skills feature must advertise only names and descriptions in the default prompt,
and must disclose the actual bodies, resources, and scripts late, only through the three tools
`load_skill`, `read_skill_resource`, and `run_skill_script`.

**Upstream comparison**

- .NET: Reads skills from a source and builds the prompt and the three tools.
- Python: Reads skills in `before_run` and builds the system prompt and the three tools.

**Decision.** Both upstreams agree. Skills are not simple prompt fragments; they also carry
executable assets. Without progressive disclosure, every detail document and script contaminates the
default prompt and the approval boundary blurs as well.

**Acceptance criteria**

- When there is no skill, the skills-related prompt and tools are not injected.
- When there is a skill, there are exactly three public tool names.
- The skill body is not inserted directly into the full prompt before `load_skill` is called.

**Evidence** [13 Skills](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-015 Skill script execution requires approval by default

**Requirement.** `run_skill_script` must be an approval-required tool by default and must have an
approval policy independent from the read-only skills tools.

**Upstream comparison**

- .NET: An individual approval wrapper can be attached to each of the three skills tools.
- Python: Keeps an approval disable flag for each of `load_skill`, `read_skill_resource`, and `run_skill_script`.

**Decision.** Both upstreams agree. Reading and executing must not be treated at the same trust
level. Java keeps `run_skill_script` approval-required by default and allows a separate automatic
approval rule only for the read-only tools.

**Acceptance criteria**

- `run_skill_script` is approval-required unless configured otherwise.
- `load_skill` and `read_skill_resource` can be given automatic approval policies independently.
- An automatic approval setting for the read tools does not extend to approving script execution.

**Evidence** [13 Skills](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-016 File-based skills and detailed errors are handled so they do not cross the trust boundary

**Requirement.** A file-based skills source must block traversal and symlink escape, and the option
that returns detailed exceptions to the model as they are must be off by default.

**Upstream comparison**

- .NET: The file skills source checks traversal/symlink and limits `IncludeDetailedErrors` to trusted sources.
- Python: The file source defends against traversal/symlink and documents external sources as a trust boundary.

**Decision.** Both upstreams agree. The skills source is itself a trust boundary. Path escape and
re-injection of detailed exceptions can become channels for prompt injection and secret exposure.
Java keeps detailed errors as an optional feature only and keeps the default on the safe side.

**Acceptance criteria**

- File-based skills discovery does not follow paths outside the root or a symlink escape.
- In the default configuration, the raw exception message is not automatically re-injected into the model output path.
- Turning on detailed error exposure requires an explicit option.

**Evidence** [13 Skills](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-017 Background agents are left out of the MVP and start from a polling registry even later

**Requirement.** Background agents must not be included in the Java core harness MVP, and even when
they are introduced in a later stage they must start from a polling task registry contract rather
than real-time push.

**Upstream comparison**

- .NET: The background provider and the completion evaluator are experimental.
- Python: Background agents are experimental and use a tool-polled registry and a LOST state.

**Decision.** Both are experimental and require keeping a runtime handle and a session handle. The
LOST semantics after a restart, the child agent trust boundary, and the clear/continue rules have to
be designed together, which makes it hard to put in Core+.

**Acceptance criteria**

- The core harness module does not provide background task tools by default.
- Even when a later module is added, the default status query contract is a polling API.
- A task that has lost its runtime reference transitions to an explicit `LOST` state.

**Evidence** [13 Background agents](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-018 Shell execution is assembled manually from a separate tools module

**Requirement.** Shell execution and the shell environment provider must live in a separate tools
module rather than in the harness proper, and the caller must assemble them manually.

**Upstream comparison**

- .NET: The shell is a separate package and the harness does not wire it automatically.
- Python: Keeps the shell in the tools package, and the harness can wire it automatically when it receives a `shell_executor`.

**Decision.** Java takes the .NET module boundary. The shell carries large host dependencies and a
large security explanation. Putting it in the harness proper would make the assembly layer take on
the responsibilities of the execution layer.

**Acceptance criteria**

- The shell execution types are defined in a separate module, not in the harness core.
- The default harness assembly does not add shell tools automatically.
- The shell environment provider can optionally be attached as a context provider.

**Evidence** [13 Shell environment / shell executors](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-019 The denylists of shell and local execution are documented as guardrails only

**Requirement.** The shell policy denylist and the local execution limits must not be claimed as a
security boundary, and must be documented on the premise of approval-based guardrails and
additional isolation.

**Upstream comparison**

- .NET: Treats the local shell approval loop as the security boundary and warns about the collision risk of the denylist.
- Python: Fixes with tests that `ShellPolicy` is a guardrail and not a security boundary.

**Decision.** As instructed by the user, this position is reflected exactly. A denylist is only a
device that reduces accidental mistakes; it does not fully block adversarial input. If the Java
documentation overstates it, hosts will place misplaced trust in it.

**Acceptance criteria**

- The public documentation states that the denylist and the policy are not a security boundary.
- Disabling approval for the local shell is not allowed without an explicit unsafe acknowledgement.
- The security description does not claim to replace additional isolation or the approval procedure.

**Evidence** [13 Shell environment / shell executors](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-020 LocalCodeAct is not treated as a sandbox and is excluded from the core

**Requirement.** Local subprocess code execution of the LocalCodeAct kind must be stated not to be a
sandbox and must be excluded from the Java core and the default harness.

**Upstream comparison**

- .NET: LocalCodeAct is preview and targets only environments that already have external isolation.
- Python: There is no implementation corresponding to LocalCodeAct in the inspected scope.

**Decision.** The safer default is taken. A local execution feature with a name that looks like a
sandbox invites the most dangerous misunderstanding. It is better not to provide it unless the
environment already has an external VM or container.

**Acceptance criteria**

- The core harness and the default distribution do not include a local subprocess code execution feature.
- The documentation states that this feature is not a sandbox.
- Even if a later module appears, it requires the external isolation precondition strongly in the types or the configuration documentation.

**Evidence** [13 LocalCodeAct](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## HAR-021 Sandboxed CodeAct backends are split into a separate optional module

**Requirement.** Code execution backends such as Hyperlight or Monty must be kept in an optional
module separate from the harness proper, and must enforce approval bundling and file staging safety
from the first implementation.

**Upstream comparison**

- .NET: Hyperlight is a preview sandbox backend and ties together the approval mode and a provider-owned tool registry.
- Python: Hyperlight and Monty are beta backends and fix mount, approval, and symlink-safe capture individually.

**Decision.** A sandbox backend is not a harness convenience feature but a separate execution layer.
Java splits the module out with a backend-first strategy and must enforce approval accounting and
safe staging early. Monty has no .NET parity either, which makes Optional even more appropriate.

**Acceptance criteria**

- Code execution backends are not included in the harness core dependencies.
- If any one of the provider-owned tools requires approval, `execute_code` requires approval too.
- Input staging and output capture block symlink or reparse point escape.

**Evidence** [13 Hyperlight CodeAct](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md), [13 Monty CodeAct](../upstream/snapshots/d0a4165f/features/13-skills-background-code.md)

---

## What this document does not cover

| Topic | Owning document |
| --- | --- |
| The general tool call loop and the default approval model | [04 Tool definitions and the tool call loop](04-tools.md) |
| Session serialization and stores | [06 Sessions and conversation state](06-sessions.md) |
| Interceptors and context compaction | [07 Interceptors and context management](07-interceptors.md) |
| Workflow graphs and the runtime | [09 Workflows and orchestration](09-workflows.md) |
| Hosting environments and protocol adapters | [10 Hosting and protocols](10-hosting.md) |
| Operational policy, observability, security operations | [11 Operational quality](11-operations.md) |
