package com.iconcraft.studio.model

import androidx.compose.ui.graphics.ImageBitmap

data class CustomIconQueueItem(
    val app: InstalledApp,
    val slotIndex: Int,
    val customPngPath: String? = null,
    val sourcePngPath: String? = null,
    val customPreviewBitmap: ImageBitmap? = null,
    val shapeMaskName: String = "ROUNDED_SQUARE",
    val imageScale: Float = 1.0f,
    val roundInnerCorners: Boolean = false,
    val backgroundColorArgb: Int = 0,
    val userZoom: Float = 1.0f,
    val userPanX: Float = 0f,
    val userPanY: Float = 0f
) {
    val drawableName: String
        get() = "custom_icon_%03d".format(slotIndex)
}
