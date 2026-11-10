# Changelog

All notable user-facing changes to SameSame notes are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
pre-release alpha versions.

## 0.2.0-alpha (unreleased)

A Files alpha on top of 0.1.1-alpha. It installs over the previous build and keeps your notes and settings.
The 0.1.2 fixes ship inside this release; they are listed under Fixes.

Upgrading keeps your notes: 0.2.0-alpha is signed with the same key as 0.1.1-alpha, so it installs over it in place
and keeps notes, folders, favourites, locked notes and voice recordings.

### Files

- Files, a new screen opened from the drawer: import any file as a local copy (nothing is uploaded), organise files in
  folders, grid or list view, sort, search, rename, move, share, a Recycle bin and a Storage page.
- SameSame notes appears in Share and Open with for every file type. A file that can't go into a note is saved to Files.
- Built-in viewers for images, PDFs (read-only), text files and audio. Other types open in another app.
- Copy link on any file. Pasting a link into a note asks "Paste as link" or "Show in note" for pictures; the choice can
  be fixed in Settings. Tapping a link opens the file; a file's Info lists the notes that use it.
- Insert > From Files in the editor, and Open in Files / Search Files from a link.
- Exports keep file links (txt, md, HTML, docx, pptx, PDF). A SameSame note file can include its linked files and
  restores them on import.

### Fixes

- Drawer rows for Favourites and Locked notes after All notes, each with a count and shown only when the count is
  above zero.
- Cut in the view state, next to Copy, for text selected in a note you are reading.
- Home in landscape shows a toolbar-only header so the note list gets the screen height; portrait restores the full
  header.
- The press-and-glide font size picker opens on hold and stays open while the finger is still. It moves one size per
  step of finger travel from the current size, applies the size on release, and cancels when the finger slides back
  onto the toolbar.
- The sort-direction arrow did nothing while the scroll bar was showing; it now flips the order in grid and list
  views.
- The home scroll bar jumped while scrolling up; the thumb now moves smoothly.
- The drawer Folders row, Manage folders and Select folder showed the number of unfiled notes instead of folders.
- Folder cards showed 0 for folders whose notes sit in subfolders; they now count their subfolders' notes too.
- Folder cards fit only three per row on a phone; they now fit four in portrait.
- Pasting multi-line text into the home search box left only the first line; line breaks now become spaces.
- The highlighter opacity slider did not move; dragging now changes the value and the next stroke uses it.
- The size picker chose different sizes from the same finger travel at different speeds, and a hold without moving
  closed the popup.
- After choosing Body, the next one-step glide skipped to 18; it now goes to 16.
- Select all did not cover list numbers and bullets, and copying dropped them; copied plain text keeps the markers
  ("1. ", bullets and "[x]").
- Voice playback stopped whenever the app went to the background; it now keeps going with the screen off or in another
  app, and stops when the note is closed. Locked notes still pause in the background.
- Duplicate was offered for locked notes and did nothing when tapped; it is now hidden when a locked note is selected.
- Locked notes could be deleted (move to Recycle bin, delete forever, empty the Recycle bin, delete a folder holding
  them) without a password or biometric check. Deleting now asks for identity first, and cancelling deletes nothing.

## [0.1.1-alpha] - 2026-11-02

A bug-fix alpha on top of 0.1.0-alpha. It installs over the previous build and keeps your notes and settings.
Existing notes open and migrate in place. Note files written by 0.1.1 cannot be opened by a 0.1.0 app, so there
is no downgrade; export anything important before going back.

### Added

- Locked notes now show their titles in the home grid, list and search results. Content stays hidden until the
  note is unlocked, and only the title is searchable.
- Editor panels, dialogs, menus, the in-note search, the toolbar state and the home filter and selection now
  survive rotation and the system reclaiming the app in the background.
- Picture and PDF pages in a dark note stay light, and text boxes edited on them keep their colours.

### Changed

- Tapping the note title while viewing a note now starts editing the title, with the keyboard up and the caret at
  the end.
- Pulling up past the last page now animates into the new page without a flash or jump, in the real colour of the
  incoming page.
- The lock and unlock pages slide in and out, and Continue becomes a progress circle while the password is
  checked.
- Rotating the device keeps the editor open and keeps voice playback and recording running. Landscape now fits
  the home grid, panels, dialogs and camera preview.
- Opening or scrolling a note no longer rewrites it or changes its modified date. The reading position is stored
  separately from the note.
- Home scrolling is smoother: previews are cached and reused instead of re-rendered, and the editor is composed
  once when a note opens.
- The app now ships a baseline profile, so more code is optimized at install time for faster startup and smoother
  scrolling.
- Locking and unlocking now seals or unseals the container directly and writes less data. Large locked notes that
  were only read close almost instantly, and up to three lock or unlock actions can run in parallel.
- Checking a password no longer repeats the expensive key derivation on every attempt.

### Fixed

- The highlighter drew in blue and changed colour when the pen was lifted, on every pen and highlighter tip.
- Locked notes were listed as "Locked note" even when they had a title, and locked titles could not be searched.
- Ink, highlighter, typed text and objects on PDF pages were invisible in dark mode.
- Locking from the editor briefly showed a blank page.
- Tapping below the text on a PDF page now puts the caret where you tapped and extends the text to that page,
  instead of jumping to page 1.
- Closing a new note that only gained blank pages now discards it, so no empty entry is left in the list or the
  Recycle Bin.
- Dragging the home scroll bar no longer makes the thumb jump or drift, and the thumb follows the list.
- A locked note's newer saved edits are no longer discarded when a save fails; the app keeps the newer copy and
  reports the error.

### Security

- A locked note is kept safe if a save fails: the plaintext copy survives until the encrypted container is
  verified to hold the saved note, and the final save on close is retried.
- Locked note bodies are never indexed or shown in search; only their titles are matched.

## [0.1.0-alpha] - 2026-11-01

First public alpha release.

### Added

- Handwriting with pens, a highlighter, an eraser with stroke and area modes, lasso selection, stroke
  straightening and an easy writing pad.
- Rich text with lists, checklists, headings, colours, undo and redo, plus text boxes, images and drawings on the
  page.
- Pages with templates and per-page sizes, a page sorter, reading mode and search inside a note.
- Voice recordings that play back from a recording bar, with a list to rename, share and delete them.
- Nested folders, grid and list views, sorting, favourites, multi-select, full-text search and a 30-day trash.
- Per-note password locking with optional biometric unlock, plus blocked screenshots while a locked note is open.
- Import and export of `.ssnote`, `.sdocx`, PDF, PNG and JPEG images, plain text, Markdown, HTML, Word and
  PowerPoint, including bulk import and export.
- Light, dark and system themes.

### Fixed

- Partial-word search now matches word prefixes.

[0.2.0-alpha]: https://github.com/Rishi-Bhati/samesame_notes/compare/v0.1.1-alpha...v0.2.0-alpha
[0.1.1-alpha]: https://github.com/Rishi-Bhati/samesame_notes/compare/v0.1.0-alpha...v0.1.1-alpha
[0.1.0-alpha]: https://github.com/Rishi-Bhati/samesame_notes/releases/tag/v0.1.0-alpha
