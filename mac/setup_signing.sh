#!/bin/bash
# One-time setup: create a stable self-signed code-signing identity so macOS TCC
# (Accessibility, Microphone, …) honors permission grants and KEEPS them across
# rebuilds. Ad-hoc signing has no stable identity, so every rebuild looks like a
# brand-new app and you'd have to re-grant Accessibility each time — and macOS often
# refuses to honor an ad-hoc app's Accessibility grant at all.
#
# Idempotent: re-running is a no-op if the identity already exists.
set -euo pipefail

IDENTITY="SpeakKeys Dev Cert"
KEYCHAIN="$HOME/Library/Keychains/login.keychain-db"

if security find-identity -v "$KEYCHAIN" 2>/dev/null | grep -q "$IDENTITY"; then
    echo "✓ Signing identity '$IDENTITY' already present — nothing to do."
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/openssl.cnf" <<'EOF'
[req]
distinguished_name = dn
x509_extensions = v3
prompt = no
[dn]
CN = SpeakKeys Dev Cert
[v3]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
EOF

echo "==> generating self-signed code-signing certificate"
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -keyout "$TMP/sk.key" -out "$TMP/sk.crt" -config "$TMP/openssl.cnf" 2>/dev/null

# -legacy is required: OpenSSL 3 defaults to a PKCS12 MAC algorithm macOS rejects.
openssl pkcs12 -export -legacy -inkey "$TMP/sk.key" -in "$TMP/sk.crt" \
    -out "$TMP/sk.p12" -passout pass:speakkeys -name "$IDENTITY" 2>/dev/null

echo "==> importing into login keychain"
security import "$TMP/sk.p12" -k "$KEYCHAIN" -P speakkeys -T /usr/bin/codesign -A

echo ""
echo "✓ Created signing identity '$IDENTITY'."
echo "  make_app.sh will now sign with it automatically."
