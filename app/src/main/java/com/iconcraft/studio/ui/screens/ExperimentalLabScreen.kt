package com.iconcraft.studio.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iconcraft.studio.lab.ExtractedThemePalette
import com.iconcraft.studio.lab.ProceduralStyle
import com.iconcraft.studio.lab.ProceduralThemeEngine
import com.iconcraft.studio.model.InstalledApp
import com.iconcraft.studio.ui.theme.EmeraldAccent
import com.iconcraft.studio.ui.theme.IndigoPrimary
import com.iconcraft.studio.ui.theme.SlateSurface
import com.iconcraft.studio.ui.theme.SlateSurfaceVariant
import com.iconcraft.studio.ui.theme.VioletSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ExperimentalLabScreen(
    installedApps: List<InstalledApp>,
    inspirationBitmap: Bitmap?,
    selectiveQueueCount: Int,
    onPickInspirationImage: () -> Unit,
    onBatchExportPack: (
        inspiration: Bitmap,
        palette: ExtractedThemePalette,
        style: ProceduralStyle,
        invertVariant: Boolean,
        preserveSelectiveQueue: Boolean,
        syncToSelectiveQueue: Boolean
    ) -> Unit
) {
    val context = LocalContext.current

    // Default procedural artwork if user hasn't picked an image yet
    val activeInspiration = remember(inspirationBitmap) {
        inspirationBitmap ?: createDefaultInspirationPoster()
    }

    var palette by remember { mutableStateOf(ExtractedThemePalette()) }
    var selectedStyle by remember { mutableStateOf(ProceduralStyle.NEON_GLOW) }
    var invertVariant by remember { mutableStateOf(false) }
    var preserveSelectiveQueue by remember { mutableStateOf(true) }
    var syncToSelectiveQueue by remember { mutableStateOf(true) }

    // Select up to 16 real installed apps for the 4x4 live preview grid
    val previewApps = remember(installedApps) {
        val priorityKeywords = listOf(
            "letterboxd", "youtube", "spotify", "instagram", "whatsapp",
            "chrome", "discord", "reddit", "camera", "photos", "maps",
            "gmail", "phone", "messages", "settings", "clock"
        )
        val prioritized = installedApps.sortedBy { app ->
            val lower = (app.appName + " " + app.packageName).lowercase()
            val matchIdx = priorityKeywords.indexOfFirst { lower.contains(it) }
            if (matchIdx >= 0) matchIdx else 999
        }
        prioritized.take(16)
    }

    // Cache extracted foreground silhouettes for the 16 preview apps
    val cachedForegrounds = remember { mutableStateMapOf<String, Bitmap>() }
    var previewIcons by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var isRenderingPreview by remember { mutableStateOf(false) }

    // 1. Extract Palette when inspiration image changes
    LaunchedEffect(activeInspiration) {
        val extracted = withContext(Dispatchers.Default) {
            ProceduralThemeEngine.extractPalette(activeInspiration)
        }
        palette = extracted
    }

    // 2. Render live 4x4 preview icons whenever inspiration, palette, style, or variant changes
    LaunchedEffect(activeInspiration, palette, selectedStyle, invertVariant, previewApps) {
        if (previewApps.isEmpty()) return@LaunchedEffect
        isRenderingPreview = true
        val renderedMap = withContext(Dispatchers.Default) {
            val out = LinkedHashMap<String, ImageBitmap>()
            for (app in previewApps) {
                val fg = cachedForegrounds.getOrPut(app.componentInfo) {
                    ProceduralThemeEngine.extractAppForegroundGlyph(
                        context = context,
                        app = app,
                        targetSize = 192
                    )
                }
                val iconBmp = ProceduralThemeEngine.renderProceduralIcon(
                    inspirationBitmap = activeInspiration,
                    palette = palette,
                    foregroundGlyph = fg,
                    style = selectedStyle,
                    outputSize = 192,
                    invertSilhouette = invertVariant
                )
                out[app.componentInfo] = iconBmp.asImageBitmap()
            }
            out
        }
        previewIcons = renderedMap
        isRenderingPreview = false
    }

    val batchCount = installedApps.size.coerceAtMost(200)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Experimental Header Banner (100% Local, Zero AI)
        item {
            Surface(
                color = SlateSurface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = VioletSecondary.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(18.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(VioletSecondary.copy(alpha = 0.22f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "EXPERIMENTAL • 100% OFFLINE (ZERO AI)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = VioletSecondary,
                                    letterSpacing = 0.6.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Auto-Theme Pack from 1 Image",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Upload any movie poster or wallpaper. IconCraft extracts its color palette via androidx.palette and procedurally transforms up to 200 installed app foregrounds using pure Kotlin Bitmap, Canvas, and ColorMatrix math—100% locally with zero AI or cloud calls.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 2. Step 1 & 2: Inspiration Image + Extracted Palette Swatches
        item {
            Surface(
                color = SlateSurface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "1. Inspiration Image & Palette",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (inspirationBitmap != null) "Custom image loaded" else "Using sample synthwave poster",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        FilledTonalButton(
                            onClick = onPickInspirationImage,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Upload Image")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Image(
                            bitmap = remember(activeInspiration) { activeInspiration.asImageBitmap() },
                            contentDescription = "Inspiration image",
                            modifier = Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, SlateSurfaceVariant, RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Extracted Palette (androidx.palette)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                PaletteSwatchBadge("Dominant", palette.dominantColor)
                                PaletteSwatchBadge("Vibrant", palette.vibrantColor)
                                PaletteSwatchBadge("DarkMuted", palette.darkMutedColor)
                                PaletteSwatchBadge("LightVib", palette.lightVibrantColor)
                            }
                        }
                    }
                }
            }
        }

        // 3. Step 3: Choose 1 of 3 Procedural Styles
        item {
            Surface(
                color = SlateSurface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "2. Choose Procedural Style",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )

                        FilterChip(
                            selected = invertVariant,
                            onClick = { invertVariant = !invertVariant },
                            label = {
                                Text(
                                    when (selectedStyle) {
                                        ProceduralStyle.POSTER_TEXTURE -> if (invertVariant) "Dark Glyph" else "White Glyph"
                                        ProceduralStyle.NEON_GLOW -> if (invertVariant) "#0D0D0D Base" else "DarkMuted Base"
                                        ProceduralStyle.MINIMAL_DUOTONE -> if (invertVariant) "Vibrant Base" else "Dark Base"
                                    }
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = IndigoPrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    ProceduralStyle.entries.forEach { style ->
                        val isSelected = selectedStyle == style
                        Surface(
                            color = if (isSelected) IndigoPrimary.copy(alpha = 0.16f) else SlateSurfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) IndigoPrimary else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { selectedStyle = style }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedStyle = style },
                                    colors = RadioButtonDefaults.colors(selectedColor = IndigoPrimary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = style.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = style.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Step 5: Live 4x4 Preview Grid of 12-16 Real Installed Apps
        item {
            Surface(
                color = SlateSurface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "3. Live 4×4 Preview (${previewApps.size} Real Apps)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "AdaptiveIconDrawable.foreground extraction + procedural Canvas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isRenderingPreview) {
                            CircularProgressIndicator(
                                color = IndigoPrimary,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val rows = previewApps.chunked(4)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        rows.forEach { rowApps ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                for (i in 0 until 4) {
                                    val app = rowApps.getOrNull(i)
                                    if (app != null) {
                                        val renderedImg = previewIcons[app.componentInfo] ?: app.iconImageBitmap
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(58.dp)
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(SlateSurfaceVariant.copy(alpha = 0.4f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (renderedImg != null) {
                                                    Image(
                                                        bitmap = renderedImg,
                                                        contentDescription = app.appName,
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentScale = ContentScale.Fit
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = app.appName,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Step 6: Batch Export up to 200 Installed Apps
        item {
            Surface(
                color = SlateSurface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "4. Batch Export ($batchCount Installed Apps)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Maps up to 200 launchable apps into custom_icon_001..custom_icon_200 and signs the APK on-device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (selectiveQueueCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { preserveSelectiveQueue = !preserveSelectiveQueue }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = preserveSelectiveQueue,
                                onCheckedChange = { preserveSelectiveQueue = it },
                                colors = CheckboxDefaults.colors(checkedColor = EmeraldAccent)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Keep my $selectiveQueueCount custom Selective Studio icon(s) untouched on top",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { syncToSelectiveQueue = !syncToSelectiveQueue }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = syncToSelectiveQueue,
                            onCheckedChange = { syncToSelectiveQueue = it },
                            colors = CheckboxDefaults.colors(checkedColor = EmeraldAccent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Retain generated icons in Queue (edit any single app later without re-uploading)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            onBatchExportPack(
                                activeInspiration,
                                palette,
                                selectedStyle,
                                invertVariant,
                                preserveSelectiveQueue,
                                syncToSelectiveQueue
                            )
                        },
                        enabled = installedApps.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VioletSecondary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Generate & Sign $batchCount-App Theme Pack",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteSwatchBadge(label: String, colorArgb: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(colorArgb))
                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "#%06X".format(0xFFFFFF and colorArgb),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Generates a vibrant synthwave/cinema poster gradient bitmap out-of-the-box so the
 * Experimental Lab 4x4 live preview works immediately even before the user picks a photo.
 */
private fun createDefaultInspirationPoster(): Bitmap {
    val size = 512
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
        0f,
        0f,
        size.toFloat(),
        size.toFloat(),
        intArrayOf(
            0xFF0F172A.toInt(),
            0xFF4F46E5.toInt(),
            0xFFEC4899.toInt(),
            0xFFF59E0B.toInt()
        ),
        floatArrayOf(0f, 0.42f, 0.78f, 1f),
        Shader.TileMode.CLAMP
    )
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    return bmp
}
