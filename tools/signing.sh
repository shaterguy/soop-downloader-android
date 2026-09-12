#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${SOOP_SIGNING_PASSPHRASE:?Set repository secret SOOP_SIGNING_PASSPHRASE before release}"
test "${#SOOP_SIGNING_PASSPHRASE}" -ge 32 || { echo "Signing passphrase must have at least 32 characters" >&2; exit 1; }
mode="${1:?bootstrap or restore}"
: "${RUNNER_TEMP:?Signing runs only in CI runner}"
keydir="$RUNNER_TEMP/soop-signing"
mkdir -p "$keydir"
printf '%s' "$SOOP_SIGNING_PASSPHRASE" > "$keydir/passphrase"
export SOOP_KEYSTORE="$keydir/release.p12"
if [[ "$mode" == bootstrap ]]; then
  test ! -e signing/release.p12.gpg
  test ! -e signing/certificate.sha256
  mkdir -p signing
  keytool -genkeypair -noprompt -keystore "$SOOP_KEYSTORE" -storetype PKCS12 \
    -storepass:env SOOP_SIGNING_PASSPHRASE -keypass:env SOOP_SIGNING_PASSPHRASE \
    -alias soop-release -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
    -validity 36500 -dname 'CN=SOOP Downloader, OU=Android, O=shaterguy, C=KR'
  keytool -exportcert -keystore "$SOOP_KEYSTORE" -storepass:env SOOP_SIGNING_PASSPHRASE \
    -alias soop-release -file "$keydir/certificate.der"
  sha256sum "$keydir/certificate.der" | cut -d ' ' -f 1 > signing/certificate.sha256
  keytool -exportcert -rfc -keystore "$SOOP_KEYSTORE" -storepass:env SOOP_SIGNING_PASSPHRASE \
    -alias soop-release -file signing/certificate.pem
  gpg --batch --yes --pinentry-mode loopback --passphrase-file "$keydir/passphrase" \
    --symmetric --cipher-algo AES256 --s2k-mode 3 --s2k-count 65011712 \
    --output signing/release.p12.gpg "$SOOP_KEYSTORE"
elif [[ "$mode" == restore ]]; then
  test -s signing/release.p12.gpg
  test -s signing/certificate.sha256
  gpg --batch --yes --pinentry-mode loopback --passphrase-file "$keydir/passphrase" \
    --output "$SOOP_KEYSTORE" --decrypt signing/release.p12.gpg
else
  exit 2
fi
keytool -exportcert -keystore "$SOOP_KEYSTORE" -storepass:env SOOP_SIGNING_PASSPHRASE \
  -alias soop-release -file "$keydir/check.der"
actual="$(sha256sum "$keydir/check.der" | cut -d ' ' -f 1)"
expected="$(tr -d '\r\n ' < signing/certificate.sha256)"
test "$actual" = "$expected"
printf 'SOOP_KEYSTORE=%s\n' "$SOOP_KEYSTORE" >> "$GITHUB_ENV"
