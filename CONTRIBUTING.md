# Contributing

Before changing code, read `AGENTS.md`, the approved designs in `docs/design/`, the requirements in
`docs/requirements/`, and the pinned upstream guidance in `docs/upstream/`.

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
