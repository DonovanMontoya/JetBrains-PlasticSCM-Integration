# Plastic SCM Integration

![Build](https://github.com/DonovanMontoya/JetBrains-PlasticSCM-Integration/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/com.donovanmontoya.jetbrains-plasticscm-integration.svg)](https://plugins.jetbrains.com/plugin/com.donovanmontoya.jetbrains-plasticscm-integration)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.donovanmontoya.jetbrains-plasticscm-integration.svg)](https://plugins.jetbrains.com/plugin/com.donovanmontoya.jetbrains-plasticscm-integration)

<!-- Plugin description -->
Plastic SCM Integration integrates **Plastic SCM / Unity Version Control** into JetBrains Rider (and other IntelliJ-based IDEs)
as a first-class VCS, so it works through the same UI you use for Git:

- **Plastic SCM tool window**: a dedicated commit panel modelled on the Commit tool window (change tree with
  checkboxes, unversioned files, diff/undo/refresh toolbar, comment box and **Check In** button). It only shows
  Plastic items, so it sits next to Git's Commit tool window in projects that use both. The comment box carries the
  standard commit-message toolbar, so add-ons such as JetBrains AI Assistant's *Generate Commit Message* appear there
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

## Development

- `./gradlew test` runs the unit tests for the `cm` output parsers.
- `./gradlew buildPlugin` produces the installable zip in `build/distributions`.
- `./gradlew runIde` starts a sandbox IDE with the plugin. The build compiles against the IntelliJ Platform
  (`platformVersion` in `gradle.properties`); the plugin only uses platform VCS APIs, so the same zip installs in Rider.

Everything talks to Plastic through the `cm` CLI (`src/main/kotlin/.../cli/PlasticCli.kt`). Output is requested in
machine-readable formats and parsed in `PlasticStatusParser` / `PlasticOutputParsers`.

### Releases

- `pluginVersion` in `gradle.properties` is the stable release version, currently `1.0.0`.
- Every successful push to `main` publishes a GitHub **prerelease** after build, tests, code inspections,
  and plugin verification pass. Development versions use the next patch plus the workflow run and attempt,
  for example `1.0.1-dev.123.1`. Each tag points to the exact tested commit and contains the installable plugin ZIP.
  A newer push can cancel an older build, so only completed successful builds are released.
- Download development builds from the [GitHub releases list](https://github.com/DonovanMontoya/JetBrains-PlasticSCM-Integration/releases).
  The `releases/latest` link below deliberately selects stable releases, not development builds.
- To create a stable GitHub release, update `pluginVersion` as needed and run **Build** from `main` with
  **publish_stable_release** enabled. This publishes `v<pluginVersion>` only after the same checks pass.
  Existing tags are never reused or moved, and release assets are never overwritten. If publication fails after
  tag creation, inspect the tag/release before retrying a stable version. Pull requests and ordinary manual builds do not publish.
- JetBrains Marketplace publishing is separate. Run **Publish to JetBrains Marketplace** from `main` and enter
  an existing stable GitHub release tag. The tag must match its `gradle.properties` version. This uses the existing
  signing and Marketplace secrets and re-runs tests/verification before publishing. Do not republish a version
  already uploaded to Marketplace; choose a new stable version for the next Marketplace update.
- Automated GitHub releases require only the workflow's built-in `GITHUB_TOKEN`; no new token is needed.

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
