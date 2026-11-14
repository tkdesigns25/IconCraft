package com.iconcraft.studio.model

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap

data class InstalledApp(
    val appName: String,
    val packageName: String,
    val activityName: String,
    val iconBitmap: Bitmap?,
    val iconImageBitmap: ImageBitmap?
) {
    /**
     * Exact ComponentInfo format required by appfilter.xml:
     * ComponentInfo{packageName/className}
     */
    val componentInfo: String
        get() = "ComponentInfo{$packageName/$activityName}"
}
