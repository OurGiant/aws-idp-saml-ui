---
name: verify
description: How to build, launch, and drive this Java Swing app (aws-idp-saml-ui) to verify a change actually works — this repo's Docker/build specifics, launch/config setup, and confirmed environment gotchas. See verify-java-swing for the general Swing-verification techniques this file doesn't repeat.
---

# Verifying changes in aws-idp-saml-ui

This is a Java Swing desktop app. Verifying a change means actually launching
it and driving the real UI — not just `mvn test`. This file covers what's
specific to this repo's setup; see `verify-java-swing` for the general
Swing-verification techniques (reflection/`dispatchEvent`, the modal-dialog
`invokeAndWait` deadlock, timing, process safety on a shared display) used
below.

## 1. Build (Maven only runs inside Docker)

Maven isn't available on the host. Find/start the build container:

```bash
docker ps -a --format '{{.Names}} {{.Status}} {{.Image}}'   # find a maven-* container
docker start <container-name>                                # if stopped
docker exec <container-name> bash -lc "cd /projects/aws-idp-saml-ui && mvn -q clean package -DskipTests"
```

The container bind-mounts the host repo (commonly `/home/ryanleach/projects` →
`/projects`), so edits made on the host are visible for the build.

**Known quirk:** the bind mount can lag — the container sometimes serves a stale
version of a just-edited file (confirmed on `pom.xml` and `.java` files),
likely VirtioFS/gRPC-FUSE cache staleness. If a build doesn't reflect a recent
edit, verify with `docker exec <container> grep <marker> <path>`; if stale,
force a sync with `docker cp <host-file> <container>:<container-path>` before
rebuilding.

The built uber-jar lands at `target/aws-idp-saml-ui-all.jar`, visible on the
host via the same mount.

## 2. Launching against a real display

`DISPLAY=:1` is a **real, shared X11 display** — the user's actual desktop
session, not an isolated headless one (see `verify-java-swing` §5 for the
process-safety rules that follow from that).

**Always isolate from the user's real data** with `-Duser.home=<fake-home>`:

```bash
FAKEHOME=/path/to/scratch/fakehome
mkdir -p "$FAKEHOME/.aws"
cat > "$FAKEHOME/.aws/samlsts" <<'EOF'
[global]
idp_entry_url = https://example.okta.com/app/example/sso/saml
username =

[some-profile]
aws_account_id = 111111111111
role_arn = arn:aws:iam::111111111111:role/TestRole
EOF

DISPLAY=:1 java -Duser.home="$FAKEHOME" -jar target/aws-idp-saml-ui-all.jar
```

A `samlsts` file must exist or the app shows the first-run setup dialog instead
of the main window. To test a profile with saved credentials but no `samlsts`
entry (reproduces real bugs — see the context-menu fix in PR #86), also write
`$FAKEHOME/.aws/credentials` directly with an INI section per profile
(`aws_access_key_id`/`aws_secret_access_key`/`aws_session_token`).

## 3. No screenshots are possible here

`java.awt.Robot.createScreenCapture(...)` returns solid black for both a
specific window and the full screen — confirmed empirically in this
environment, consistent with `verify-java-swing` §1's Wayland explanation.
**Don't spend time debugging this — it doesn't work here.** Verify visually
via live component introspection, log output, or reading a generated image
*asset* directly — see `verify-java-swing` §1.

## 4. Driving the UI programmatically

Write a small standalone verification harness (a plain `.java` file in the
scratchpad dir, compiled against the jar):

```bash
javac -cp target/aws-idp-saml-ui-all.jar VerifyThing.java
DISPLAY=:1 java -cp .:target/aws-idp-saml-ui-all.jar VerifyThing <fakehome> <args>
```

Inside the harness:
- Set `System.setProperty("user.home", fakeHome)` **before** touching any app
  class.
- Construct the real app class (`import com.ourgiant.saml.gui.SwingMain;`) on
  the EDT:
  `SwingUtilities.invokeAndWait(() -> { win[0] = new SwingMain(); win[0].setVisible(true); });`

See `verify-java-swing` §2–4 for the general techniques used from here:
reflection into private fields, `button.doClick()`, synthetic `MouseEvent`
dispatch for context menus, the modal-dialog `invokeAndWait` deadlock, and
generous polling timeouts.

## 5. Process safety and background runs

This session's display is the user's real, shared desktop — see
`verify-java-swing` §5 for the process-cleanup rules (never a broad
`pkill -f <browser>`; track exact PIDs). Launching the app or a harness can
take a while; use `run_in_background: true` rather than polling, and wrap
anything that could hang in a shell `timeout N` as a safety net.
