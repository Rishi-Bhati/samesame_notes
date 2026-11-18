package org.samesame.notes.core.model

/**
 * Persisted app settings (the Settings screens). Defaults match the reference app: auto save on, show links on,
 * web previews off, hide scroll bar off, block Back off, toolbar add-ons on. "Style of new notes" defaults to
 * individual pages with a blank template and the default background.
 */
data class AppSettings(
    val autoSaveNotes: Boolean = true,
    val showLinks: Boolean = true,
    val showWebPreviews: Boolean = false,
    val hideScrollBar: Boolean = false,
    val blockBackKey: Boolean = false,
    val toolbarChangeStyle: Boolean = true,
    val toolbarEasyWritingPad: Boolean = true,
    val toolbarZoomLock: Boolean = true,
    val newNote: NewNoteStyle = NewNoteStyle(),
    val adjustColourInDarkMode: Boolean = true,
    /** Light, Dark or follow the system; [ThemeMode.System] by default. */
    val themeMode: ThemeMode = ThemeMode.System,
    /** "When pasting a file link": what pasting a link to a picture of the Files library does; [FilePasteMode.Ask] by default. */
    val filePasteMode: FilePasteMode = FilePasteMode.Ask,
    /** "Calculate as you type": show an inline result after a typed `=` that can be accepted. */
    val calculateAsYouType: Boolean = true,
    /** SAF tree URI of the chosen backup folder, or null when none is selected. */
    val backupFolderUri: String? = null,
    /** Automatic backup schedule; [BackupSchedule.Off] by default. */
    val backupSchedule: BackupSchedule = BackupSchedule.Off,
    /** Automatic backups run only while the device is charging. */
    val backupOnlyWhileCharging: Boolean = true,
    /** How many complete backups this app keeps in the chosen folder. */
    val backupKeepCount: Int = 5,
    /** Time of the last backup attempt, or null when none ran yet. */
    val backupLastTime: Long? = null,
    /** Notes in the last successful backup, or null when the last attempt failed or none ran yet. */
    val backupLastNoteCount: Int? = null,
    /** Error of the last backup attempt, or null when it succeeded or none ran yet. */
    val backupLastError: String? = null,
)

/** How often automatic backups run. */
enum class BackupSchedule { Off, Daily, Weekly }

/** Settings > "When pasting a file link" (Files). Only links to pictures ask; other files always paste as links. */
enum class FilePasteMode {
    /** Show the "Paste as link" / "Show in note" menu at the caret. */
    Ask,
    AlwaysLink,
    AlwaysShowInNote,
}

/** The app's colour theme, chosen in Settings. */
enum class ThemeMode {
    System,
    Light,
    Dark;

    /** Whether the UI is dark, given whether the system currently is. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }
}

/** "Style of new notes": page style, layout (page ratio), template and background colour. */
data class NewNoteStyle(
    /** [PageMode.Pages] = Individual pages, [PageMode.Infinite] = Infinite scrolling page. */
    val pageMode: PageMode = PageMode.Pages,
    /** [PageRatio.A4Portrait] = Normal, [PageRatio.Long] = Long (1:2). */
    val ratio: PageRatio = PageRatio.A4Portrait,
    val template: PageTemplate = PageTemplate.Blank,
    /** ARGB, or null for the default background. */
    val backgroundColor: Long? = null,
    /** How pages of an imported PDF are sized ("PDF templates" in Settings). */
    val pdfPageSize: PdfPageSize = PdfPageSize.Original,
)

/** Page treatment applied to pages imported from a PDF. */
enum class PdfPageSize {
    /** Keep each PDF page's own size (a landscape page stays landscape). */
    Original,

    /** Fit every page to the note's page shape ([NewNoteStyle.ratio]). */
    FitToNote,
}
