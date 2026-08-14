# Main History and Squash Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the noisy `main` history with eight meaningful linear commits and make squash
merge the repository's only enabled pull request merge strategy.

**Architecture:** Reuse seven verified historical tree objects instead of replaying intermediate
fixes, then append one policy tree containing the repository instruction and planning documents.
Keep the original remote tip under a local backup ref, verify tree identity before moving local
`main`, and configure GitHub independently without force-pushing.

**Tech Stack:** Git, GitHub CLI, Gradle Wrapper, Markdown

## Global Constraints

- Rewrite the complete local `main` history.
- The seventh rewritten tree must equal the original `1b6d7d5` tree.
- The final policy commit intentionally adds the repository instruction, design, and plan.
- Keep `backup/main-pre-squash-20260814` at the original `main` tip.
- Do not force-push or change AKS/ARC infrastructure.
- Enable squash merge; disable merge commits and rebase merge.
- Use the pull request title and body for the squash commit title and message.

---

### Task 1: Document the Squash Merge Policy

**Files:**
- Modify: `AGENTS.md`
- Reference: `docs/superpowers/specs/2026-08-14-main-history-and-squash-merge-design.md`

**Interfaces:**
- Consumes: The repository's existing review workflow and GitHub pull request process.
- Produces: A canonical instruction that future pull requests use squash merge.

- [ ] **Step 1: Add the merge policy after the standard workflow**

Add this section to `AGENTS.md` immediately before `## Review loop`:

```markdown
## Merge policy

Pull requests are merged with squash merge so `main` receives one coherent commit per pull request.
Intermediate branch commits may be used during development. Before merging, ensure the pull request
title describes the final squashed commit and the pull request body records the completed change.
Merge commits and rebase merges are not used.
```

- [ ] **Step 2: Run the repository policy verification**

Run:

```bash
./gradlew policyCheck
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit the policy**

```bash
git add AGENTS.md
git commit -m "docs: require squash merges for pull requests" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

Expected: one commit modifying only `AGENTS.md`.

### Task 2: Rebuild the Local Main History

**Files:**
- Modify: Git refs only
- Preserve: The complete working tree at the Task 1 tip

**Interfaces:**
- Consumes: Existing milestone commits `812192b`, `b4768a1`, `de93fea`, `046490b`, `5427279`,
  `eaf3bd5`, and `1b6d7d5`.
- Produces: A linear eight-commit `main` and `backup/main-pre-squash-20260814`.

- [ ] **Step 1: Confirm the worktree is clean and create the backup ref**

Run:

```bash
test -z "$(git status --porcelain)"
test "$(git rev-parse origin/main)" = "1b6d7d502b3ce14973e3b06baf0ba0633f1078bb"
git branch backup/main-pre-squash-20260814 origin/main
test "$(git rev-parse backup/main-pre-squash-20260814)" = "$(git rev-parse origin/main)"
```

Expected: every command exits successfully and the backup resolves to the unchanged remote tip.

- [ ] **Step 2: Construct the eight commits and create the temporary branch**

Run as one shell command:

```bash
POLICY_TIP=$(git rev-parse main)

commit_from_tree() {
  source=$1
  parent=$2
  subject=$3
  tree=$(git rev-parse "$source^{tree}")

  if [ -n "$parent" ]; then
    GIT_AUTHOR_NAME="$(git show -s --format=%an "$source")" \
    GIT_AUTHOR_EMAIL="$(git show -s --format=%ae "$source")" \
    GIT_AUTHOR_DATE="$(git show -s --format=%aI "$source")" \
    GIT_COMMITTER_NAME="$(git show -s --format=%cn "$source")" \
    GIT_COMMITTER_EMAIL="$(git show -s --format=%ce "$source")" \
    GIT_COMMITTER_DATE="$(git show -s --format=%cI "$source")" \
      git commit-tree "$tree" -p "$parent" -m "$subject"
  else
    GIT_AUTHOR_NAME="$(git show -s --format=%an "$source")" \
    GIT_AUTHOR_EMAIL="$(git show -s --format=%ae "$source")" \
    GIT_AUTHOR_DATE="$(git show -s --format=%aI "$source")" \
    GIT_COMMITTER_NAME="$(git show -s --format=%cn "$source")" \
    GIT_COMMITTER_EMAIL="$(git show -s --format=%ce "$source")" \
    GIT_COMMITTER_DATE="$(git show -s --format=%cI "$source")" \
      git commit-tree "$tree" -m "$subject"
  fi
}

C1=$(commit_from_tree 812192b "" "docs: establish the design and requirements baseline")
C2=$(commit_from_tree b4768a1 "$C1" "build: establish the Gradle engineering harness")
C3=$(commit_from_tree de93fea "$C2" "feat: bootstrap the product modules")
C4=$(commit_from_tree 046490b "$C3" "build: establish publication and release verification")
C5=$(commit_from_tree 5427279 "$C4" "build(deps): update the test tools")
C6=$(commit_from_tree eaf3bd5 "$C5" "docs: establish English as the documentation source")
C7=$(commit_from_tree 1b6d7d5 "$C6" "docs: define requirements-driven architecture")
C8=$(commit_from_tree "$POLICY_TIP" "$C7" "docs: define history and squash merge policy")
git branch history/reorganized-main "$C8"
```

