#!/usr/bin/env bash
# One-time setup of the PERMANENT APK signing key.
#
# Android only installs an update over an existing app when both are signed with the same key.
# Until this has been run, CI signs every build with a throwaway key, which is why each new APK
# demands an uninstall first. Run this once (Git Bash, from anywhere inside the repo):
#
#     bash tools/setup-signing.sh
#
# It creates the key OUTSIDE the repo (~/.pogo-signing), and stores it as two GitHub Actions
# secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD) that both workflows read.
#
# BACK UP ~/.pogo-signing. If the key is ever lost, every user has to uninstall and reinstall,
# and a Play Store listing could never be updated again.
set -euo pipefail

DIR="$HOME/.pogo-signing"
KEYSTORE="$DIR/release-keystore.p12"
PASSFILE="$DIR/password.txt"

# Use the openssl bundled with Git for Windows (other copies on PATH often lack a config file).
OPENSSL=openssl
for cand in /mingw64/bin/openssl.exe /usr/bin/openssl.exe "/c/Program Files/Git/mingw64/bin/openssl.exe"; do
  [ -x "$cand" ] && { OPENSSL="$cand"; break; }
done
"$OPENSSL" version >/dev/null 2>&1 || { echo "openssl not found (run this from Git Bash)"; exit 1; }
echo "Using $OPENSSL ($("$OPENSSL" version))"
command -v gh >/dev/null || { echo "GitHub CLI (gh) not found"; exit 1; }

mkdir -p "$DIR"
chmod 700 "$DIR" 2>/dev/null || true

if [ -f "$KEYSTORE" ] && [ -f "$PASSFILE" ]; then
  echo "Reusing the existing key in $DIR"
else
  echo "Creating a new signing key in $DIR"
  PASS=$("$OPENSSL" rand -hex 24)
  echo "  1/3 generating RSA key + certificate"
  # MSYS_NO_PATHCONV stops Git Bash rewriting "/CN=..." into a Windows path, but it also stops
  # it converting file paths, so those are handed over in Windows form.
  WDIR=$(cygpath -m "$DIR")
  MSYS_NO_PATHCONV=1 "$OPENSSL" req -x509 -newkey rsa:2048 -sha256 -days 10000 -nodes \
    -keyout "$WDIR/key.pem" -out "$WDIR/cert.pem" \
    -subj "/CN=PoGo Companion/O=PoGo Field Guide/C=US"
  echo "  2/3 packing the PKCS12 keystore"
  "$OPENSSL" pkcs12 -export -inkey "$WDIR/key.pem" -in "$WDIR/cert.pem" \
    -name pogo-release -out "$(cygpath -m "$KEYSTORE")" -passout "pass:$PASS"
  echo "  3/3 keystore written"
  rm -f "$DIR/key.pem" "$DIR/cert.pem"
  printf '%s' "$PASS" > "$PASSFILE"
fi

echo "Uploading to GitHub Actions secrets..."
base64 -w0 "$KEYSTORE" | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD < "$PASSFILE"

echo
echo "Done. Builds from now on share one signature and install over each other."
echo "One last uninstall is needed on each phone to switch from the throwaway-signed app."
echo "BACK UP THIS FOLDER somewhere safe: $DIR"
