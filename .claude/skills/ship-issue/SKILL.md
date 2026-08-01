---
name: ship-issue
description: The standard workflow for shipping a bug fix or feature to aws-idp-saml-ui — file a GitHub issue, branch off main, implement, verify, bump the patch version, and open a PR. Use whenever picking up a bug fix or feature for this repo.
---

# Shipping a change to aws-idp-saml-ui

This repo follows the generic `java-swing-ship-issue` workflow: file → branch
→ implement → verify → **bump the patch version** → PR. See that skill for
the full mechanics (Docker/Maven commands, why `pom.xml` is the only version
file to touch, the PR/CI steps). This file covers only what's specific to
this repo.

## Project-specific facts

- **Docker path**: `/projects/aws-idp-saml-ui` inside the Maven container
  (see `java-swing-project-setup` §2 for finding/starting the container —
  its name drifts across sessions).
- **Branch naming**: `fix/<issue#>-<slug>` / `feature/<issue#>-<slug>` (see
  `git branch -a` for existing examples) — always include the issue number.
- **Verifying UI changes**: use this repo's own `verify` skill for the
  specifics of this dev setup (jar path, `-Duser.home` isolation, `samlsts`
  fixture format), plus `verify-java-swing` for the underlying techniques.
  Per `java-swing-ship-issue` §5, don't stop at `mvn test` if the change
  touches a window/dialog/menu, or bumps any `pom.xml` dependency version —
  even one that looks UI-unrelated.

No other repo-specific PR-checklist items beyond the generic workflow.
