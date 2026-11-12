# Security and release readiness

**DO NOT USE WHILE DRIVING.** CarVideo is a research prototype using the Android Auto navigation Surface for non-navigation video. It does not reliably detect the vehicle's driving state. The OEM reverse camera must always take priority.

## Not a production-safety release

The following are **not demonstrated**:
- Guaranteed parked-only blocking or a verified safety interlock
- Google Play approval for Android Auto video applications
- Broad compatibility with other Android Auto versions or vehicles
- Battery/thermal endurance and multiple codec combinations
- Reproducible signed-update chain or app-store release approval

This is why source publication is distinct from distributing a production APK.

## Signing

Never commit .jks/.keystore files, signing passwords, media access tokens, personal logs or screenshots of passengers.

CI preview APKs use a temporary debug signing identity. To publish a stable signed release, the maintainer must choose a permanent application ID, create a private keystore, back it up securely, configure Gradle signing from a secure secret store, verify the signed APK, and test an upgrade over the previous signed APK without data loss. A temporary CI key **cannot** be used as a stable update signing identity.

## Responsible disclosure

Please report security or privacy issues privately to the repository owner where possible rather than posting sensitive data in public issues.
