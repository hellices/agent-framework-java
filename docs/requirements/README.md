# Agent Framework for Java requirements

This directory defines **what** the Java implementation has to build.

The upstream analysis documents (`docs/upstream/snapshots/d0a4165f/`) are research records of
**how** Microsoft Agent Framework is built. These requirement documents take that research as
input and are the artifact that settles the Java decisions. The two have different roles.

| Document | Question | Nature |
| --- | --- | --- |
| `docs/upstream/snapshots/d0a4165f/` | How does upstream work | Research record |
| `docs/requirements/` | What does Java have to build | Implementation contract |

## Requirement documents

| Document | Prefix | Scope | Requirements | Upstream features |
| --- | --- | --- | ---: | --- |
| [01 Agent execution and model calls](01-agent-execution.md) | `AGT` | Agent lifecycle, run entry points, model calls, streaming | 16 | 01, 03 |
| [02 Messages and the content model](02-message-content.md) | `MSG` | Roles, content kinds, responses, usage, finish reasons | 13 | 02 |
| [03 Structured output](03-structured-output.md) | `OUT` | Schema requests, native and fallback paths, parse failures | 12 | 04 |
| [04 Tool definitions and the tool call loop](04-tools.md) | `TOOL` | Tool schemas, the call loop, budgets, approvals | 21 | 05, 06 |
| [05 MCP integration](05-mcp.md) | `MCP` | MCP client tools, server hosting | 19 | 07, 24 |
| [06 Sessions and conversation state](06-sessions.md) | `SES` | Session identity, serialization, stores, history, context | 20 | 08, 09 |
| [07 Interceptors and context management](07-interceptors.md) | `INT` | Run interceptors, compaction | 22 | 10, 11 |
| [08 Harness features](08-harness.md) | `HAR` | Harness assembly, providers, skills, code execution | 21 | 12, 13 |
| [09 Workflows and orchestration](09-workflows.md) | `WF` | Graphs, runtime, checkpoints, composition, declarative form | 35 | 14, 15, 16, 17, 18, 19 |
| [10 Hosting and protocols](10-hosting.md) | `HOST` | Hosting core, Responses, A2A, AG-UI, identity | 29 | 20, 21, 22, 23, 25, 26 |
| [11 Operational quality](11-operations.md) | `OPS` | Observability, errors, security, evaluation, packaging | 26 | 27, 28, 29, 30 |
| [12 Provider integrations](12-providers.md) | `PRV` | Model providers, infrastructure adapters | 10 | 31 |

All 244 requirements together cover all 31 upstream features. The number of documents and the
number of features do not have to match. One requirement document can cover several features, and
one feature can produce several requirements.

## Requirement ids

Every requirement has a fixed id in the form `<prefix>-<three digits>`, for example `AGT-001`.

- Ids are never reused. When a requirement is retired, its id stays behind in the `retired` state.
- Tests, commits, and planning documents refer to a requirement by its id.
- A new requirement uses the number after the last one in its document.

## Requirement grades

| Grade | Meaning |
| --- | --- |
| Required | The feature is not released without this requirement. |
| Recommended | It may be implemented differently when a reasonable justification is recorded. |
| Optional | Implement it when it is needed. The release ships without it. |

A grade states **how binding the requirement is once the feature has been chosen for
implementation**; it does not state that the feature has to be implemented at all. Whether the
feature itself gets built is decided by the release phases below.

Mixing the two axes lets scope grow silently. Compaction, for example, is a feature the
[compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md) judged `Optional`,
so a release can ship without it. But once it is built, it must not break the atomicity of a tool
call and its result, and that condition is `Required`.

The summary table in each document records both of the following.

- **Adoption** — whether Java is going to build that feature group. It follows
  `Required`/`Optional`/`Deferred` in the matrix.
- **Grade** — how binding this requirement is once the decision to build it has been made.

Raising a feature group the matrix judged `Optional` or `Deferred` to adoption `Required` requires
the reason to be written down in the document.

## Release phases

| Phase | Scope |
| --- | --- |
| `MVP` | The minimum contract needed to run a single agent |
| `Core+` | Added to the core once the MVP is stable |
| `Workflow` | The workflow subproject |
| `Hosting` | Hosting and protocol adapters |
| `Optional` | Scope that can be split out into an independent artifact |

## The shape of a requirement

Every requirement has the following five parts.

1. **Requirement** — one sentence on what Java has to do. It is stated definitively, as `must`
   or `does not`.
2. **Upstream comparison** — one line each on what .NET and Python do. `Same` when there is no
   difference.
3. **Decision** — why the decision was made. When the two upstreams differ, which side was chosen
   and why.
4. **Acceptance criteria** — conditions whose pass or fail can be judged mechanically.
5. **Evidence** — links to the upstream analysis documents. Permalinks into upstream sources are
   already kept by the analysis documents, so they are not repeated here.

## Decision rules

When the two upstream implementations differ, the decision follows this order.

1. **Code beats documentation.** A feature that exists only in documentation does not become a
   requirement.
2. **Behavior pinned by a test wins.** When both sides have tests, the stricter side is chosen.
3. **Choose the safer default.** The side that prevents leaking sensitive data, unbounded loops,
   and silent failures.
4. **Follow Java ecosystem conventions.** Compatibility of observable behavior comes before
   upstream API names.
5. **Do not blur boundaries.** `AgentEngine` does not take over the responsibilities of the host
   runtime.

Java public APIs and extension points also follow the
[Java API and extension principles](java-api-principles.md). Even when observable upstream
semantics stay the same, Java does not copy unsafe implementation forms such as null conveniences,
dynamic dictionaries, implicit global registries, or arbitrary class restoration.

A feature neither upstream provides does not become a requirement. When it is needed, it is
proposed as a separate design.

## Related documents

- [Foundation design and roadmap](../design/foundation-design.md)
- [Java API and extension principles](java-api-principles.md)
- [Java idiom and extensibility audit](java-api-audit.md)
- [Upstream snapshot analysis](../upstream/snapshots/d0a4165f/README.md)
- [Per-feature compatibility matrix](../upstream/snapshots/d0a4165f/compatibility-matrix.md)
- [Documentation index](../README.md)
