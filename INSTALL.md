# Installing BetterCodeReview

## What you need

- IntelliJ IDEA 2026.1, 2026.2 or 2026.3 (Ultimate or Community).
- The bundled **Git** and **GitHub** plugins enabled (they are by default).
- For pull request features: a GitHub account added in *Settings | Version Control | GitHub*.

## Install

1. Download [BetterCodeReview.zip](dist/BetterCodeReview.zip). Keep it zipped; do not unpack it.
2. In IntelliJ IDEA open *Settings* (`⌘,` on macOS, `Ctrl+Alt+S` on Windows and Linux) and go to *Plugins*.
3. Click the gear icon ⚙ at the top and choose *Install Plugin from Disk…*.
4. Pick the downloaded `BetterCodeReview.zip` and click *OK*.
5. Restart the IDE when asked.

To check it worked: *Settings | Plugins | Installed* lists **BetterCodeReview** with the version shown at the top
of the [README](README.md).

## Update

Download the new zip and install it the same way. The IDE replaces the old version; restart when asked.
What changed is in the [changelog](CHANGELOG.md).

## If you used ProjectTree Color Highlighter

BetterCodeReview imports its folder colours (`.idea/highlightedFiles.xml`) on first use. Uninstall that plugin
afterwards: with both installed, its colours win over these.

## Switching single features off

Right-click inside the pull request's file tree or the Commit window's tree: the context menu holds
*Viewed Checkbox Cycles Through Dash*, *Double-Click Opens File* and *Dash Changed or Flagged Files*.
Package colours are edited in *Settings | Tools | Package Colors*.

## Something does not show

The plugin writes what it does to the IDE log. *Help | Show Log in Finder* (or *Explorer*) opens it; search
`idea.log` for `bettercodereview`. Send the matching lines together with the IDE version from
*IntelliJ IDEA | About*.

## Uninstall

*Settings | Plugins | Installed*, select **BetterCodeReview**, click the arrow next to *Disable* and choose
*Uninstall*. Restart the IDE.
