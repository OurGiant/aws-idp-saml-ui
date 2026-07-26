---
name: ship-issue
description: The standard workflow for shipping a bug fix or feature to aws-idp-saml-ui — file a GitHub issue, branch off main, implement, verify, bump the patch version, and open a PR. Use whenever picking up a bug fix or feature for this repo. Covers where Maven/tests run vs where the app runs, the per-issue patch-version-bump convention, and the PR checklist.
---

# Shipping a change to aws-idp-saml-ui

The workflow this repo follows for every issue: file → branch → implement →
verify → **bump the patch version** → PR. The version-bump step is the one easy
to forget since nothing enforces it — it's a convention, not a build check.

## 1. File the issue

`gh issue create --title "..." --body "..."` — describe the bug/feature and,
for a bug, the root cause if already known. This becomes the PR's `fixes #N`
reference later.

## 2. Branch off up-to-date main

```bash
git checkout main && git pull --ff-only
git checkout -b fix/N-short-description   # or feature/N-short-description
```

This repo's existing branches follow `feature/<issue#>-<slug>` /
`fix/<issue#>-<slug>` (see `git branch -a`) — include the issue number.

If another PR merged since you last synced, `git pull --ff-only` catches
that before you branch — don't skip it.

## 3. Implement

Normal edits. Check `git status` before anything destructive per the usual
git safety rules.

## 4. Test and build

Maven only exists in a Docker container, not on the host — find it, don't
assume a fixed name (it varies by session):

```bash
docker ps -a --format '{{.Names}} {{.Status}} {{.Image}}'   # find a maven-* container
docker start <container-name>                                # if stopped
docker exec <container-name> bash -lc "cd /projects/aws-idp-saml-ui && mvn -q test"
docker exec <container-name> bash -lc "cd /projects/aws-idp-saml-ui && mvn -q package -DskipTests"
```

The container bind-mounts this host's `~/projects` directory to `/projects`.

## 5. Verify Swing UI changes for real, not just via tests

If the change touches a window/dialog/menu, don't stop at `mvn test` —
launch the built jar and drive the real UI. See this repo's own `verify`
skill for the specifics of this dev setup (build-in-container-run-on-host,
why screenshots don't work here, isolating `-Duser.home` from the real
`.aws` directory), and the more general `verify-java-swing` skill for the
underlying techniques (modal-dialog `invokeAndWait` deadlock, synthetic
`MouseEvent` dispatch, process safety on a shared display).

## 6. Bump the patch version

**Every issue-fixing PR bumps `pom.xml`'s `<version>` patch component by
one** — `M.m.X` → `M.m.(X+1)`, e.g. `1.2.0` → `1.2.1`. This lands in the
same PR as the fix/feature, not as a separate release PR.

```xml
<!-- pom.xml -->
<version>1.2.1</version>
```

`pom.xml` is the only file to touch. The app's displayed version
(`SwingMain.resolveCurrentVersion()`, shown in the About dialog) reads the
JAR manifest's `Implementation-Version` at runtime, which Maven's jar
plugin populates from `pom.xml` at build time — falling back to
`src/main/resources/version.properties`, which is itself Maven-filtered
from `${project.version}` (see the resource-filtering split in `pom.xml`'s
`<build><resources>` section, which filters only that one file). There's no
second place to edit, and no other file hardcodes the current version — a
version string in `SwingMainVersionComparisonTest` is just an arbitrary
fixture for `isNewerVersion()`/`extractJsonString()` comparison logic,
unrelated to the real app version. Don't "fix" it to match.

**Skip this step** for pure housekeeping that isn't shipping a user-facing
change (e.g. branch cleanup, a memory/doc-only update, CI config tweaks
with no issue behind them).

**This is patch-only.** Bumping the minor or major version (`M.m` itself,
or resetting the patch number back to 0 for a real numbered release) is a
separate, deliberate decision the user makes when actually cutting a
release — not something this per-issue convention decides on its own.

## 7. Commit, push, open the PR

Commit message references the issue (`fixes #N` or `(fixes #N)` in the
subject). Push, then:

```bash
gh pr create --title "..." --body "$(cat <<'EOF'
## Summary
- Fixes #N: ...

## Test plan
- [x] ...
EOF
)"
```

## 8. Watch CI, then stop and wait

```bash
gh pr checks <N> --watch --interval 30
```

This is a real network call that can take a few minutes — run it via the
Bash tool's `run_in_background`, don't poll it manually. Report the PR as
ready once green. **Never merge without the user explicitly saying so for
that specific PR** — reporting "ready to merge" is not the same as
authorization to merge it.
