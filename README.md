# BetterPullRequests

Small additions to the GitHub Pull Requests review UI bundled with IntelliJ IDEA (and other JetBrains IDEs):

1. **Copy review comments** - a copy button next to edit / delete / react when a comment is hovered,
   `Cmd+C` / `Ctrl+C` while a comment has focus (selection, or the whole comment when nothing is selected),
   and a right-click menu with *Copy Comment* and *Copy Selection*. Works in the PR timeline, in diff threads
   and in editor inlays.
2. **Emoji in comments** - type `:smi` in a comment editor and pick from the completion popup (GitHub short codes,
   inserted as Unicode glyphs). The 😀 button under the comment field, `Ctrl+Shift+;` (`⌘⇧;` on macOS) or *Edit | Insert Emoji…*
   opens an emoji picker: a grid of all 1900+ GitHub emoji with a search box and the recently used ones on top.
3. **Paste screenshots** - the paste shortcut (whatever your keymap binds it to), the image button under the
   comment field or *Edit | Paste Image as Attachment* with an image on the clipboard (or image files copied from
   a file manager) inserts an `![Uploading …]()` placeholder and uploads the image as a regular GitHub attachment
   (`github.com/user-attachments/assets/...`). The link renders on github.com, in e-mails and in the IDE,
   also for private repositories. Text next to the image wins unless it is just the image's address; copied files
   always count as files. *Paste as Plain Text* always pastes the text.

## Requirements

- IntelliJ IDEA 2026.1 to 2026.3 (builds 261 to 263) with the bundled *GitHub* and *Git* plugins enabled.
- A GitHub account configured in *Settings | Version Control | GitHub* (the upload reuses its token).
- Attachments: github.com only. GitHub Enterprise is not supported, the upload endpoint differs.

## Build

```bash
JAVA_HOME=<JDK 21> ./gradlew buildPlugin
```

The zip lands in `build/distributions/` (`BetterPullRequests-<version>.zip`). `gradle.properties` points `platformLocalPath` at a local IDE so the
build needs no 1 GB download; remove the property to build against a downloaded IntelliJ IDEA Community 2026.1.

Tests: `./gradlew test` (unit tests plus platform tests in a headless IDE sandbox).

## Install

*Settings | Plugins | ⚙ | Install Plugin from Disk…* and choose the zip from `build/distributions/`. Restart the IDE.

## Caveats

- The attachment upload uses `uploads.github.com/user-attachments/assets`, which GitHub does not document.
  It is the same endpoint the web UI's attachment box uses and it accepts the IDE's OAuth token today; if GitHub
  changes it, pasting falls back to an error balloon and nothing is lost (the placeholder is removed).
- The plugin hooks into internal UI details of the GitHub plugin (the name of the HTML pane that renders a
  comment, the `Dummy.md` file behind the comment editor, the layout of the comment field's action row).
  A major IDE update may need a small follow-up.
- Uploads go to github.com only; a GitHub Enterprise account gets an error balloon instead.
- Reactions on comments stay limited to the eight GitHub allows; that is a GitHub API limit, not an IDE one.
