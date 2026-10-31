# Changelog

What each version added. The newest is on top; the current one is also shown at the top of the [README](README.md).

## 0.20.10 - 2026-10-31

- *Center Code on Window* takes the text column from the code style's right margin for the file's language. 0.20.9
  read the editor's margin while it still held the general default (200 columns instead of 80 for Java), which put
  the code against the left edge.

## 0.20.9 - 2026-10-31

- *Center Code on Window* works out the strip while the editor lays itself out, in the same pass that moves it, so
  resizing a tool window on the left no longer makes the code shake. It runs in the IDE's Center View mode with the
  IDE's own margin at zero (*Advanced Settings*, `editor.distraction.free.margin`); switching the feature off puts both
  back as they were. The warning about the `-Deditor.distraction.free.mode=true` VM option is gone: that mode is now
  part of the feature.

## 0.20.8 - 2026-10-31

- *Center Code on Window* lays the window out before it measures. Resizing a tool window on the left still makes the
  code shake slightly: the IDE paints the moved editor before the plugin can narrow the strip.

## 0.20.7 - 2026-10-31

- The `Center on window:` log line names what moved the editor, to find the last bit of shaking while a tool window
  on the left is resized.

## 0.20.6 - 2026-10-31

- *Center Code on Window* glides the code to a new place over 180 ms instead of jumping (a narrower editor, a resized
  window, switching it on), while a tool window that leaves the code room still leaves it exactly where it was.

## 0.20.5 - 2026-10-31

- *Center Code on Window* no longer pushes the code out of sight: it measured before the editor took the new strip
  and kept adding to it. The editor is now laid out right after each change, a change that did not move the code is
  not built upon, and the strip never exceeds half the window.

## 0.20.4 - 2026-10-31

- *Show Changes Since Tick* in the Commit window's and the project tree's context menus: a diff of a ticked file
  between the content it was ticked with and its content now. Ticks from before a restart keep only the dash.
- A file whose folder has no colour, such as a test next to coloured main sources, takes its package's colour on the
  `package` line.

## 0.20.3 - 2026-10-31

- *Center Code on Window* sets the strip's width in exact pixels and in one step, right when the editor moves, so the
  code no longer drifts into place over a dozen small steps; a difference of two pixels or less is left alone.

## 0.20.2 - 2026-10-31

- *Center Code on Window* measures again after the gutter takes the new width, so the code ends in the middle and
  stays there, and logs every change of the pad (`Center on window:` in `idea.log`).

## 0.20.1 - 2026-10-31

- *View | Appearance | Center Code on Window* keeps the code in the middle of the window instead of the editor, so a
  tool window opened on one side does not push it aside. Off by default.
- *Tools | BetterJ* switches each feature on and off with a tick, and *Settings | Tools | BetterJ* has the same as
  checkboxes with what each one does. Line counts, the open-file mark and package colours can now be switched off too.
- The Commit window's ticks survive a restart: the IDE used to start with every change ticked. A file changed while
  the IDE was closed shows a dash.
- A warning when a `-Deditor.distraction.free.mode=true` VM option keeps the IDE's own Center View on and blocks
  centring on the window.

## 0.20.0 - 2026-10-30

- The branch total in the Commit window counts a stacked branch from the branch below it (`vs ZT-847 +859 −9`), so it
  matches its pull request; any other branch still counts from develop.
- The `package` line takes its package's colour in diffs too, on both sides; a past revision gets the colour of the
  folder its package names.

## 0.19.0 - 2026-10-30

- The pull request's tree also counts the files it adds and deletes, after the lines: `+760 −45 · 3 new · 1 deleted`.

## 0.18.0 - 2026-10-30

- Renamed to **BetterJ**, with a new icon: the plugin now covers committing, branches and package colours, not only
  reviews. Installs over BetterCodeReview and keeps its settings.
- *Package Color* is back in the project tree's and the editor tab's context menus; the IDE dropped it for a missing
  name.

## 0.17.0 - 2026-10-30

- The Commit window shows the whole branch against develop (`vs develop +858 −8`): its commits plus the work not
  committed yet. It shows with no local changes too, so a freshly checked-out branch gives its size at once.

## 0.16.0 - 2026-10-30

- The line count in the pull request's tree shows on its top row also when the tree is grouped by module.
- A ticked file compares its content with the moment it was ticked: edit it and it shows a dash, undo the edit and
  the tick comes back.

## 0.15.0 - 2026-10-30

- Commit (`⌘K`) keeps the checkboxes as they were instead of ticking every change.
- The project tree's checkboxes take a click anywhere between the box and the file's icon.
- The pull request's line count logs what it does, and no longer waits forever for the pull request's commits.

## 0.14.0 - 2026-10-28

- The Commit window's checkboxes show in the project tree too, in front of each changed file, with the same cycle
  and the same dash.

## 0.13.0 - 2026-10-28

- The pull request's tree shows the lines added and removed next to its file count.

## 0.12.0 - 2026-10-28

- Package colours of your own: the project tree, the change trees, the editor tabs and the `package` line, with an
  editable palette and an import from ProjectTree Color Highlighter.
- The Commit window shows the lines added and removed next to the file count.

## 0.11.0 - 2026-10-28

- The file open in the editor pulses in the Commit window's tree too.
- A file ticked for the commit shows a dash once it changes again.

## 0.10.0 - 2026-10-16

- Three-state checkboxes: the viewed cycle in the pull request's tree and change marks in the Commit window.

## 0.9.0 - 2026-10-16

- Double-click opens the file from the pull request's tree; viewed files changed since get a dash.

## 0.8.0 - 2026-10-16

- Renamed to BetterCodeReview.

## 0.7.0 - 2026-10-16

- Files holding your unsent review comments get an amber bar and a count.

## 0.6.0 - 0.6.1 - 2026-10-15

- The file open in the editor is marked in the pull request's tree, with a gentle pulse.

## 0.5.0 - 2026-10-15

- *Review…* button: approve, request changes or comment from the IDE.

## 0.1.0 - 0.4.0 - 2026-10-15

- Copy review comments, emoji completion and picker, paste screenshots as GitHub attachments, buttons under the
  comment field.
