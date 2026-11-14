package com.iconcraft.studio.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.iconcraft.studio.model.CustomIconQueueItem
import com.iconcraft.studio.model.InstalledApp
import com.iconcraft.studio.ui.theme.EmeraldAccent
import com.iconcraft.studio.ui.theme.IndigoPrimary
import com.iconcraft.studio.ui.theme.SlateSurface
import com.iconcraft.studio.ui.theme.SlateSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

enum class IconShapeMask(val label: String) {
    ROUNDED_SQUARE("Rounded Square"),
    SQUARE("Square"),
    CIRCLE("Circle"),
    FULL_BLEED("Full Bleed");

    companion object {
        fun fromName(name: String): IconShapeMask =
            entries.firstOrNull { it.name == name } ?: ROUNDED_SQUARE
    }
}

data class StudioSavePayload(
    val renderedBitmap: Bitmap,
    val sourceBitmap: Bitmap?,
    val shapeMaskName: String,
    val imageScale: Float,
    val roundInnerCorners: Boolean,
    val backgroundColorArgb: Int,
    val userZoom: Float,
    val userPanX: Float,
    val userPanY: Float
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IconStudioScreen(
    targetApp: InstalledApp,
    uploadedBitmap: Bitmap?,
    initialQueueItem: CustomIconQueueItem? = null,
    onPickNewPhoto: () -> Unit,
    onSaveIcon: (StudioSavePayload) -> Unit,
    onCancel: () -> Unit
) {
    val activeSourceBitmap = uploadedBitmap ?: targetApp.iconBitmap

    var selectedShape by remember(initialQueueItem) {
        mutableStateOf(
            initialQueueItem?.shapeMaskName?.let { IconShapeMask.fromName(it) }
                ?: IconShapeMask.ROUNDED_SQUARE
        )
    }
    // Inner image scale inside the full-bleed background: 0.30f (30%) .. 1.20f (120%), default 1.00f
    var imageScale by remember(initialQueueItem) {
        mutableFloatStateOf(initialQueueItem?.imageScale ?: 1.0f)
    }
    var roundInnerCorners by remember(initialQueueItem) {
        mutableStateOf(initialQueueItem?.roundInnerCorners ?: false)
    }

    // Interactive pan & zoom inside the cropper
    var userZoom by remember(initialQueueItem) {
        mutableFloatStateOf(initialQueueItem?.userZoom ?: 1.0f)
    }
    var userPanX by remember(initialQueueItem) {
        mutableFloatStateOf(initialQueueItem?.userPanX ?: 0f)
    }
    var userPanY by remember(initialQueueItem) {
        mutableFloatStateOf(initialQueueItem?.userPanY ?: 0f)
    }

    // Track initial bitmap reference so picking a replacement photo resets pan/zoom cleanly
    var lastSeenBitmap by remember { mutableStateOf(uploadedBitmap) }
    LaunchedEffect(uploadedBitmap) {
        if (uploadedBitmap != null && uploadedBitmap !== lastSeenBitmap) {
            lastSeenBitmap = uploadedBitmap
            userZoom = 1.0f
            userPanX = 0f
            userPanY = 0f
        }
    }

    // Background fill color (0 = No Background / Transparent, otherwise ARGB color)
    // Preserves exact user choice (including 0 = No Background) when editing a saved queue item.
    var backgroundFillColor by remember(initialQueueItem) {
        mutableIntStateOf(initialQueueItem?.backgroundColorArgb ?: 0)
    }
    var isEyedropperActive by remember { mutableStateOf(false) }
    var previewBoxSize by remember { mutableStateOf(IntSize(1, 1)) }

    // Extracted palette swatches from the uploaded image (never auto-overrides user's background choice)
    var extractedSwatches by remember { mutableStateOf<List<Int>>(emptyList()) }

    LaunchedEffect(activeSourceBitmap) {
        if (activeSourceBitmap != null) {
            val colors = withContext(Dispatchers.Default) {
                val palette = Palette.from(activeSourceBitmap).generate()
                listOfNotNull(
                    palette.dominantSwatch?.rgb,
                    palette.vibrantSwatch?.rgb,
                    palette.darkVibrantSwatch?.rgb,
                    palette.lightVibrantSwatch?.rgb,
                    palette.mutedSwatch?.rgb,
                    palette.darkMutedSwatch?.rgb
                ).distinct()
            }
            extractedSwatches = colors
        }
    }

    // Live 512x512 ARGB_8888 rendered bitmap
    val renderedBitmap = remember(
        activeSourceBitmap,
        selectedShape,
        imageScale,
        roundInnerCorners,
        userZoom,
        userPanX,
        userPanY,
        backgroundFillColor
    ) {
        renderStudioIconBitmap(
            source = activeSourceBitmap,
            shapeMask = selectedShape,
            canvasScale = imageScale,
            userZoom = userZoom,
            userPanX = userPanX,
            userPanY = userPanY,
            backgroundColorArgb = backgroundFillColor,
            roundInnerCorners = roundInnerCorners
        )
    }

    val renderedImageBitmap = remember(renderedBitmap) {
        renderedBitmap.asImageBitmap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Icon Studio — ${targetApp.appName}",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (initialQueueItem != null) {
                                "Editing ${initialQueueItem.drawableName} • Keeps same app & slot"
                            } else {
                                "512×512 ARGB_8888 • Pan & Pinch to Crop"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (initialQueueItem != null) EmeraldAccent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Cancel"
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = onPickNewPhoto,
                        modifier = Modifier.padding(end = 12.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Change Photo")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                color = SlateSurface,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            onSaveIcon(
                                StudioSavePayload(
                                    renderedBitmap = renderedBitmap,
                                    sourceBitmap = activeSourceBitmap,
                                    shapeMaskName = selectedShape.name,
                                    imageScale = imageScale,
                                    roundInnerCorners = roundInnerCorners,
                                    backgroundColorArgb = backgroundFillColor,
                                    userZoom = userZoom,
                                    userPanX = userPanX,
                                    userPanY = userPanY
                                )
                            )
                        },
                        modifier = Modifier
                            .weight(2f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Save to Queue (512×512)",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Interactive Square 512x512 Preview & Pan/Zoom Cropper
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0E131F))
                    .border(
                        width = 2.dp,
                        color = if (isEyedropperActive) EmeraldAccent else IndigoPrimary.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .onSizeChanged { previewBoxSize = it }
                    .pointerInput(isEyedropperActive) {
                        if (isEyedropperActive) {
                            detectTapGestures { tapOffset ->
                                val w = previewBoxSize.width.coerceAtLeast(1)
                                val h = previewBoxSize.height.coerceAtLeast(1)
                                val bx = ((tapOffset.x / w) * 511f).roundToInt().coerceIn(0, 511)
                                val by = ((tapOffset.y / h) * 511f).roundToInt().coerceIn(0, 511)
                                val sampled = renderedBitmap.getPixel(bx, by)
                                if (android.graphics.Color.alpha(sampled) > 16) {
                                    backgroundFillColor = sampled or (0xFF shl 24)
                                }
                                isEyedropperActive = false
                            }
                        } else {
                            detectTransformGestures { _, pan, zoom, _ ->
                                userZoom = (userZoom * zoom).coerceIn(0.35f, 4.5f)
                                val scaleFactor = 512f / previewBoxSize.width.coerceAtLeast(1).toFloat()
                                userPanX = (userPanX + pan.x * scaleFactor).coerceIn(-512f, 512f)
                                userPanY = (userPanY + pan.y * scaleFactor).coerceIn(-512f, 512f)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = renderedImageBitmap,
                    contentDescription = "Live 512x512 Icon Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isEyedropperActive) {
                        "Tap anywhere on the preview to sample a color"
                    } else {
                        "Drag to pan • Pinch to zoom (${(userZoom * 100).roundToInt()}%)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isEyedropperActive) EmeraldAccent else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (userZoom != 1.0f || userPanX != 0f || userPanY != 0f) {
                    Text(
                        text = "Reset Crop",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = IndigoPrimary,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.clickable {
                            userZoom = 1.0f
                            userPanX = 0f
                            userPanY = 0f
                        }
                    )
                }
            }

            if (initialQueueItem != null) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onPickNewPhoto,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Replace Image (Keep ${targetApp.appName} & ${initialQueueItem.drawableName})",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Control Card 1: Shape Mask
            Surface(
                color = SlateSurface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Outer Icon Shape Mask",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconShapeMask.entries.forEach { mask ->
                            FilterChip(
                                selected = selectedShape == mask,
                                onClick = { selectedShape = mask },
                                label = { Text(mask.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = IndigoPrimary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Control Card 2: Inner Image Scale (Background stays full fill)
            Surface(
                color = SlateSurface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Image Size (Inside Background)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${(imageScale * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = IndigoPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { imageScale = 1.0f },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reset image scale",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Slider(
                        value = imageScale,
                        onValueChange = { imageScale = it },
                        valueRange = 0.30f..1.20f,
                        colors = SliderDefaults.colors(
                            thumbColor = IndigoPrimary,
                            activeTrackColor = IndigoPrimary
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Background stays full fill while image scales inside",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = roundInnerCorners,
                            onClick = { roundInnerCorners = !roundInnerCorners },
                            label = { Text("Round Image") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = IndigoPrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Control Card 3: Background Fill Color + Eyedropper
            Surface(
                color = SlateSurface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Background Layer Fill",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (backgroundFillColor == 0) "No Background (Transparent)" else "Solid Background Layer",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        FilterChip(
                            selected = isEyedropperActive,
                            onClick = { isEyedropperActive = !isEyedropperActive },
                            label = {
                                Text(if (isEyedropperActive) "Tap Preview..." else "Eyedropper")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Colorize,
                                    contentDescription = "Eyedropper",
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldAccent,
                                selectedLabelColor = Color.Black
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val presetColors = remember(extractedSwatches) {
                        buildList {
                            add(0) // Transparent (No Background)
                            addAll(extractedSwatches)
                            add(0xFF0A0D14.toInt())
                            add(0xFF1E293B.toInt())
                            add(0xFFFFFFFF.toInt())
                            add(0xFF6366F1.toInt())
                            add(0xFFA855F7.toInt())
                            add(0xFF10B981.toInt())
                            add(0xFFEF4444.toInt())
                            add(0xFFF59E0B.toInt())
                        }.distinct()
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        presetColors.forEach { colorArgb ->
                            val isSelected = backgroundFillColor == colorArgb
                            ColorSwatchCircle(
                                colorArgb = colorArgb,
                                isSelected = isSelected,
                                onClick = { backgroundFillColor = colorArgb }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ColorSwatchCircle(
    colorArgb: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isTransparent = colorArgb == 0
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (isTransparent) SlateSurfaceVariant else Color(colorArgb)
            )
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) EmeraldAccent else Color.White.copy(alpha = 0.25f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isTransparent) {
            Text(
                text = "∅",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}

/**
 * Pure Bitmap/Canvas renderer producing a 512x512 ARGB_8888 Bitmap.
 * - Outer Shape Mask & Background Fill stay full-bleed (512x512) so the background acts as a full backdrop layer.
 * - `canvasScale` (Image Size) scales the image inside the background.
 */
fun renderStudioIconBitmap(
    source: Bitmap?,
    shapeMask: IconShapeMask,
    canvasScale: Float,
    userZoom: Float,
    userPanX: Float,
    userPanY: Float,
    backgroundColorArgb: Int,
    roundInnerCorners: Boolean = false
): Bitmap {
    val size = 512
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    val half = size / 2f
    val outerRect = RectF(0f, 0f, size.toFloat(), size.toFloat())

    val outerClipPath = Path()
    when (shapeMask) {
        IconShapeMask.FULL_BLEED -> {
            outerClipPath.addRect(outerRect, Path.Direction.CW)
        }
        IconShapeMask.SQUARE -> {
            val r = size * 0.10f
            outerClipPath.addRoundRect(outerRect, r, r, Path.Direction.CW)
        }
        IconShapeMask.ROUNDED_SQUARE -> {
            val r = size * 0.22f
            outerClipPath.addRoundRect(outerRect, r, r, Path.Direction.CW)
        }
        IconShapeMask.CIRCLE -> {
            outerClipPath.addOval(outerRect, Path.Direction.CW)
        }
    }

    val outerSave = canvas.save()
    canvas.clipPath(outerClipPath)

    // 1. Draw full-bleed background fill color inside the outer shape mask (if not No Background)
    if (backgroundColorArgb != 0) {
        paint.style = Paint.Style.FILL
        paint.color = backgroundColorArgb
        canvas.drawRect(outerRect, paint)
    }

    // 2. Draw the inner image scaled by canvasScale * userZoom
    if (source != null && source.width > 0 && source.height > 0) {
        val innerHalf = (half * canvasScale.coerceIn(0.20f, 1.50f))
        val innerRect = RectF(
            half - innerHalf,
            half - innerHalf,
            half + innerHalf,
            half + innerHalf
        )

        val innerSave = canvas.save()
        if (roundInnerCorners && canvasScale < 0.99f) {
            val innerClip = Path()
            val r = innerRect.width() * 0.20f
            innerClip.addRoundRect(innerRect, r, r, Path.Direction.CW)
            canvas.clipPath(innerClip)
        }

        // Base scale fills the 512x512 canvas at 1.0, then scales proportionally by canvasScale * userZoom
        val baseScale = max(
            size.toFloat() / source.width.toFloat(),
            size.toFloat() / source.height.toFloat()
        )
        val finalScale = baseScale * canvasScale * userZoom

        val matrix = Matrix().apply {
            postTranslate(-source.width / 2f, -source.height / 2f)
            postScale(finalScale, finalScale)
            postTranslate(half + userPanX, half + userPanY)
        }

        paint.color = android.graphics.Color.WHITE
        canvas.drawBitmap(source, matrix, paint)
        canvas.restoreToCount(innerSave)
    }

    canvas.restoreToCount(outerSave)
    return out
}
