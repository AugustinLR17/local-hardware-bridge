# Automated media capture

Regenerates the screenshots and demo recordings in [`docs/assets/`](../../docs/assets)
**without any manual screenshotting**. Everything runs in Docker — the only host
requirement is Docker itself (no local JDK, Node, or Go needed).

## What it produces

| File | Surface | Tool |
|------|---------|------|
| `docs/assets/web-ui.png` | Dashboard (light) | Playwright |
| `docs/assets/web-ui-advanced.png` | Advanced tab | Playwright |
| `docs/assets/web-ui-security.png` | Security tab | Playwright |
| `docs/assets/web-ui-dark.png` | Dark theme | Playwright |
| `docs/assets/web-ui.webm` | Short walkthrough video | Playwright |
| `docs/assets/web-ui.gif` | Walkthrough as GIF (README-embeddable) | ffmpeg |
| `docs/assets/tui-demo.gif` | TUI admin demo | VHS |

## Run it

```bash
scripts/capture/capture.sh          # both Web UI and TUI
scripts/capture/capture.sh webui    # Web UI only
scripts/capture/capture.sh tui      # TUI only
```

The script:

1. Builds the shadow jar (in a `eclipse-temurin:21-jdk` container) if missing.
2. Starts the bridge in a container. The bridge keeps its **secure default**
   (it binds `127.0.0.1` *inside* that container — never exposed on the host).
3. Joins the bridge container's network namespace from the capture containers
   (`--network container:…`) so `http://localhost:57212` resolves for the
   headless browser / TUI without publishing any port.
4. Runs Playwright ([`webui.mjs`](webui.mjs)) and VHS ([`tui.tape`](tui.tape)).

## Customise

- **Web UI** — edit [`webui.mjs`](webui.mjs): viewport, `deviceScaleFactor`
  (2 = retina-crisp), which tabs to shoot, video on/off.
- **TUI** — edit [`tui.tape`](tui.tape): the keystrokes, theme, size, and whether
  to also emit an `.mp4`. Tape syntax: <https://github.com/charmbracelet/vhs>.

## CI

The [`media.yml`](../../.github/workflows/media.yml) workflow runs this on demand
(`workflow_dispatch`) and uploads the assets as a build artifact, so media can be
regenerated from the Actions tab without a local setup.

## Notes on README embedding

- GitHub renders `.png` and `.gif` inline. `.webm` does **not** render via a
  Markdown `![]()` image — to show a real video, drag the `.webm`/`.mp4` into the
  GitHub web editor for an issue/PR/README and paste the generated
  `user-images.githubusercontent.com` URL, or convert the `.webm` to a `.gif`.
