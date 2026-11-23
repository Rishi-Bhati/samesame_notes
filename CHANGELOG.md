# Changelog

All notable user-facing changes to SameSame notes are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
pre-release alpha versions.

## 0.4.0-alpha (2026-11-23)

A text and Markdown editor for files from other apps, live Markdown formatting, Markdown notes, save options for files you open, automatic version history and folder backups. It includes 0.3.2, which was never released on its own. It installs over 0.3.1-alpha with the same signing key and keeps your notes.

### Open any file

- Text, Markdown, CSV, JSON, XML, YAML, code and HTML files open in a text editor when another app sends them to SameSame, from "Open with" or a file manager. Save writes back to the file when the other app allows it.
- Find and replace, undo and redo, line wrap and monospace.
- HTML files can be shown rendered. Links in rendered HTML don't open other apps.
- A complete read-only file from another app shows "Read-only". "Edit as note" saves a copy as a note and opens it. A file over 5 MB opens read-only and shows its first 5 MB.
- Files that aren't text, such as a PDF or a picture, go to the "Save to SameSame" sheet instead of showing raw bytes.

### Markdown

- Live formatting as you type: headings, bold, italic, ~~strikethrough~~, code, quotes, lists, task boxes, links and rules. The marks hide when you leave the line and come back when you edit it. A heading needs a space: '# hello'. '#hello' stays as text.
- Tap a task box to tick it. Tap a link to open or edit it. Read-only Markdown never shows its marks on the caret line, and its task boxes can't be ticked.
- Choose Live, Source or Preview from the toolbar or ⋮. Each document remembers its view. Settings > Advanced > Markdown editing sets the default.
- Preview opens only web and email links.
- Markdown notes: ⋮ > New Markdown note on the home screen. They are listed, searched, locked and trashed like other notes. Locked Markdown notes open read-only.
- Export Markdown as HTML, PDF or Word.

### Save options for files you open

- Save in the text editor offers: save changes, save as a note (Markdown files become Markdown notes), save to Files, save a copy, and export as PDF, HTML or Word.
- Files shared to SameSame can be saved to Files, as a new note, as a copy, or (text and pictures) as a PDF. Several pictures become one PDF.

### Editing

- Hardware keyboards work in notes: typing, Enter, Backspace, Delete, Tab, arrows (hold Shift to select), Home and End, Ctrl+arrows by word, and Ctrl+Z, Y, A, C, X, V, B, I and U.
- Arrows (→ ← ↔ ⇒ ⇔ and the rest) are drawn a little bigger and bolder in notes. The saved text doesn't change.

### Widgets

- Quick capture changes shape as you resize it. Make it about square and it shows one big New note button in the middle with four buttons in the corners: Checklist, Voice, Handwriting and Camera.
- Choose the buttons in the widget's settings: the main button and each corner, from New note, Checklist, Voice note, Handwriting, Drawing, Camera, Picture, Markdown note and Open the app. A corner can be left empty. The wide layouts use the same buttons.
- Camera takes a photo straight into a new note.

### Safety

- Automatic version history: earlier versions of a note can be viewed and restored.
- Backup to a folder you choose, daily or weekly, with restore. You choose how many backups to keep, from 1 to 10. Restore adds notes and never overwrites or deletes existing ones. Locked notes stay encrypted in backups and history.

### Fixes

- Delete page now really removes the page. The typed text on that page is removed with it, and Undo brings both back.
- Calculations work after a label: 'Udupi -> Mangalore: 100*7 =' shows 700. The currency in front ('Rent: ₹1,000 * 12 =') is kept. Times, dates and phone numbers are still ignored.

### Security

- A security review of this version found no critical or high issues. Two medium and five low issues were fixed: links in the HTML preview, very deeply nested Markdown, unsafe link types in Markdown and exports, edit access for files from other apps, and restore file names.

### Known limits

