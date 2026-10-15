# Plastic SCM Integration

![Build](https://github.com/DonovanMontoya/RiderPlastic/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/com.github.donovanmontoya.riderplastic.svg)](https://plugins.jetbrains.com/plugin/com.github.donovanmontoya.riderplastic)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.github.donovanmontoya.riderplastic.svg)](https://plugins.jetbrains.com/plugin/com.github.donovanmontoya.riderplastic)

<!-- Plugin description -->
Plastic SCM Integration brings Plastic SCM workflows directly into JetBrains Rider. The plugin aims to streamline everyday source control actions including:

- Cloning or connecting to Plastic SCM repositories
- Viewing workspace status and pending changes
- Creating, amending, and submitting commits
- Managing branches and switches between them
- Inspecting diffs, resolving merge conflicts, and launching merge tools
- Reviewing incoming and outgoing changesets

Installation is available via the JetBrains Marketplace or manual package download. Ensure the Plastic SCM command-line client is installed and available on your PATH, and authenticate with your Plastic SCM credentials or a configured authentication provider before using the plugin.

<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Plastic SCM Integration"</kbd> >
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
