# BetterCodeReview

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

4. **Submit the review from the IDE** - a *Review…* button in the pull request details next to Merge (and
   *Submit Review…* in the details, changes and timeline context menus) opens GitHub's palette: Approve,
   Request Changes, Comment, with a summary field and a way to discard pending comments. It is there in every
   state, also after someone else already approved, where the bundled plugin hides its own button. Authors can
   only comment, as on github.com.

5. **See where you are in the change tree** - the file open in the selected editor tab gets a gently pulsing
   purple tint and a bar at the left edge in the pull request's file tree, on top of selection, hover and file
   colours. It follows
   the editor file matched by path, so it works when the real file is open (review on the checked-out branch);
   a diff tab does not match. Renamed files match their new path, deleted files never do. The Commit tool window's
   tree (and Local Changes) marks the open file the same way, with or without its checkboxes.
6. **See where your unsent comments are** - files holding comments of your pending (not yet submitted) review,
   new threads and replies alike, get an amber bar at the left edge and a round badge with the count (the badge
   appears when the indentation leaves room for it). A collapsed folder gets a lighter bar; expanded folders show
   nothing, their files do. The marks disappear once the review is submitted. Unresolved discussions are already
   shown by the bundled plugin as a comment icon with a number next to the file; a pending thread counts there too.
   Theme keys: `BetterCodeReview.CurrentFile.background`, `BetterCodeReview.PendingComments.foreground`,
   `BetterCodeReview.PendingComments.text`.
7. **See which viewed files changed again** - GitHub drops the "viewed" tick of a file when a later commit touches
   it, and the bundled plugin then shows the same unread dot as for a file never looked at (the checkbox itself only
   appears under the mouse). The plugin paints a dash over that slot: dot = not viewed, tick = viewed, dash = viewed
   before, changed since. Clicking cycles the states: a ticked file clicked once shows the dash ("read it again"),
   clicked again is unticked, clicked again is ticked; a file GitHub dismissed follows the same path, and that
   click unticks it on GitHub. GitHub only stores ticked or not, so the dash after a click is the plugin's own flag,
   kept per project and pull request (the newest 500) and dropped when the file is ticked elsewhere. The cycle acts
   on plain left clicks on the checkbox; the keyboard and the context menu's own toggles go straight to GitHub.
   *Viewed Checkbox Cycles Through Dash* in the tree's context menu switches the cycle off.
   Theme keys: `BetterCodeReview.ViewedDismissed.dash` (defaults to the theme's selected checkbox colour),
   `.dashOnCurrent` for the row of the open file and `.dashInBox` for the dash inside a checkbox.
8. **Double-click opens the file** - double-click or Enter on a file in the pull request's tree opens the file
   in the editor, which is what reading on the checked-out branch calls for; Alt+double-click and *Show Diff*
   in the context menu open the diff as before (the platform ignores Enter with modifiers, so there is no
   keyboard variant). When the pull request's branch is not the one checked out, the diff opens and a balloon
   says why, once per tree. "Checked out" means the branch, not that the working copy equals the pull request's
   head: unpulled commits or local edits can make line comments point elsewhere. Only the tree of the whole GitHub
   pull request is affected: in the tree of a single commit, and in a GitLab merge request, the diff opens as before.
   *Double-Click Opens File* in the tree's context menu switches the behaviour off.
9. **Commit window: see which ticked files changed** - the checkboxes in the Commit tool window choose what goes
   into the commit. A file you ticked by clicking its checkbox shows a dash once it is modified afterwards: edited in
   the editor, saved, rewritten on disk by a tool such as a coding agent, or deleted. Any rewrite counts, also one that
   leaves the content as it was, and reverting the edit does not take the dash away; untick and tick again does. A
   ticked file you click once gets the dash too, to mark it for another look. A dashed file is still part of the
   commit. Clicking a dashed file unticks it as usual, and ticking it again takes its current content as the new
   baseline. Files the IDE ticks on its own, files ticked before the plugin saw the tree, and files ticked with the
   keyboard get no baseline, so they never dash by themselves. Baselines and these flags live in memory only. A file
   with only some of its lines included keeps the platform's own indeterminate box and is left alone. The cycle acts
   on plain left clicks; modifier-clicks and Space go straight to the platform. *Dash Changed or Flagged Files* in
   the tree's context menu switches the feature off.
   Next to the number of files, each change list shows the lines added and removed (`+120 −34`), counted like the
   diff viewer counts them (a changed line is one of each), unsaved edits included. Binary files and files over two
   million characters count as zero. Theme keys: `BetterCodeReview.LineStats.added`, `.deleted`.

10. **Package colours** - right-click a folder or file in the project tree (or an editor tab) and pick a colour under
   *Package Color*. The colour shows as the background in the project tree, in the change trees (Commit, pull request)
   and on the editor tabs, for the folder and everything inside it; a nested folder may get its own colour. Files in a
   coloured package also get the colour behind their `package` line in the editor (Java, Kotlin, Scala, Groovy, Go), so
   the editor tells which part of the project you are in. The palette is yours: *Settings | Tools | Package Colors*
   edits each colour's name and value (with transparency) and switches the package line off. Settings live in
   `.idea/betterCodeReviewColors.xml`. On first use the plugin imports the assignments and the palette of the
   *ProjectTree Color Highlighter* plugin from `.idea/highlightedFiles.xml`; uninstall that plugin afterwards, since
   with both installed its colours win.

## Requirements

- IntelliJ IDEA 2026.1 to 2026.3 (builds 261 to 263) with the bundled *GitHub* and *Git* plugins enabled.
- A GitHub account configured in *Settings | Version Control | GitHub* (the upload reuses its token).
- Attachments: github.com only. GitHub Enterprise is not supported, the upload endpoint differs.

## Build

```bash
JAVA_HOME=<JDK 21> ./gradlew buildPlugin
```

The zip lands in `build/distributions/` (`BetterCodeReview-<version>.zip`). `gradle.properties` points `platformLocalPath` at a local IDE so the
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
- The current-file mark is a transparent overlay added to the change tree, recognised by the class name of the
  tree the collaboration tools build; an IDE update may need a follow-up.
- The pending-comment marks and the viewed-state dash read the pull request's review data through private fields of
  the change list model (the data itself comes from public, cached flows, so no extra requests are made). When the
  fields are not where the plugin expects them, the marks stay off and the IDE log says so at INFO level
  ("Tree marks ..."). Both work only in the tree of the whole pull request, not in the tree of a single commit.
- The click cycles intercept checkbox clicks at the toolkit level, before the trees' own click handlers, and the
  pull request tree's viewed-state toggle (which ignores consumed events) is rewrapped to respect them. Both are
  undone when the plugin unloads.
- The *Review…* button is recognised by the shape of the details action row, which GitLab merge requests share;
  it is only added where a GitHub pull request is in the context. The submit flow reuses the GitHub plugin's
  review model through its pluggable popup handler, an internal API.
- Reactions on comments stay limited to the eight GitHub allows; that is a GitHub API limit, not an IDE one.
