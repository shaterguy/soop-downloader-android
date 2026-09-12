# Release and signing

This repository is authorized for stable releases for every completed update. Keep the application ID fixed; increase both versionName and versionCode for each stable update. Never reuse or replace an existing tag or release asset.

## One-time signing initialization

1. Add repository Actions secret `SOOP_SIGNING_PASSPHRASE`: a unique randomly generated passphrase of at least 32 characters. Keep a recoverable copy in the owner's password manager. Do not put its value in chat, source, workflow inputs, logs, or artifacts.
2. Run `Initialize permanent signing identity` on `main`. It refuses to run if a key, certificate pin, or release already exists. The private key is generated on the hosted runner and only its AES256-encrypted PKCS12, public certificate, and certificate SHA256 are committed.
3. Bootstrap explicitly dispatches `Stable Android release` on `main` after preserving the encrypted key. Ordinary GitHub-token pushes alone do not recursively start workflows. If dispatch fails, rerun `Stable Android release` on `main`; never regenerate the signing key.

Subsequent releases decrypt the same keystore. Missing or wrong secret, missing encrypted keystore, mismatching certificate, changed application ID, or nonincreasing versionCode fails before publication. No debug-key or newly generated-key fallback is allowed. Plaintext signing material is confined to the runner temporary directory and removed in an always-run cleanup step.

The encrypted key in Git and secret/passphrase backup together preserve recovery. Losing the passphrase makes the encrypted backup unusable. Existing APK signatures and the pinned certificate fingerprint protect the update lineage.

## CI verification

The Gradle wrapper is executable. `testDebugUnitTest`, `lintRelease`, `assembleRelease`, and `assembleDebugAndroidTest` run before the Android 15 emulator verifies a real shared catch download. Gradle outputs `app/build/outputs/apk/release/app-release-unsigned.apk`; signing occurs after build. Build Tools expose aapt, zipalign, and apksigner. Public Actions above should be pinned to reviewed commit SHAs before final integration if project policy requires it.

Current connector provides contents/commit/tree/ref mutation, PR operations, workflow and artifact reads, and failed-job rerun. It explicitly excludes secrets API and exposes no native workflow dispatch. These are actual initial credential/trigger integration requirements, not reasons to skip producing app code and build configuration.