Expected: `history/reorganized-main` names a new eight-commit history without changing the worktree
or `main`.

- [ ] **Step 3: Verify the constructed history before moving `main`**

Run:

```bash
test "$(git rev-list --count history/reorganized-main)" -eq 8
test -z "$(git rev-list --merges history/reorganized-main)"
test "$(git rev-parse history/reorganized-main~1^{tree})" = "$(git rev-parse 1b6d7d5^{tree})"
test "$(git rev-parse history/reorganized-main^{tree})" = "$(git rev-parse main^{tree})"
git diff --exit-code main history/reorganized-main
git log --reverse --format='%s' history/reorganized-main
```

Expected: all tests pass and the log lists the eight subjects from Step 2 in order.

- [ ] **Step 4: Move local `main` without resetting the worktree**

Run:

```bash
git switch history/reorganized-main
git branch -f main history/reorganized-main
git switch main
git branch -d history/reorganized-main
```

Expected: `main` points to the rewritten tip, the temporary branch is deleted, and the working tree remains
unchanged.

- [ ] **Step 5: Verify divergence is intentional and no force-push occurred**

Run:

```bash
git status --short --branch
git rev-list --left-right --count origin/main...main
git rev-parse backup/main-pre-squash-20260814
git rev-parse origin/main
```

Expected: the worktree is clean, local `main` and `origin/main` have intentionally diverged, and the
last two object IDs are identical.

### Task 3: Configure GitHub for Squash-Only Merges

**Files:**
- Modify: GitHub repository settings only

**Interfaces:**
- Consumes: Administrator access to `hellices/agent-framework-java`.
- Produces: Squash-only pull request merge settings using PR title and body.

- [ ] **Step 1: Apply the repository merge settings**

Run:

```bash
gh api --method PATCH repos/hellices/agent-framework-java \
  -F allow_squash_merge=true \
  -F allow_merge_commit=false \
  -F allow_rebase_merge=false \
  -f squash_merge_commit_title=PR_TITLE \
  -f squash_merge_commit_message=PR_BODY
```

Expected: the response reports `allow_squash_merge: true`, `allow_merge_commit: false`, and
`allow_rebase_merge: false`.

- [ ] **Step 2: Query and verify the persistent settings**

Run:

```bash
gh repo view hellices/agent-framework-java \
  --json mergeCommitAllowed,rebaseMergeAllowed,squashMergeAllowed \
  --jq '"mergeCommitAllowed=\(.mergeCommitAllowed)\nrebaseMergeAllowed=\(.rebaseMergeAllowed)\nsquashMergeAllowed=\(.squashMergeAllowed)"'
gh api repos/hellices/agent-framework-java \
  --jq '"squash_merge_commit_title=\(.squash_merge_commit_title)\nsquash_merge_commit_message=\(.squash_merge_commit_message)"'
```

Expected:

```text
mergeCommitAllowed=false
rebaseMergeAllowed=false
squashMergeAllowed=true
squash_merge_commit_title=PR_TITLE
squash_merge_commit_message=PR_BODY
```

### Task 4: Run Final Verification

**Files:**
- Verify: `AGENTS.md`
- Verify: `docs/superpowers/specs/2026-08-14-main-history-and-squash-merge-design.md`
- Verify: `docs/superpowers/plans/2026-08-14-main-history-and-squash-merge.md`

**Interfaces:**
- Consumes: The rewritten local history and GitHub repository settings.
- Produces: Evidence that the request is complete without publishing rewritten history.

- [ ] **Step 1: Run the required policy check on the rewritten branch**

Run:

```bash
./gradlew policyCheck
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Verify history, backup, tree, settings, and worktree**

Run:

```bash
test "$(git rev-list --count main)" -eq 8
test -z "$(git rev-list --merges main)"
test "$(git rev-parse main~1^{tree})" = "$(git rev-parse 1b6d7d5^{tree})"
test "$(git rev-parse backup/main-pre-squash-20260814)" = "$(git rev-parse origin/main)"
test -z "$(git status --porcelain)"
git log --graph --decorate --oneline --all -n 20
gh repo view hellices/agent-framework-java \
  --json mergeCommitAllowed,rebaseMergeAllowed,squashMergeAllowed
```

Expected: eight linear commits, a matching historical tree and backup ref, a clean worktree, and
squash as the only enabled merge strategy.

- [ ] **Step 3: Report the intentionally unexecuted publication command**

Report, but do not run:

```bash
git push --force-with-lease=main:1b6d7d502b3ce14973e3b06baf0ba0633f1078bb origin main
```

Explain that repository policy prohibits automatic force-pushes and that the command safely refuses
to overwrite `origin/main` if it moved after the rewrite began.
