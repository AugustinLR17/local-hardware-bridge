# VHS tape — automated TUI demo recording for Local Hardware Bridge.
# Render with charmbracelet/vhs (https://github.com/charmbracelet/vhs):
#   vhs scripts/capture/tui.tape
# Produces docs/assets/tui-demo.gif (and .mp4 if uncommented below).
#
# Requires:
#   - the lhb-tui binary on PATH (built from ./tui)
#   - a running bridge reachable at the --server URL below

Output docs/assets/tui-demo.gif
# Output docs/assets/tui-demo.mp4

Set Shell bash
Set FontSize 16
Set Width 1200
Set Height 720
Set Padding 18
Set Theme "Catppuccin Mocha"
Set TypingSpeed 60ms

# Launch the TUI against the local bridge.
Type "lhb-tui --server http://localhost:57212"
Enter
Sleep 3s

# View 1: Health (landing)
Sleep 1500ms

# Cycle through the four views (1=Health, 2=Printers, 3=Serial, 4=Connections)
Type "2"
Sleep 1800ms
Type "3"
Sleep 1800ms
Type "4"
Sleep 1800ms
Type "1"
Sleep 1500ms

# Refresh once to show live polling
Type "r"
Sleep 1800ms

# Quit cleanly
Type "q"
Sleep 800ms
