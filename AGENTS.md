# SOOP Downloader repository instructions

The owner explicitly authorizes direct stable releases for completed changes in this repository, including future updates. After relevant verification, publish a normal GitHub Release with a signed APK; do not default to prereleases or ask again for stable publication permission.

Keep the established application ID and permanent signing identity unchanged. Increase versionName and versionCode for every released update. Never regenerate a signing key as a build fallback or replace an existing release/tag. Maintain the certificate SHA256 pin and compare existing stable APK signatures and versionCode before publishing.

Generate signing private keys only on the CI runner; never copy plaintext production keys into local workspaces, source, logs, or artifacts. `SOOP_SIGNING_PASSPHRASE` belongs in repository Actions Secrets and an owner-controlled password-manager backup. Only the encrypted keystore and public certificate may be committed. If signing prerequisites are missing, finish build and implementation evidence, then report the specific missing prerequisite; do not deliver an unsigned APK as installable.

The application must choose the highest actually offered video quality and retain original streams without re-encoding where supported. Never silently downgrade after a highest-quality failure. Keep icon resources, download status, cancellation, persistence, and Android installation compatibility in scope for relevant changes.
