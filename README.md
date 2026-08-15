# AWS IDP SAML UI Client

[![Build and Release](https://github.com/OurGiant/aws-idp-saml-ui/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/OurGiant/aws-idp-saml-ui/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/OurGiant/aws-idp-saml-ui)](https://github.com/OurGiant/aws-idp-saml-ui/releases/latest)
[![License: MIT](https://img.shields.io/github/license/OurGiant/aws-idp-saml-ui)](LICENSE)
[![Java](https://img.shields.io/badge/Java-24-orange)](https://adoptium.net/)

A Java Swing desktop client for SAML-based AWS federation. It drives the browser
login flow against your identity provider (Okta, Azure AD, ADFS, Ping Identity,
OneLogin, or any other SAML 2.0 IdP), exchanges the resulting assertion for
temporary AWS credentials via STS, and gives you a single window to manage every
profile you federate into — credentials, session expiration, and everything
that touches AWS console/CLI access.

## Features

**Authentication & profiles**
- Browser-driven SAML login (Selenium WebDriver, Chrome or Firefox) against any SAML 2.0 identity provider
- Okta FastPass support for password-less, device-based login
- Guided first-run setup wizard with presets for Okta, Azure AD, ADFS, Ping Identity, and OneLogin
- Manage any number of AWS profiles — add, edit, rename, and delete from the UI, backed by a standard `samlsts` INI config file
- One-click AWS Management Console launch via the STS federation endpoint

**Credential & token management**
- Live credential status table with per-profile expiration and time remaining
- Filter the table by name and by status (Valid / Expired / Unknown), independently or combined
- Background auto-refresh (every 30s) plus a manual Refresh Status button
- System tray notifications before a profile's credentials expire
- Encrypted, hybrid AES/RSA credential export for handing session credentials to other tooling (e.g. automated deployment scripts) without exposing them in plain text
- Automatic database cleanup: profiles removed from `samlsts` are pruned from local storage after a grace period, with a "Force Refresh" option in Settings to prune immediately

**Interface**
- FlatLaf-based UI with multiple light/dark themes
- System tray integration, including start-minimized-to-tray and a macOS Dock reopen fallback
- Silent background check for new releases, with an in-app prompt when one is available

**Security**
- Okta password storage is encrypted at rest with a configurable expiration, never stored in plain text
- The credentials file and local database are locked to owner-only permissions on disk
- SAML/XML parsing is hardened against XXE injection; AWS role-selection lookups are hardened against XPath injection
- See [Security](#security) below for the reasoning behind these choices and what's deliberately out of scope

**Distribution**
- Native installers published on every tagged release: a Windows zip (jpackage app image), a macOS `.dmg` (Intel and Apple Silicon), and a Linux `.deb`
- Nightly build of `main` published as a rolling pre-release for early testing

## Download

Prebuilt installers for Windows, macOS, and Linux are published on the
[Releases page](https://github.com/OurGiant/aws-idp-saml-ui/releases). Grab the
latest tagged release for a stable build, or the
[nightly build](https://github.com/OurGiant/aws-idp-saml-ui/releases/tag/nightly)
to try the latest `main`.

Alternatively, download `aws-idp-saml-ui-all.jar` from any release and run it
directly (see [Usage](#usage)) — this works on any platform with Java 24+
installed and doesn't require an installer.

## Prerequisites

- Java 24 or higher (only needed if running the jar directly or building from source — the platform installers bundle their own runtime)
- Maven 3.6+ (build from source only)
- A Chrome or Firefox browser, for the SAML login flow
- Network access to your identity provider and to AWS

## Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/OurGiant/aws-idp-saml-ui.git
   cd aws-idp-saml-ui
   ```

2. Build and package:
   ```bash
   mvn clean package
   ```

   This produces `target/aws-idp-saml-ui-all.jar`, a self-contained uber-jar.

## Usage

Run the application:
```bash
java -jar target/aws-idp-saml-ui-all.jar
```

On first launch, a setup wizard walks you through configuring your identity
provider and your first AWS profile, and writes the result to `~/.aws/samlsts`.

### Main Interface

- **Select Profile**: choose which configured AWS profile to act on
- **Fast Pass**: toggle Okta FastPass (device-based) login for the selected profile
- **Request Credentials**: run the SAML login flow and obtain temporary AWS credentials
- **Open Console**: launch the AWS Management Console in your browser using the current profile's credentials
- **Credential Status table**: shows every known profile with its status, expiration time, and time remaining; filter by profile name and/or status, right-click a row for quick actions
- **Configuration**: session duration, password storage, theme, browser, notifications, startup behavior, and a Force Refresh action to immediately clean up stale profiles

## Configuration

Profiles and identity provider settings live in `~/.aws/samlsts`, a standard
INI file (`[global]` section for defaults, one section per profile, one
`Fed-*` section per identity provider). It's normally managed entirely through
the UI — the Profile Manager and Configuration dialogs — but can also be
hand-edited; the app picks up profile changes on its next status refresh.

Application settings (session duration, theme, stored password, last-used
profile, etc.) and per-profile token state are kept in a local SQLite database
at `~/.aws/aws_saml.db`, restricted to owner-only file permissions.

## Sharing Credentials with Other Tools

The Credentials dialog can export the active session's AWS credentials as an
encrypted, hybrid AES/RSA-encoded string suitable for pasting into another
tool's input (for example, an automated deployment script) without exposing
the raw access key, secret key, or session token.

## Security

This app handles two categories of sensitive data — the Okta password (if
you opt into storing it) and temporary AWS credentials/SAML tokens — so this
section documents the reasoning behind how each is stored and handled, not
just what the code does.

**Okta password at rest** (`PasswordManager`): encrypted with AES-256-GCM, a
random 96-bit IV per encryption, before being written to the local SQLite
database. GCM was chosen over a non-authenticated mode (e.g. CBC) so that a
tampered ciphertext fails to decrypt instead of silently producing garbage.
Storage is opt-in and time-boxed: the configurable expiration means a
compromised or stale ciphertext has a bounded useful lifetime rather than
living forever. The encryption key itself is generated once and stored in
the same database it protects — this is a deliberate, bounded tradeoff, not
an oversight: it defends against the database file being read out of
context (a backup, a copy handed to someone else, a different OS user
account on a shared machine subject to the file permissions below), but
**not** against an attacker who already has this OS user's own access,
since the key sits right next to what it encrypts. There's no OS
keychain/TPM-backed key storage or passphrase-derived key — introducing one
would need to justify the added complexity (key rotation, recovery when a
passphrase is forgotten) against a threat model where the primary asset at
risk is a short-lived IdP password, not the AWS credentials themselves.

**File permissions** (`FilePermissions`): `~/.aws/credentials` and the local
`~/.aws/aws_saml.db` database are restricted to owner-only access
(`rw-------` / POSIX mode 0600 where supported, an equivalent
readable/writable-only-by-owner ACL elsewhere), mirroring how the AWS CLI
itself treats `~/.aws/credentials`. `~/.aws/samlsts` (IdP URLs, account IDs,
role ARNs — no secrets) is intentionally left at the OS default, since it
holds no credential material worth locking down further.

**Cross-tool credential export** (`CredentialsDialog`): exporting the active
session for another tool encrypts it with a hybrid scheme — a fresh,
per-export random AES-256 key (CBC/PKCS5Padding) encrypts the credentials,
and that one-time AES key is itself wrapped with RSA-OAEP (SHA-256, MGF1-SHA256)
under a recipient public key read from `~/.aws/public_key.pem`. This exact
algorithm/mode/padding combination is pinned deliberately, not just "some
AES/RSA scheme": it's byte-for-byte compatible with the sibling Python
tooling (the Python desktop app and `python-aws-deployer`) that decrypts the
same export format on the other end. Because of that, **any change to this
encode/decode path is a cross-repo change** — it must ship in lockstep
across all three implementations, or exports produced by one become
unreadable by the others.

**SAML/XML parsing** (`SamlParser`): the SAML response arrives over the
network from the IdP and is treated as untrusted input accordingly. DOCTYPE
declarations are rejected outright and external general/parameter entity
resolution is disabled before any parsing happens — legitimate SAML
responses never contain a DOCTYPE, so disallowing it entirely closes off XXE
(XML external entity) injection with no legitimate-use tradeoff.

**AWS role-selection lookups** (`BrowserLoginHandler`): the AWS account
number and IAM role name are attacker-influenceable (they come from the
SAML assertion) and were previously interpolated directly into an XPath
expression used to locate the matching role element in the browser DOM,
allowing a crafted assertion to break out of the intended query. XPath 1.0
has no native string-escaping mechanism, so values are instead built into
safe XPath string literals (`toXPathLiteral`, splitting on embedded quote
characters and concatenating them back together) before being placed into
the query.

**Login-failure diagnostics** (`BrowserLoginHandler`): when a browser login
step fails, the app logs the page's URL/title and saves a screenshot to
`~/.aws/login-failure-screenshots/` — the same capture-on-failure technique
the sibling Python app's `ScreenshotRecorder` uses — so a failure caused by
an unexpected page (a device-trust check, a bot-check, an IdP redirect hop)
is something a user or support engineer can actually look at rather than
inferring blind from a timeout message. This only fires on an actual
failure, not routinely, and a corporate SSO page can itself contain
identifying information (company branding, the logged-in username) worth
being aware of — saved screenshots get the same owner-only file permissions
as the credentials file.

**Explicitly out of scope**: this app doesn't attempt to defend against a
compromise of the local OS user account it runs as — file permissions and
the encryption above raise the bar against *other* accounts, processes, or
copies of these files, not against an attacker already inside this session.
Temporary AWS credentials and SAML tokens are time-bound by AWS STS/the IdP
itself; this app doesn't add its own independent revocation or shortening
of those lifetimes.

## Dependencies

- **Selenium WebDriver**: browser automation for the SAML login flow
- **AWS SDK for Java (STS)**: exchanging SAML assertions for temporary credentials
- **Apache Santuario (XML Security)** / hardened DOM parsing: SAML assertion processing
- **SQLite JDBC**: local database storage
- **ini4j**: `samlsts` config file parsing
- **FlatLaf** (+ IntelliJ themes, extras): modern Swing look and feel
- **SLF4J / Logback**: logging
- **Apache Commons Lang / Codec**: utility functions

## Development

### Building and Testing

```bash
mvn clean install  # build, including tests
mvn test            # run the test suite only
```

Tests use JUnit 5 and Mockito.

### Project Structure

```
src/main/java/com/ourgiant/saml/
├── ThemeManager.java              # UI theming
├── gui/                           # Swing UI - depends on core/, never the reverse
│   ├── SwingMain.java             # Main application window (and app entry point)
│   ├── FirstRunSetupDialog.java   # First-launch setup wizard
│   ├── CredentialsDialog.java     # Credential detail/export UI
│   ├── ConfigurationDialog.java   # Application settings UI
│   ├── ProfileManagerDialog.java  # Profile list management UI
│   └── ProfileEditDialog.java     # Add/edit a single profile
├── core/                          # Domain logic - no javax.swing.* dependency
│   ├── SamlAuthenticator.java     # SAML authentication flow orchestration
│   ├── SamlParser.java            # SAML assertion parsing (XXE-hardened)
│   ├── SamlRole.java              # SAML role representation
│   ├── BrowserLoginHandler.java   # Browser automation for the IdP login page
│   ├── BatchRefreshRunner.java    # Batch credential-refresh orchestration
│   ├── AwsConsoleLauncher.java    # AWS federation console sign-in
│   ├── CredentialManager.java     # AWS credential file management
│   ├── CredentialRequestError.java # Failure classification for credential requests
│   ├── TokenStateManager.java     # Token lifecycle management
│   ├── DatabaseManager.java       # SQLite database operations
│   ├── ConfigManager.java         # samlsts config handling
│   └── PasswordManager.java       # Password encryption/decryption
└── util/                          # Shared helpers with no business meaning of their own
    ├── FilePermissions.java       # Owner-only file permission enforcement
    └── JsonUtil.java              # Minimal JSON string-value extraction
```

### CI/CD

Every push to `main` and every pull request builds on Windows, macOS
(arm64 + x64), and Linux (see `.github/workflows/build.yml`). Tagged pushes
(`v*`) additionally package and publish platform installers to a GitHub
Release. A nightly workflow (`.github/workflows/nightly.yml`) rebuilds `main`
and republishes it as a rolling pre-release.

## License

MIT — see [LICENSE](LICENSE) for details.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## Troubleshooting

- If login fails with "Your Okta password needs to be reset before you can sign in", your IdP is forcing a password reset — log in to Okta via your browser to complete the reset, then try again
- Ensure Java 24 is installed and `JAVA_HOME` is set correctly (not needed when using a platform installer)
- Check browser compatibility for Selenium WebDriver (Chrome or Firefox)
- Verify your `samlsts` configuration and SAML provider settings via the Configuration/Profile Manager dialogs
- If a profile lingers in the status table after being removed from `samlsts`, use **Configuration → Force Refresh** to prune it immediately
- Review the application log (`aws-saml-ui.log`, alongside the jar) for error details
