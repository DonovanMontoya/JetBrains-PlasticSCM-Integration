<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Plastic SCM Integration Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Plastic SCM / Unity Version Control VCS integration backed by the `cm` CLI: local changes, check-in, rollback, diff, file history, annotate, update project
- Status bar branch widget and Switch Branch / New Branch actions
- Settings page for the `cm` executable path
- Dedicated "Plastic SCM" tool window for checking in, usable alongside Git's Commit tool window

### Changed
- Rename the plugin to "Plastic SCM Integration", as JetBrains Marketplace does not allow product names such as "Rider" in plugin names
- Update Gradle wrapper, Kotlin, Kover, Qodana, IntelliJ Platform Gradle Plugin and GitHub Actions dependencies
