# Microsoft Agent Framework design baseline

This directory tracks the behavior and the design that the Java implementation needs, anchored on
one specific upstream commit of the Microsoft Agent Framework (MAF).

## Current baseline

- Upstream: <https://github.com/microsoft/agent-framework>
- Branch: `main`
- Commit: `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- Commit time: 2026-08-10 05:51:59 UTC

## Analysis priority

When two sources describe the same feature differently, judge them in the following order.

1. Production source at the pinned commit
2. Unit, integration, and conformance tests at the pinned commit
3. Specifications and feature documents inside the pinned commit
4. Official documentation on Microsoft Learn
5. Samples and READMEs

A feature that exists only in documentation and cannot be confirmed in code and tests is not
treated as implemented. When the .NET and Python implementations differ, neither side is picked
as the standard arbitrarily: the difference is recorded and then separated out as a Java design
decision.

## Document set

- [Snapshot index](snapshots/d0a4165f/README.md)
- [Snapshot manifest](snapshots/d0a4165f/snapshot-manifest.md)
- [Coverage ledger](snapshots/d0a4165f/coverage-ledger.md)
- [Compatibility matrix](snapshots/d0a4165f/compatibility-matrix.md)
- [Documentation index](../README.md)

The 31 feature documents are navigated from the snapshot index, grouped by feature group, and the
per-language feature matrix and the Java compatibility decisions take the coverage ledger and the
compatibility matrix together as their baseline.

## Update policy

Moving to a new upstream baseline does not overwrite the existing snapshot documents. A new commit
directory is added, and the feature and behavior differences between the two commits are recorded
in a separate delta document. This keeps it reproducible which upstream state the Java
implementation supports.
