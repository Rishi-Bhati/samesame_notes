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

- SameSame notes keeps notes on the device. The app declares the `INTERNET` permission only for the manual
  Check for updates in Settings, which sends one HTTPS request to the GitHub releases API when the user taps it;
  notes are never sent anywhere. 0.4.0-alpha adds no new permissions: it declares the same set as 0.3.1-alpha.
- Note content is stored as files in the app's private storage; the database is a catalog and
  search index, not the note content itself.
- A locked note is encrypted with AES-GCM, using a key derived from the user's password with
  PBKDF2-HMAC-SHA256.
- Optional biometric unlock releases a key held in the Android Keystore.
- Android backups include the catalog and note files but never the key for a locked note, so a
  restored locked note cannot be opened without the original key.
- Backups to a folder (Settings > Backup) are written to the folder the user picks, which other apps with
  storage access may be able to read. Locked notes stay encrypted there and their titles are left out; every
  other note and the Files library are stored as they are. Version history keeps only encrypted copies of locked
  notes, and a note's versions are deleted when it is locked. Folder backups made before the lock keep their plain
  copy until those backups are deleted.
- Files received from other apps are copied into app storage; sharing and export go through a
  content provider limited to the app's own cache and share directories.
- Release builds are minified and not debuggable. The exported components are the main activity, the share target,
  the text editor (`TextDocActivity`), the widget configuration activities and the widget receivers. The text editor
  accepts only `content://` URIs from other apps, with `text/*`, `application/json`, `application/xml`,
  `application/javascript`, `application/x-sh`, `application/yaml` or `application/toml` types. It writes back only
  when the sender granted write access. Notes and library files open in `NoteTextDocActivity`, which is not exported.
  All other components are not exported.
- Text, Markdown and HTML files from other apps are untrusted: HTML is previewed without JavaScript, network, file
  access or navigation; a link in Markdown opens only http, https or mailto targets, and a web link in a note opens
  only in apps that accept browser links. Exports (HTML, Word, PowerPoint) drop script and file links.
- Dependency versions and checksums are pinned; the Gradle wrapper and the dependency
  verification metadata are committed and validated in continuous integration.

## Security review of 0.4.0-alpha

Before the 0.4.0-alpha release, the code for the text editor, Markdown, exports, backups and version history was
reviewed. The review found no critical or high issues. Two medium and five low issues were found and fixed, with tests:

- An HTML file from another app could start other apps through a link or a redirect. The HTML preview no longer
  follows links.
- Very deeply nested Markdown could crash the app in Preview and in exports. Parsing now falls back to plain text,
  and nesting is capped.
- The Markdown preview opened any kind of link, and exports kept unsafe link targets such as script links. The preview
  now opens only web and e-mail links, and exports keep only safe kinds of link.
- A web link stored in a note opens only in apps that accept browser links.
- A flag from another app cannot make a file editable. Only a write grant on the file does.
- Restoring a folder backup uses random temporary names and sanitised file names.

Two low-impact recommendations are left for a later release: importing a note file that another app sends directly
does not ask first, and changing the backup folder keeps access to the old folder.

