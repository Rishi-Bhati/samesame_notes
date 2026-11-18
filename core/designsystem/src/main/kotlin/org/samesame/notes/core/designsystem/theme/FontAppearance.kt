package org.samesame.notes.core.designsystem.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import org.samesame.notes.core.designsystem.R
import org.samesame.notes.core.model.BundledFonts
import org.samesame.notes.core.model.CustomFontFile
import org.samesame.notes.core.model.FontKind
import org.samesame.notes.core.model.FontSection
import org.samesame.notes.core.model.FontSettings
import java.io.File
import java.io.InputStream

/**
 * The font settings as the UI uses them: scales per section, the Compose font family and the platform typeface (the
 * editor paints with a `Typeface`). [family] is null for System default, which keeps today's look.
 */
@Immutable
class FontAppearance(
    val settings: FontSettings = FontSettings(),
    val family: FontFamily? = null,
    val typeface: Typeface? = null,
    /** The custom font was chosen but its file is missing or broken, so System default is shown. */
    val customMissing: Boolean = false,
) {
    fun scaleFor(section: FontSection): Float = settings.scaleFor(section)
}

internal val LocalFontAppearance = staticCompositionLocalOf { FontAppearance() }

/** The device's own font scale, before the app's multipliers. */
internal val LocalSystemFontScale = staticCompositionLocalOf { 0f }

/** The app/section multiplier currently folded into the density's font scale. */
val LocalExtraFontScale = compositionLocalOf { 1f }

/** The font the user chose, for code that paints with the platform (the editor); null = System default. */
val LocalAppTypeface = staticCompositionLocalOf<Typeface?> { null }

/** The settings behind the current theme (the editor reads the default size for new text from here). */
val LocalFontSettings = staticCompositionLocalOf { FontSettings() }

/** Text inside [content] uses [section]'s size: system scale x app-wide x the section's own. */
@Composable
fun FontSectionScope(section: FontSection, content: @Composable () -> Unit) {
    val appearance = LocalFontAppearance.current
    val system = LocalSystemFontScale.current
    val current = LocalDensity.current
    if (system <= 0f) {
        content()
        return
    }
    val extra = appearance.scaleFor(section)
    val density = remember(current.density, system, extra) { Density(current.density, system * extra) }
    CompositionLocalProvider(LocalDensity provides density, LocalExtraFontScale provides extra, content = content)
}

/** Wraps [content] so text follows the app-wide scale (no section of its own yet). */
@Composable
internal fun ProvideFontScale(appearance: FontAppearance, content: @Composable () -> Unit) {
    val current = LocalDensity.current
    val system = LocalSystemFontScale.current.takeIf { it > 0f } ?: current.fontScale
    val extra = appearance.settings.scaleForApp()
    val density = remember(current.density, system, extra) { Density(current.density, system * extra) }
    CompositionLocalProvider(
        LocalFontAppearance provides appearance,
        LocalFontSettings provides appearance.settings,
        LocalAppTypeface provides appearance.typeface,
        LocalSystemFontScale provides system,
        LocalDensity provides density,
        LocalExtraFontScale provides extra,
        content = content,
    )
}

private fun FontSettings.scaleForApp(): Float = org.samesame.notes.core.model.FontScaling.clamp(appPercent) / 100f

/** The one custom font file ("Add your own font"), kept in the app's private files (user data, not a cache). */
object CustomFontFiles {
    private const val DIR = "fonts"
    private const val NAME = "custom-font.ttf"

    /** Bumped whenever the file changes, so a theme showing it reloads without a restart. */
    var revision by mutableIntStateOf(0)
        private set

    fun file(context: Context): File = File(File(context.filesDir, DIR), NAME)

    sealed interface ImportResult {
        data object Imported : ImportResult
        data class Rejected(val reason: CustomFontFile.Verdict) : ImportResult
        data object Unreadable : ImportResult
    }

