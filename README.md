# Plastic SCM Integration

![Build](https://github.com/DonovanMontoya/JetBrains-PlasticSCM-Integration/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/com.donovanmontoya.jetbrains-plasticscm-integration.svg)](https://plugins.jetbrains.com/plugin/com.donovanmontoya.jetbrains-plasticscm-integration)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.donovanmontoya.jetbrains-plasticscm-integration.svg)](https://plugins.jetbrains.com/plugin/com.donovanmontoya.jetbrains-plasticscm-integration)

<!-- Plugin description -->
Plastic SCM Integration integrates **Plastic SCM / Unity Version Control** into JetBrains Rider (and other IntelliJ-based IDEs)
as a first-class VCS, so it works through the same UI you use for Git:

- **Plastic SCM tool window**: a dedicated commit panel modelled on the Commit tool window (change tree with
  checkboxes, unversioned files, diff/undo/refresh toolbar, comment box and **Check In** button). It only shows
  Plastic items, so it sits next to Git's Commit tool window in projects that use both. The inline **Generate** button
  drafts a check-in comment using your own OpenAI, Anthropic Claude, or OpenAI-compatible endpoint settings
- **Check in** with a comment; checked unversioned files are added (`cm add`) first
- Plastic changes also show up in the IDE's shared local-changes model (gutter markers, file colors)
- **Rollback** (`cm undo`), **diff against the base revision**, gutter change markers
- **Show History** for a file and compare revisions (`cm history` / `cm cat`)
- **Annotate** / blame (`cm annotate`)
- **Update Project** (`cm update`)
- **Branch widget** in the status bar plus *VCS | Plastic SCM | Switch Branch / New Branch*

Requires the Plastic SCM command line client (`cm`), installed with Unity Version Control, and an already
configured/authenticated workspace. The `cm` path is auto-detected and can be overridden under
<kbd>Settings</kbd> > <kbd>Version Control</kbd> > <kbd>Plastic SCM</kbd>.
<!-- Plugin description end -->

## Getting started

1. Install Unity Version Control (Plastic SCM) and make sure `cm` works in a terminal (`cm version`).
2. Open a solution that lives inside a Plastic workspace (a folder containing `.plastic`).
3. Rider detects the workspace and offers to register it; or add it manually under
   <kbd>Settings</kbd> > <kbd>Version Control</kbd> > <kbd>Directory Mappings</kbd> with VCS **Plastic SCM**.

## Optional AI commit messages

Open **Settings > Version Control > Plastic SCM** and choose OpenAI, Anthropic Claude, or an
OpenAI-compatible endpoint. Enter the model identifier and your own provider API key using the explicit
key controls. Local endpoints accept a configurable `/v1` base URL and an optional key. OpenAI and Claude
use their native provider APIs. Your provider account is billed separately from any IDE AI subscription.

In the Plastic SCM tool window, check the files to include, review the displayed destination and model,
then allow that request with the sharing checkbox and click **Generate**. Only those checked Plastic files'
text diffs are prepared; unchecked files and Git changes are not included. New files contribute their text.
Generation never starts automatically and never checks files in. Review the generated message before **Check In**.

- Selected code leaves the IDE for the displayed destination. An OpenAI-compatible endpoint may be remote:
  only an endpoint you actually run on this computer is local. HTTPS is required except for exact loopback hosts.
- Binary files, symbolic links, obvious credential files, and oversized selections are rejected without a request.
  Filename checks cannot guarantee that ordinary source files contain no secrets; review what you check.
- Files read from disk and historical revisions must be UTF-8 text. Historical content is retrieved through a
  temporary `cm cat` file before its bounded read, so it may use temporary disk space even when rejected.
- Cancel stops pending work. A late response cannot replace a message you edited or a different checked selection.
- Keys belong only to this plugin and provider/endpoint; they are never imported from another plugin or saved
  in project/XML settings or logs. They use the IDE PasswordSafe subject to its configured storage policy.
- On IDE **2025.2**, keys are deliberately **session-only**, including local IDE sessions, because older remote
  development backends cannot safely persist them. Re-enter them after restarting. IDE **2025.3+** supports
  normal PasswordSafe storage. Empty key input does not erase an existing key; use the explicit remove control.
- Provider redirects and automatic request retries are disabled. A missing key, timeout, rate limit, invalid model,
  or malformed response leaves your existing comment intact. Retry manually when appropriate.
- Generation is blocked when JVM HTTP diagnostics, automatic POST retries, or disabled TLS hostname verification
  could weaken these protections. Remove the indicated JVM overrides and restart the IDE before trying again.

## Development

- `./gradlew test` runs parser, AI protocol, selected-diff, credential-scope, and generation-safety tests using synthetic data.
- `./gradlew buildPlugin` produces the installable zip in `build/distributions`.
- `./gradlew runIde` starts a sandbox IDE with the plugin. The build compiles against the IntelliJ Platform
  (`platformVersion` in `gradle.properties`); the plugin only uses platform VCS APIs, so the same zip installs in Rider.

Everything talks to Plastic through the `cm` CLI (`src/main/kotlin/.../cli/PlasticCli.kt`). Output is requested in
machine-readable formats and parsed in `PlasticStatusParser` / `PlasticOutputParsers`.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Plastic SCM Integration"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/com.donovanmontoya.jetbrains-plasticscm-integration) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/com.donovanmontoya.jetbrains-plasticscm-integration/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/DonovanMontoya/JetBrains-PlasticSCM-Integration/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
