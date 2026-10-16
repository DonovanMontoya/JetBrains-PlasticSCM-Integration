<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Plastic SCM Integration Changelog

## [Unreleased]
### Added
- Right-click menu for files in the Plastic SCM tool window, matching the IDE's Local Changes menu; Check In File checks the selected files in the tool window
- Plastic changes in folders another VCS owns, such as a Git repository nested in the workspace, now appear in the Plastic SCM tool window

### Changed
- Replace the AI generation controls with a single icon button next to Check In; its tooltip shows the destination and model
- AI settings show only the selected provider's fields
- Status refreshes run one `cm status` instead of two, and changes in nested Git folders come from the same read
- Reopened diffs reuse the base revision instead of running `cm cat` again, and AI generation reads base revisions in parallel
- Double-click or Enter in the Plastic SCM tool window opens the file, or its diff when the file is already in the editor; Show Diff reuses one editor tab that follows the selection, like the Commit tool window

### Fixed
- Check In and AI Settings buttons failing on IDE 2026.2 with a threading error
- Cancelled `cm` commands being reported as errors
- Files in more than one state at once, such as replaced and checked out, missing from the IDE's changes
- Changes not refreshing after check-ins, updates, switches or undos made outside the IDE; the workspace's `.plastic` metadata is now watched, as the IDE does with `.git`

## [1.0.1] - 2026-10-15
### Added
- Inline commit-message generation with user-owned OpenAI or Anthropic Claude credentials, or an OpenAI-compatible /v1 endpoint
- Provider, model, endpoint, and isolated PasswordSafe key settings with explicit per-request sharing consent

### Fixed
- Remove internal IntelliJ API dependencies that blocked plugin verification
- Preserve edited commit messages and changed selections when generation finishes; bound input and response sizes and support cancellation
- Address relevant Qodana findings without suppressing inspections

### Changed
- Keep keys session-only on IDE 2025.2 and use normal PasswordSafe policy on newer IDEs
- Publish verified GitHub development releases automatically while keeping stable and Marketplace publication explicit
- Preserve existing release drafts and original installable ZIP assets

## [1.0.0]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Plastic SCM / Unity Version Control VCS integration backed by the `cm` CLI: local changes, check-in, rollback, diff, file history, annotate, update project
- Status bar branch widget and Switch Branch / New Branch actions
- Settings page for the `cm` executable path
- Dedicated "Plastic SCM" tool window for checking in, usable alongside Git's Commit tool window

### Changed
- Rename the plugin to "Plastic SCM Integration", as JetBrains Marketplace does not allow product names such as "Rider" in plugin names
- Update Gradle wrapper, Kotlin, Kover, Qodana, IntelliJ Platform Gradle Plugin and GitHub Actions dependencies
