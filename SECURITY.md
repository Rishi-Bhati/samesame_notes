# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub private vulnerability reporting:
open the repository's **Security** tab and choose **Report a vulnerability**. Do not open a
public issue for a security problem.

A useful report includes the affected version or commit, the steps to reproduce the problem,
and the impact you expect.

## Supported versions

Security fixes are made on the latest commit of `main`. The project is in alpha and does not
maintain fixes for older releases.

## Security model

- SameSame notes runs entirely on the device and declares no `INTERNET` permission, so the app
  never sends notes anywhere.
- Note content is stored as files in the app's private storage; the database is a catalog and
  search index, not the note content itself.
- A locked note is encrypted with AES-GCM, using a key derived from the user's password with
  PBKDF2-HMAC-SHA256.
- Optional biometric unlock releases a key held in the Android Keystore.
- Android backups include the catalog and note files but never the key for a locked note, so a
  restored locked note cannot be opened without the original key.
- Files received from other apps are copied into app storage; sharing and export go through a
  content provider limited to the app's own cache and share directories.
- Release builds are minified and not debuggable, and the main activity is the only exported
  component.
- Dependency versions and checksums are pinned; the Gradle wrapper and the dependency
  verification metadata are committed and validated in continuous integration.