    /**
     * Validates [input] and, if it is a usable font, replaces the stored custom font with it. Reads through a
     * temporary file so a rejected pick leaves the current font untouched. Blocking: call it off the main thread.
     */
    fun import(context: Context, input: InputStream): ImportResult {
        val dir = File(context.filesDir, DIR).also { it.mkdirs() }
        val temp = File(dir, "incoming.tmp")
        return try {
            var size = 0L
            val header = ByteArray(4)
            var headerLen = 0
            temp.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (headerLen < 4) {
                        val take = minOf(4 - headerLen, n)
                        System.arraycopy(buf, 0, header, headerLen, take)
                        headerLen += take
                    }
                    size += n
                    if (size > org.samesame.notes.core.model.Limits.MAX_CUSTOM_FONT_BYTES) break
                    out.write(buf, 0, n)
                }
            }
            val verdict = CustomFontFile.check(size, header.copyOf(headerLen))
            if (verdict != CustomFontFile.Verdict.Ok) return ImportResult.Rejected(verdict)
            if (load(temp) == null) return ImportResult.Rejected(CustomFontFile.Verdict.NotAFont)
            val target = file(context)
            target.delete()
            if (!temp.renameTo(target)) return ImportResult.Unreadable
            revision++
            ImportResult.Imported
        } catch (e: java.io.IOException) {
            ImportResult.Unreadable
        } finally {
            temp.delete()
        }
    }

    fun remove(context: Context) {
        file(context).delete()
        revision++
    }

    /** The typeface of [file], or null when it is missing or not a loadable font. */
    fun load(file: File): Typeface? = try {
        if (file.isFile) Typeface.Builder(file).build() else null
    } catch (e: Exception) {
        null
    }
}

private fun bundledFamily(id: String): FontFamily? = when (id) {
    BundledFonts.ATKINSON -> FontFamily(
        Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
        Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
    )
    BundledFonts.PLEX_SERIF -> FontFamily(
        Font(R.font.ibm_plex_serif_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_serif_bold, FontWeight.Bold),
    )
    else -> null
}

private fun bundledTypeface(context: Context, id: String): Typeface? {
    val res = when (id) {
        BundledFonts.ATKINSON -> R.font.atkinson_hyperlegible_regular
        BundledFonts.PLEX_SERIF -> R.font.ibm_plex_serif_regular
        else -> return null
    }
    return context.resources.getFont(res)
}

/** The platform typeface for [settings] (null = System default, or a custom font that is missing or broken). */
fun resolveFontTypeface(context: Context, settings: FontSettings): Typeface? = when (settings.choice.kind) {
    FontKind.System -> null
    FontKind.Bundled -> bundledTypeface(context, settings.choice.bundledId.orEmpty())
    FontKind.Custom -> CustomFontFiles.load(CustomFontFiles.file(context))
}

/** Resolves [settings] into a [FontAppearance]; a missing or broken custom font silently falls back to System default. */
@Composable
fun rememberFontAppearance(settings: FontSettings): FontAppearance {
    val context = LocalContext.current
    val revision = CustomFontFiles.revision
    return remember(settings, revision) {
        when (settings.choice.kind) {
            FontKind.System -> FontAppearance(settings)
            FontKind.Bundled -> {
                val id = settings.choice.bundledId.orEmpty()
                FontAppearance(settings, bundledFamily(id), bundledTypeface(context, id))
            }
            FontKind.Custom -> {
                val file = CustomFontFiles.file(context)
                val typeface = CustomFontFiles.load(file)
                if (typeface == null) FontAppearance(settings, customMissing = true)
                else FontAppearance(settings, FontFamily(Font(file, FontWeight.Normal)), typeface)
            }
        }
    }
}

/** The multiplier folded into the font scale at this point of the tree (for fixed-size helpers). */
@Composable
@ReadOnlyComposable
internal fun extraFontScale(): Float = LocalExtraFontScale.current
