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
