# Installing BetterJ

## What you need

- IntelliJ IDEA 2026.1, 2026.2 or 2026.3 (Ultimate or Community).
- The bundled **Git** and **GitHub** plugins enabled (they are by default).
- For pull request features: a GitHub account added in *Settings | Version Control | GitHub*.

## Install

1. Download [BetterJ-0.20.12.zip](dist/BetterJ-0.20.12.zip). Keep it zipped; do not unpack it.
2. In IntelliJ IDEA open *Settings* (`⌘,` on macOS, `Ctrl+Alt+S` on Windows and Linux) and go to *Plugins*.
3. Click the gear icon ⚙ at the top and choose *Install Plugin from Disk…*.
4. Pick the downloaded `BetterJ-0.20.12.zip` and click *OK*.
5. Restart the IDE when asked.

To check it worked: *Settings | Plugins | Installed* lists **BetterJ** with the version shown at the top
of the [README](README.md).

## Update

Download the new zip and install it the same way. The IDE replaces the old version; restart when asked.
What changed is in the [changelog](CHANGELOG.md).

## If you used ProjectTree Color Highlighter

BetterJ imports its folder colours (`.idea/highlightedFiles.xml`) on first use. Uninstall that plugin
afterwards: with both installed, its colours win over these.

## Switching single features off

*Tools | BetterJ* in the menu bar lists every feature with a tick: click one to switch it off or on. The same
switches, with what each feature does, are in *Settings | Tools | BetterJ*. *Center Code on Window* is the only one
that starts off. Package colours themselves are edited in *Settings | Tools | Package Colors*.

## Something does not show

The plugin writes what it does to the IDE log. *Help | Show Log in Finder* (or *Explorer*) opens it; search
`idea.log` for `bettercodereview`. Send the matching lines together with the IDE version from
*IntelliJ IDEA | About*.

## Uninstall

*Settings | Plugins | Installed*, select **BetterJ**, click the arrow next to *Disable* and choose
*Uninstall*. Restart the IDE.
