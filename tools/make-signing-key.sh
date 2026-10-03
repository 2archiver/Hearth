#!/usr/bin/env bash
#
# make-signing-key.sh — create your own PhairPlay APK signing key.
#
# WHY: every PhairPlay build ships signed with the public "community build" key in
# app/signing/phairplay.p12, which is what makes "install the new APK over the old one" work
# instead of failing with "App not installed as package conflicts with an existing package".
# That key is committed to the repository, so it is public: anyone can build an APK signed
# with it.
#
# If you publish PhairPlay builds to other people, make your own key with this script and
# point the build at it. Updates then work for your builds and nobody else can forge them.
#
# Usage:
#   tools/make-signing-key.sh                      # → phairplay-signing.p12 (alias phairplay)
#   tools/make-signing-key.sh my-key.p12 myalias
#
# Then build with it:
#   KEYSTORE_PATH=$PWD/phairplay-signing.p12 \
#   KEYSTORE_PASSWORD=... KEY_ALIAS=phairplay KEY_PASSWORD=... \
#     ./gradlew :app:assembleGoogletvRelease
#
# Or in GitHub Actions, as secrets: KEYSTORE_BASE64 (base64 -w0 of the file),
# KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
#
# Back the keystore up. If you lose it you can never publish another in-place update.
#
# See docs/RELEASING.md.

set -euo pipefail

OUT="${1:-phairplay-signing.p12}"
ALIAS="${2:-phairplay}"
DAYS="${DAYS:-10950}"          # 30 years
BITS="${BITS:-2048}"

if ! command -v openssl >/dev/null 2>&1; then
    echo "error: openssl is required" >&2
    exit 1
fi

if [[ -e "$OUT" ]]; then
    echo "error: $OUT already exists — refusing to overwrite a signing key" >&2
    exit 1
fi

read -r -s -p "Keystore password (input hidden): " PASSWORD
echo
if [[ -z "$PASSWORD" ]]; then
    echo "error: an empty password is not allowed" >&2
    exit 1
fi
read -r -s -p "Confirm password: " PASSWORD2
echo
if [[ "$PASSWORD" != "$PASSWORD2" ]]; then
    echo "error: passwords do not match" >&2
    exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "Generating a ${BITS}-bit RSA key…"
openssl req -x509 -newkey "rsa:${BITS}" -sha256 -days "$DAYS" -nodes \
    -keyout "$tmp/key.pem" -out "$tmp/cert.pem" \
    -subj "/C=AU/ST=Queensland/L=Brisbane/O=PhairPlay/OU=Release/CN=PhairPlay" \
    -addext "basicConstraints=CA:FALSE" \
    -addext "keyUsage=digitalSignature" \
    -addext "extendedKeyUsage=1.3.6.1.5.5.7.3.3" 2>/dev/null

# PBES2/AES-256-CBC + PBKDF2-HMAC-SHA256: what a current JDK reads without extra config.
openssl pkcs12 -export \
    -inkey "$tmp/key.pem" -in "$tmp/cert.pem" \
    -name "$ALIAS" \
    -out "$OUT" \
    -passout "pass:${PASSWORD}" 2>/dev/null

chmod 600 "$OUT"

echo
echo "Wrote $(pwd)/$OUT"
echo
echo "  alias        $ALIAS"
echo "  type         PKCS12 (RSA ${BITS}, self-signed, ${DAYS} days)"
echo
echo "Build with it:"
echo "  KEYSTORE_PATH=\$PWD/$OUT KEYSTORE_PASSWORD=… KEY_ALIAS=$ALIAS KEY_PASSWORD=… \\"
echo "    ./gradlew :app:assembleGoogletvRelease"
echo
echo "Verify an APK was signed with it:"
echo "  \$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs PhairPlay-googletv.apk"
