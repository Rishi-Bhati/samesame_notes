package org.samesame.notes.feature.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas

/** The app's own launcher icon and name, for the creator widget's first cell and title row. */
internal object AppBranding {
    fun name(context: Context): String = context.applicationInfo.loadLabel(context.packageManager).toString()

    /** The launcher icon drawn at [sizePx] (adaptive icons can't go through `painterResource`, so it is rasterised once). */
    fun icon(context: Context, sizePx: Int): Bitmap? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()
}