- Live view shows Markdown tables as plain text with pipes. Preview draws them as a table.
- Markdown PDF export loses table and code styling. Tables become plain lines and code blocks become plain text.
- Folder backups keep unlocked copies of a note made before the note was locked, until those backups are deleted. Locking a note does not change them.
- The bigger arrows show only in the editor. PDF and image exports and note previews use the normal size.

### Upgrading

- Installs over 0.3.1-alpha with the same signing key and keeps your notes.
- The database gets a note-type column, added automatically. The note format moves to version 10, and older notes are still read.

## 0.3.1-alpha (2026-11-18)

Typing shortcuts for arrows and files, smarter totals, checklist progress, tap-to-act for addresses, deleting pages, and a font size and style page. It installs over 0.3.0-alpha with the same signing key and keeps your notes.

### Typing

- Arrows: '->', '<-', '<->', '=>' and '<=>' become →, ←, ↔, ⇒ and ⇔ as you type. Backspace right after brings the typed characters back.
- Turn arrows off with "Replace arrows as you type" in Settings. '--' is not changed.
- Type '///' in a note to pick a file from Files. Type to filter, then tap or press Enter to insert it, as Insert > From Files does. One Undo removes it.
- Turn off with "Insert files with ///" in Settings.

### Calculate as you type

- Type 'Total', 'Sum', 'Subtotal', 'Grand total', 'Average' or 'Avg', 'Count', 'Max' or 'Min' on its own line under a list of labelled amounts, and the result appears. No '=' needed.
- Works with lines like 'Milk 60', 'Rent: ₹12,000', '2k', '1.5L', 'Eggs 2 x 72' and 'Refund -200'.
- Keeps the currency and comma style. Counted amounts are lightly underlined.
- Enter accepts the result on these keyword lines. Enter after an ordinary '=' expression still makes a new line.
- Blank lines, headings, lines ending in ':' and earlier totals end a list.
- Dates, times and phone numbers are skipped. Mixed currencies show nothing.

### Checklists

- Ticked items move below unticked ones. Turn it off with "Move checked items to the bottom" in Settings. One Undo restores the order.
- Note cards show progress such as 3/7. Not shown for locked notes. Existing notes show it after their next save.
- Enter on an empty item ends the list.

### Links

- Tapping a phone number offers Call, Send message and Copy.
- Tapping an email offers Send email and Copy.
- Tapping a street address (new) offers Open in Maps and Copy.

### Pages

- ⋮ > Delete page removes the current page. It asks first unless the page is empty. Typed text is kept and moves to the next pages.
- Backspacing from an empty new page back to the previous page removes that page.
- A new page is never removed on its own. Undo brings a deleted page back.

### Font size and style

- Settings > Font size and style sets one size for the whole app (85-150%), and separate sizes for the Notes list, the Editor (size of new text), Toolbars and menus, Files and the Drawer.
- Fonts: System default, Atkinson Hyperlegible, IBM Plex Serif (both bundled, open font licence), or your own .ttf or .otf up to 10 MB.
- A missing custom font falls back to the system font. Saved notes are never changed.
- Widgets follow the phone's font size.
- A changed font applies to an open note the next time it is opened.

### Upgrading

- Installs over 0.3.0-alpha with the same signing key and keeps your notes.
- The database gets two new columns for checklist counts. They are added automatically.

## 0.3.0-alpha (2026-11-17)

Widgets, calculate as you type, more Files tools, and opening text, photos and audio as notes. It installs over 0.2.1-alpha with the same signing key and keeps your notes.

### Widgets

- Note creator widget: start a note with the keypad, pen, brush, a picture or a voice recording, plus a shortcut to the app.
- Note widget showing the top of one note.
- Notes widget with List, Pages or Automatic display. It replaces the separate notes list and pages widgets; widgets already placed keep working.
- Quick capture widget: opens a small capture sheet over the home screen. It saves when you tap Save, go back or tap outside.
- Background White, Black or Match dark mode, with opacity.
- A locked note never shows its content on a widget.

### Calculate as you type

