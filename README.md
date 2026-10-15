# GitHub Review Extras

Small additions to the GitHub Pull Requests review UI bundled with IntelliJ IDEA (and other JetBrains IDEs):

1. **Copy review comments** - `Cmd+C` / `Ctrl+C` while a comment has focus copies the selected text, or the
   whole comment when nothing is selected. A right-click context menu offers *Copy Comment* and *Copy Selection*.
   Works in the PR timeline, in diff threads and in editor inlays.
2. **Emoji in comments** - type `:smi` in a comment editor and pick from the completion popup (GitHub short codes,
   inserted as Unicode glyphs). `Ctrl+Alt+E` opens a searchable emoji picker (also in the editor context menu).
3. **Paste screenshots** - `Cmd+V` / `Ctrl+V` with an image on the clipboard (or image files copied from Finder)
   inserts an `![Uploading …]()` placeholder and uploads the image as a regular GitHub attachment
   (`github.com/user-attachments/assets/...`). The link renders on github.com, in e-mails and in the IDE,
   also for private repositories.

## Requirements

- IntelliJ IDEA 2026.1 (build 261.*) with the bundled *GitHub* and *Git* plugins enabled.
- A GitHub account configured in *Settings | Version Control | GitHub* (the upload reuses its token).
- Attachments: github.com only. GitHub Enterprise is not supported, the upload endpoint differs.

## Build

```bash
JAVA_HOME=<JDK 21> ./gradlew buildPlugin
```

The zip lands in `build/distributions/`. `gradle.properties` points `platformLocalPath` at a local IDE so the
build needs no 1 GB download; remove the property to build against a downloaded IntelliJ IDEA Community 2026.1.

Tests: `./gradlew test` (unit tests plus platform tests in a headless IDE sandbox).

## Install

*Settings | Plugins | ⚙ | Install Plugin from Disk…* and choose the zip from `build/distributions/`. Restart the IDE.

## Caveats

- The attachment upload uses `uploads.github.com/user-attachments/assets`, which GitHub does not document.
  It is the same endpoint the web UI's attachment box uses and it accepts the IDE's OAuth token today; if GitHub
  changes it, pasting falls back to an error balloon and nothing is lost (the placeholder is removed).
- The plugin hooks into internal UI details of the GitHub plugin (the name of the HTML pane that renders a
  comment, the `Dummy.md` file behind the comment editor). A major IDE update may need a small follow-up.
- Reactions on comments stay limited to the eight GitHub allows; that is a GitHub API limit, not an IDE one.
