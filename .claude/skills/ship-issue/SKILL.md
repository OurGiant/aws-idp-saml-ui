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

## Cutting a release (e.g. 1.7.0)

Separate from the per-issue patch bumps above, a deliberate decision:

1. **Write the release's "What's New" notes** (#210) as
   `src/main/resources/whats-new/<version>.html` (plain HTML: `<h2>Version
   x.y.z</h2>` first, then `<h3>`/`<ul>` sections, written for users, not
   commit titles) and add `<version>` to `whats-new/versions.txt`. The app shows
   them once after an upgrade, and `WhatsNewTest` fails if a listed version's
   notes are missing or untitled. Base them on `git log v<last-release>..main`.
2. Bump `pom.xml` to the release version in the same PR, then merge it.
3. Tag `v<version>` on main and push the tag; `build.yml` builds and
   publishes the installers for tags.