- Type an expression and '=' to see the result faded inline.
- Accept by swiping right on the keyboard spacebar, double space, finger swipe over the ghost, tapping it or Tab.
- Units and conversions with examples '1 km + 102 m =' and '5 km in m ='.
- Percentages.
- Sum/total/avg of the lines above.
- A setting to turn it off.

### Files

- Upload a whole folder with its subfolders.
- Zip export of a folder, and of a note as Markdown or HTML with its linked files.
- Your own drag order.
- Favourites.
- Tags and a short description per file, with filter chips and tag search.

### Open with and Share

- Text files (.txt, .md, .csv, .log, .tsv up to 2 MiB) open as a new note.
- Photos open as a new note with the picture upright, and a copy is kept in Files.
- Audio files open as a new note with a voice recording, and a copy is kept in Files.
- Shared text or links from other apps become a new note.
- "Open with" lists the app only for files it can open (PDF, notes, text, pictures, audio). "Share" still accepts every
  file type and saves the rest to Files.

### Editor

- Checkbox and number or bullet together on one line.
- New text-size glide: the size under your finger is the one you get, with a live preview in the note.
- Top bar title: tap at or after the title places caret at the end without opening the expanded header.

### Settings

- Check for updates in Settings > About. It checks only when you tap it.

### Privacy

- The app now declares the INTERNET permission. It is used only by Check for updates: one HTTPS request to GitHub's
  releases API when you tap it. Nothing about your notes is sent, and nothing runs automatically.

### Fixes

- A picture shown in a note from Files now behaves like any picture: move, resize, rotate and the full menu, and keeps its Files link.
- Checkboxes and list markers line up at every text size.
- Smoother scrolling, with image caches sized to the phone's memory.
- Security hardening: stricter checks on files received from other apps and on edited pictures returned by other apps,
  stricter archive extraction, widget setup screens kept out of recent apps, and calculation suggestions are no longer
  written to the system log.

### Upgrading

- Notes edited in 0.3.0-alpha use a newer note format; don't go back to 0.2.x after upgrading.

## 0.2.1-alpha (2026-11-12)

### Fixes

- Links in an open note are now drawn fully in the link blue (text and underline). Before, text with its own colour
  showed only a blue underline while the note previews were already blue.

## 0.2.0-alpha (2026-11-11)

A Files alpha on top of 0.1.1-alpha, with a much more complete `.sdocx` import and export. It installs over the previous build and keeps your notes and settings.
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

### `.sdocx` files

- Handwriting in imported `.sdocx` notes now shows and can be edited: all pens, the highlighter, colours, sizes and
  multi-page notes. Strokes the app can't read are kept untouched and written back unchanged.
- Edits to the handwriting of an imported note (moving, deleting, adding, recolouring and resizing strokes) are
  written back when you export it again, and an unedited note still exports back unchanged.
- Notes made in SameSame notes export to `.sdocx` with real strokes (before, the page was written as a flat picture).
  Exported files are now written with the same structure as files from the original app. This is new and still
  experimental: please report any exported file that does not open.
- Known limits: pages added or removed in an imported note and objects placed on its pages are not written back to
  `.sdocx`. In notes made here, floating pictures lose their position, floating text boxes become paragraphs, and
  page templates and PDF backgrounds are not written. Pressure and tilt are not written. Locked `.sdocx` notes
  are imported unlocked, and export back locked.

### Fixes

- "Show in note" on a picture now shows the picture straight away. The text caret on a line that holds a picture
  stays text-sized instead of taking the picture's height.
- The Files folder tiles match the folder cards on the home screen.
- Home list and Files screen scrolling is smoother: less work per frame while scrolling, and thumbnails of files that
  have scrolled away are no longer decoded.
- The font size picker glide is now a joystick. Hold the size button and slide: the further your finger is from the
  neutral point, the faster the list scrolls, and bringing the finger back stops it. With the keyboard up the neutral
  point is where you pressed; with the keyboard down it is the middle of the list.
- The "Try again in N seconds" message after too many wrong passwords now counts down live instead of staying frozen.
- Manage folders: long-press a folder to create a subfolder inside it; folders are listed as a tree.
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
