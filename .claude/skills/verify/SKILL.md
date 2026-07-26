---
name: verify
description: How to build, launch, and drive this Java Swing app (aws-idp-saml-ui) to verify a change actually works, including this environment's specific gotchas (Docker-only Maven, no screenshot capture, modal-dialog deadlocks, shared real display).
---

# Verifying changes in aws-idp-saml-ui

This is a Java Swing desktop app. Verifying a change means actually launching it
and driving the real UI — not just `mvn test`. This doc captures the gotchas
that cost real time to discover.

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
session, not an isolated headless one. Treat it accordingly (see §5).

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
specific window and the full screen — confirmed empirically, not a bug in the
capture code. This is the Wayland compositor blocking legacy X11 screen
capture. **Don't spend time debugging this — it doesn't work in this
environment.** Robot's mouse/keyboard input synthesis is a different subsystem
and may work independently; test it, but don't assume — see §4 for a more
reliable alternative.

Verify visually-oriented changes via:
- Live component introspection (reflection into field values, `.getText()` on
  labels/buttons, table cell values) instead of pixels.
- The app's own log output (SLF4J via logback, prints to stdout).
- Extracting and viewing a generated image *asset* (e.g. an icon file) directly
  with the Read tool — that's not a screen capture, it's just reading a file.

## 4. Driving the UI programmatically

Write a small standalone verification harness (a plain `.java` file in the
scratchpad dir, compiled against the jar) rather than trying to interact
manually:

```bash
javac -cp target/aws-idp-saml-ui-all.jar VerifyThing.java
DISPLAY=:1 java -cp .:target/aws-idp-saml-ui-all.jar VerifyThing <fakehome> <args>
```

Inside the harness:
- Set `System.setProperty("user.home", fakeHome)` **before** touching any app
  class.
- Construct the real app class on the EDT: `SwingUtilities.invokeAndWait(() -> { win[0] = new SwingMain(); win[0].setVisible(true); });`
- Use reflection (`getDeclaredField(...).setAccessible(true)`) to reach private
  fields on the live instance — components, managers, flags.
- Click buttons with `button.doClick()`.
- For rows/cells with no simple click method (e.g. right-clicking a `JTable`
  row to trigger a context menu), dispatch a synthetic `MouseEvent` directly:
  ```java
  Rectangle r = table.getCellRect(row, col, true);   // LOCAL coords, not screen
  MouseEvent press = new MouseEvent(table, MouseEvent.MOUSE_PRESSED,
      System.currentTimeMillis(), 0, r.x + r.width/2, r.y + r.height/2,
      1, /*popupTrigger=*/true, MouseEvent.BUTTON3);
  table.dispatchEvent(press);
  table.dispatchEvent(/* matching MOUSE_RELEASED */);
  ```
  `dispatchEvent` reaches real registered listeners reliably.
  `java.awt.Robot`-based OS-level clicks were found **unreliable** for input
  delivery in this environment (screenshots aside) — prefer `dispatchEvent`.

### The modal-dialog deadlock (cost real time — avoid it)

Never do this when the click opens a **modal** dialog
(`JOptionPane.showConfirmDialog`, `showOptionDialog`, etc.):

```java
SwingUtilities.invokeAndWait(button::doClick);   // DEADLOCKS if this opens a modal dialog
```

The modal dialog pumps its own nested event loop that only returns once
dismissed — nothing can dismiss it because the thread that would is the one
blocked in `invokeAndWait`. Instead:

```java
SwingUtilities.invokeLater(button::doClick);
Thread.sleep(400);                                // let it open
JDialog dlg = findVisibleDialog();                // poll Window.getWindows()
// ...read/interact with dlg...
SwingUtilities.invokeAndWait(dlg::dispose);        // fine once it's a separate call
```

### Timing: don't trust a short polling window

If you poll for a state change with a timeout and it doesn't arrive, that's
evidence of "my poll window was too short," not "the operation completed
around when I gave up." Prefer generous timeouts (tens of seconds, not
hundreds of ms, for anything involving a real network/browser round trip) with
periodic heartbeat logging, so a genuine hang is distinguishable from a slow
success.

## 5. Process safety — this is a shared, real desktop

This session's display is the user's real one. Real apps (their actual
browser, etc.) may already be running on it.

- **Never** `pkill -f firefox` / `pkill -f chrome` / any broad process-name
  match to clean up a stuck test — it can kill the user's real browser
  session. This happened once; it didn't cause damage, but only by luck.
- Track the exact PID of anything you launch (e.g. via
  `ProcessHandle.allProcesses()` filtered to descendants of your own JVM's
  PID, or a driver-reported PID like Selenium's `moz:processID` capability)
  and kill **only that PID**.
- For a stuck harness process itself (not a browser it launched), killing by
  its own specific PID or a highly-specific class-name match
  (`pkill -f VerifyThing`) is safe — it can't collide with anything else.

## 6. Background runs

Launching the app or a harness can take a while (browser startup, network).
Use `run_in_background: true` and wait for the completion notification rather
than polling with sleep loops. Wrap anything that could hang (e.g. due to the
modal-dialog deadlock above) in a shell `timeout N` as a safety net so a bug
in the harness can't hang the session indefinitely.
