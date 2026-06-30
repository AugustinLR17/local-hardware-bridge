#!/usr/bin/env bash
#
# Automated screenshot + video capture for Local Hardware Bridge.
#
# Produces fresh media in docs/assets/ with zero manual screenshotting:
#   - Web UI  -> Playwright (headless Chromium): PNG screenshots + a .webm walkthrough
#   - TUI     -> VHS (charmbracelet): an animated GIF
#
# Everything runs in Docker; the only host requirement is Docker itself.
# The bridge keeps its secure default (binds 127.0.0.1 *inside* its container);
# the capture containers join that container's network namespace so
# http://localhost:57212 resolves without ever exposing the bridge on the host.
#
# Usage:
#   scripts/capture/capture.sh            # capture both Web UI and TUI
#   scripts/capture/capture.sh webui      # Web UI only
#   scripts/capture/capture.sh tui        # TUI only
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

VERSION="$(grep -E "^version " build.gradle | head -1 | sed -E "s/.*'([^']+)'.*/\1/")"
JAR="build/libs/local-hardware-bridge-${VERSION}.jar"
SERVER_CT="lhb-capture-server"
ASSETS="$REPO_ROOT/docs/assets"
TARGET="${1:-all}"

JDK_IMAGE="eclipse-temurin:21-jdk"
JRE_IMAGE="eclipse-temurin:21-jre"
PLAYWRIGHT_IMAGE="mcr.microsoft.com/playwright:v1.48.0-jammy"
GO_IMAGE="golang:1.22"
VHS_IMAGE="ghcr.io/charmbracelet/vhs:latest"
FFMPEG_IMAGE="linuxserver/ffmpeg:latest"

mkdir -p "$ASSETS"

cleanup() { docker rm -f "$SERVER_CT" >/dev/null 2>&1 || true; }
trap cleanup EXIT

build_jar() {
  if [[ ! -f "$JAR" ]]; then
    echo "> building $JAR"
    docker run --rm -v "$REPO_ROOT":/app -w /app \
      -e GRADLE_USER_HOME=/app/.gradle-docker \
      "$JDK_IMAGE" ./gradlew shadowJar --console=plain --no-daemon -q
  fi
}

start_server() {
  cleanup
  echo "> starting bridge ($SERVER_CT)"
  docker run -d --name "$SERVER_CT" \
    -v "$REPO_ROOT/build/libs":/libs \
    "$JRE_IMAGE" \
    java -cp "/libs/local-hardware-bridge-${VERSION}.jar" \
    io.github.augustinlr17.localhardwarebridge.Server >/dev/null
  echo -n "  waiting for bridge"
  for _ in $(seq 1 20); do
    if docker run --rm --network "container:${SERVER_CT}" curlimages/curl:latest \
         -sf http://localhost:57212/system/health >/dev/null 2>&1; then
      echo " ok"; return 0
    fi
    echo -n "."; sleep 2
  done
  echo " FAILED"; docker logs "$SERVER_CT"; exit 1
}

capture_webui() {
  echo "> capturing Web UI (Playwright)"
  docker run --rm --network "container:${SERVER_CT}" \
    -v "$REPO_ROOT/scripts/capture":/work \
    -v "$ASSETS":/out \
    -w /work "$PLAYWRIGHT_IMAGE" \
    bash -lc "npm install --no-audit --no-fund --loglevel=error && node webui.mjs http://localhost:57212 /out"
  webm_to_gif
}

# Convert the recorded walkthrough (web-ui.webm) to a README-friendly GIF.
# Two-pass palette for crisp colours; trims the ~1.3s blank initial page load.
webm_to_gif() {
  [[ -f "$ASSETS/web-ui.webm" ]] || return 0
  echo "> converting web-ui.webm -> web-ui.gif"
  docker run --rm -v "$ASSETS":/a "$FFMPEG_IMAGE" -y -ss 1.3 -i /a/web-ui.webm \
    -vf "fps=12,scale=1280:-1:flags=lanczos,palettegen=stats_mode=diff" /a/_palette.png >/dev/null 2>&1
  docker run --rm -v "$ASSETS":/a "$FFMPEG_IMAGE" -y -ss 1.3 -i /a/web-ui.webm -i /a/_palette.png \
    -lavfi "fps=12,scale=1280:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=3" \
    /a/web-ui.gif >/dev/null 2>&1
  rm -f "$ASSETS/_palette.png"
}

capture_tui() {
  echo "> building lhb-tui"
  docker run --rm -v "$REPO_ROOT/tui":/src -w /src \
    -e CGO_ENABLED=0 "$GO_IMAGE" \
    go build -o /src/lhb-tui . >/dev/null
  echo "> capturing TUI (VHS)"
  docker run --rm --network "container:${SERVER_CT}" \
    -v "$REPO_ROOT":/vhs -w /vhs \
    -e PATH="/vhs/tui:/usr/local/bin:/usr/bin:/bin" \
    "$VHS_IMAGE" scripts/capture/tui.tape
  rm -f "$REPO_ROOT/tui/lhb-tui"
}

build_jar
start_server
case "$TARGET" in
  webui) capture_webui ;;
  tui)   capture_tui ;;
  all)   capture_webui; capture_tui ;;
  *) echo "usage: $0 [all|webui|tui]"; exit 2 ;;
esac

echo "> done. Assets in docs/assets/:"
ls -la "$ASSETS"
