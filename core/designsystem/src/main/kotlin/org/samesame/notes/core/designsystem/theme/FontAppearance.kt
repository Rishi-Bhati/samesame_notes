package org.samesame.notes.core.designsystem.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import org.samesame.notes.core.designsystem.R
import org.samesame.notes.core.model.BundledFonts
import org.samesame.notes.core.model.CustomFontFile
import org.samesame.notes.core.model.FontKind
import org.samesame.notes.core.model.FontChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.samesame.notes.core.model.FontSection
import org.samesame.notes.core.model.FontSettings
import java.io.File

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
internal val LocalSystemDensity = staticCompositionLocalOf<Density?> { null }

/** The app/section multiplier applied after the system's font conversion. */
val LocalExtraFontScale = compositionLocalOf { 1f }

/** The font the user chose, for code that paints with the platform (the editor); null = System default. */
val LocalAppTypeface = staticCompositionLocalOf<Typeface?> { null }

/** The settings behind the current theme (the editor reads the default size for new text from here). */
val LocalFontSettings = staticCompositionLocalOf { FontSettings() }

/** Text inside [content] uses [section]'s size: system scale x app-wide x the section's own. */
@Composable
fun FontSectionScope(section: FontSection, content: @Composable () -> Unit) {
    val appearance = LocalFontAppearance.current
    val system = LocalSystemDensity.current
    if (system == null) {
        content()
        return
    }
    val extra = appearance.scaleFor(section)
    val density = remember(system, extra) { AppFontDensity(system, extra) }
    CompositionLocalProvider(LocalDensity provides density, LocalExtraFontScale provides extra, content = content)
}

/** Wraps [content] so text follows the app-wide scale (no section of its own yet). */
@Composable
internal fun ProvideFontScale(appearance: FontAppearance, content: @Composable () -> Unit) {
    val current = LocalDensity.current
    val system = LocalSystemDensity.current ?: current
    val extra = appearance.settings.scaleForApp()
    val density = remember(system, extra) { AppFontDensity(system, extra) }
    CompositionLocalProvider(
        LocalFontAppearance provides appearance,
        LocalFontSettings provides appearance.settings,
        LocalAppTypeface provides appearance.typeface,
        LocalSystemDensity provides system,
        LocalDensity provides density,
        LocalExtraFontScale provides extra,
        content = content,
    )
}

private fun FontSettings.scaleForApp(): Float = org.samesame.notes.core.model.FontScaling.clamp(appPercent) / 100f

private data class ResolvedFont(val family: FontFamily? = null, val typeface: Typeface? = null, val customMissing: Boolean = false)

/** The face is already loaded on IO, so Compose only selects its requested weight. */
private class PreloadedFont(private val face: Typeface, override val weight: FontWeight) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Blocking,
    typefaceLoader = Loader,
    variationSettings = FontVariation.Settings(),
) {
    override val style: FontStyle = FontStyle.Normal
    private object Loader : TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface = (font as PreloadedFont).face
        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface = (font as PreloadedFont).face
    }
}

private data class FontRevision(val choice: FontChoice, val path: String?, val modified: Long, val size: Long)

/** File metadata and font loading stay on IO; size settings do not invalidate the loaded face. */
private object FontResolver {
    private val mutex = Mutex()
    private val cache = mutableMapOf<FontRevision, ResolvedFont>()

    suspend fun resolve(context: Context, settings: FontSettings): ResolvedFont = withContext(Dispatchers.IO) {
        mutex.withLock {
            val choice = settings.choice
            val file = if (choice.kind == FontKind.Custom) {
                File(File(context.filesDir, "fonts"), CustomFontFile.storageName(settings.customFontFileName))
            } else null
            val key = FontRevision(choice, file?.absolutePath, file?.lastModified() ?: 0L, file?.length() ?: 0L)
            cache.getOrPut(key) {
                val typeface = when (choice.kind) {
                    FontKind.System -> null
                    FontKind.Bundled -> {
                        val resource = when (choice.bundledId) {
                            BundledFonts.ATKINSON -> R.font.atkinson_hyperlegible_regular
                            BundledFonts.PLEX_SERIF -> R.font.ibm_plex_serif_regular
                            else -> null
                        }
                        resource?.let { context.resources.getFont(it) }
                    }
                    FontKind.Custom -> runCatching {
                        if (file?.isFile == true) Typeface.Builder(file).build() else null
                    }.getOrNull()
                }
                val boldResource = when (choice.bundledId.takeIf { choice.kind == FontKind.Bundled }) {
                    BundledFonts.ATKINSON -> R.font.atkinson_hyperlegible_bold
                    BundledFonts.PLEX_SERIF -> R.font.ibm_plex_serif_bold
                    else -> null
                }
                val family = if (typeface != null && boldResource != null) {
                    FontFamily(
                        PreloadedFont(typeface, FontWeight.Normal),
                        PreloadedFont(context.resources.getFont(boldResource), FontWeight.Bold),
                    )
                } else typeface?.let { FontFamily(it) }
                // Preloaded faces avoid Compose's synchronous file-font loader.
                ResolvedFont(family, typeface, choice.kind == FontKind.Custom && typeface == null)
            }
        }
    }
}

/** The cached platform typeface, resolved on IO (null = System default or a missing custom font). */
suspend fun resolveFontTypeface(context: Context, settings: FontSettings): Typeface? = FontResolver.resolve(context, settings).typeface

/** Publishes a preloaded face; app/section size changes reuse it without touching storage. */
@Composable
fun rememberFontAppearance(settings: FontSettings): FontAppearance {
    val context = LocalContext.current.applicationContext
    val resolved by produceState(ResolvedFont(), context, settings.choice, settings.customFontFileName) {
        value = FontResolver.resolve(context, settings)
    }
    return remember(settings, resolved) { FontAppearance(settings, resolved.family, resolved.typeface, resolved.customMissing) }
}

/** The multiplier applied after system conversion at this point of the tree (for fixed-size helpers). */
@Composable
@ReadOnlyComposable
internal fun extraFontScale(): Float = LocalExtraFontScale.current
