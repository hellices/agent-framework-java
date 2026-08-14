# Main History and Squash Merge Design

## Goal

Replace the noisy 108-commit `main` history with a small linear history that preserves meaningful
repository milestones, then add one policy commit that makes squash merging the only merge strategy
enabled for future pull requests.

## Scope

- Rewrite the complete local `main` history.
- Preserve the current `main` file tree exactly.
- Keep a local backup ref to the original history.
- Document squash merge as the repository policy.
- Configure the GitHub repository to allow squash merges and reject merge-commit and rebase merges.
- Do not force-push. The repository instructions prohibit automatic force-pushes.

## History Model

Build the historical portion of a new linear history from seven existing milestone trees:

1. `812192b`: establish the design and requirements baseline.
2. `b4768a1`: establish the Gradle engineering harness.
3. `de93fea`: bootstrap the product modules.
4. `046490b`: establish publication, signing, and release verification.
5. `5427279`: update the test tool dependencies.
6. `eaf3bd5`: establish English documentation as the source policy.
7. `1b6d7d5`: establish requirements-driven architecture and namespace policy.

Each historical commit uses the corresponding existing tree, so every milestone is represented by a
reviewable snapshot rather than by replaying intermediate fixes. The seventh rewritten tree must be
identical to the original `1b6d7d5` tree. Add one final policy commit containing the repository
instruction, design, and implementation plan; this intentionally changes the final repository tree.

## Safety

Create `backup/main-pre-squash-20260814` at the original `main` tip before moving any branch ref.
Construct the rewritten history on a temporary branch, verify it, and only then move local `main`.
Keep `origin/main` unchanged. Report the exact manual `git push --force-with-lease` command required
to publish the rewritten history, but do not run it.

## Repository Merge Policy

Add a repository instruction stating that pull requests are merged with squash merge so `main`
receives one coherent commit per pull request. Branch authors may use intermediate commits during
development, but the pull request title and description must describe the final squashed change.

Configure GitHub with:

- squash merge enabled;
- merge commits disabled;
- rebase merge disabled;
- squash commit title derived from the pull request title;
- squash commit message derived from the pull request body.

## Verification

1. Compare the original `1b6d7d5` tree with the seventh rewritten tree object ID.
2. Confirm the rewritten history is linear and contains the seven historical commits plus one policy
   commit.
3. Run `./gradlew policyCheck` because repository instructions change.
4. Query the GitHub repository settings and confirm only squash merge is enabled.
5. Confirm the backup ref still resolves to the original `main` tip.
