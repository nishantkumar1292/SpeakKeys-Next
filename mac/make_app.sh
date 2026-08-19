#!/bin/bash
# Build SpeakKeysMac and assemble it into a proper .app bundle, then ad-hoc sign it.
# macOS ties microphone / accessibility permissions to a signed app bundle, so we
# always run the bundle (not the bare SwiftPM binary).
set -euo pipefail
cd "$(dirname "$0")"

CONFIG="${1:-release}"          # release (default) or debug
APP="build/SpeakKeys.app"
EXE_NAME="SpeakKeysMac"

echo "==> swift build -c $CONFIG"
swift build -c "$CONFIG"

BIN_PATH="$(swift build -c "$CONFIG" --show-bin-path)/$EXE_NAME"
if [[ ! -f "$BIN_PATH" ]]; then
    echo "error: built binary not found at $BIN_PATH" >&2
    exit 1
fi

echo "==> assembling $APP"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_PATH" "$APP/Contents/MacOS/$EXE_NAME"
cp Info.plist "$APP/Contents/Info.plist"

# Sign with the stable self-signed identity if present (see setup_signing.sh) so
# macOS TCC honors and PERSISTS permission grants across rebuilds. Falls back to
# ad-hoc, but ad-hoc apps often won't keep their Accessibility grant.
# Note: a self-signed cert is "not trusted" so `find-identity -v` hides it, but
# `find-identity` (no -v) lists it and codesign can still use it by name.
SIGN_ID="SpeakKeys Dev Cert"
if security find-identity 2>/dev/null | grep -q "$SIGN_ID"; then
    echo "==> codesign with '$SIGN_ID'"
    codesign --force --sign "$SIGN_ID" --identifier com.speakkeys.mac "$APP"
else
    echo "==> codesign (ad-hoc — run ./setup_signing.sh for a stable identity)"
    codesign --force --sign - "$APP"
fi

echo ""
echo "Built: $APP"
echo "Run it with:  open \"$PWD/$APP\""
echo "(The microphone icon appears in the menu bar — there is no Dock icon.)"
