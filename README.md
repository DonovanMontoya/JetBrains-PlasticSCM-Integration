# RiderPlastic

![Build](https://github.com/DonovanMontoya/RiderPlastic/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/com.github.donovanmontoya.riderplastic.svg)](https://plugins.jetbrains.com/plugin/com.github.donovanmontoya.riderplastic)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.github.donovanmontoya.riderplastic.svg)](https://plugins.jetbrains.com/plugin/com.github.donovanmontoya.riderplastic)

<!-- Plugin description -->
RiderPlastic integrates **Plastic SCM / Unity Version Control** into JetBrains Rider (and other IntelliJ-based IDEs)
as a first-class VCS, so it works through the same UI you use for Git:

- **Commit tool window**: pending changes, private (unversioned) and ignored files from `cm status`
- **Check in** with a comment; selecting unversioned files adds them (`cm add`) first
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

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "RiderPlastic"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/com.github.donovanmontoya.riderplastic) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/com.github.donovanmontoya.riderplastic/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/DonovanMontoya/RiderPlastic/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
