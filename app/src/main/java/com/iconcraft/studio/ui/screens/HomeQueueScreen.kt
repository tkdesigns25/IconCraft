package com.iconcraft.studio.ui.screens

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iconcraft.studio.apk.InstalledIconPack
import com.iconcraft.studio.lab.ExtractedThemePalette
import com.iconcraft.studio.lab.ProceduralStyle
import com.iconcraft.studio.model.CustomIconQueueItem
import com.iconcraft.studio.model.InstalledApp
import com.iconcraft.studio.ui.theme.CoralDanger
import com.iconcraft.studio.ui.theme.EmeraldAccent
import com.iconcraft.studio.ui.theme.IndigoPrimary
import com.iconcraft.studio.ui.theme.SlateSurface
import com.iconcraft.studio.ui.theme.SlateSurfaceVariant
import com.iconcraft.studio.ui.theme.VioletSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeQueueScreen(
    queueItems: List<CustomIconQueueItem>,
    installedApps: List<InstalledApp>,
    totalInstalledApps: Int,
    templateApkSizeBytes: Long,
    installedIconPacks: List<InstalledIconPack> = emptyList(),
    selectedBasePackPackage: String? = null,
    onSelectBasePack: (String?) -> Unit = {},
    labInspirationBitmap: Bitmap? = null,
    snackbarHostState: SnackbarHostState,
    onCustomizeAppClick: () -> Unit,
    onEditQueueItem: (CustomIconQueueItem) -> Unit,
    onRemoveQueueItem: (CustomIconQueueItem) -> Unit,
    onPinWidgetItem: (CustomIconQueueItem) -> Unit = {},
    onBuildPackClick: () -> Unit,
    onPickLabInspirationClick: () -> Unit = {},
    onBatchExportLabPack: (
        inspiration: Bitmap,
        palette: ExtractedThemePalette,
        style: ProceduralStyle,
        invertVariant: Boolean,
        preserveSelectiveQueue: Boolean
    ) -> Unit = { _, _, _, _, _ -> },
    onInspectLauncherClick: () -> Unit = {}
) {
    // 0 = Selective Studio (Default), 1 = Experimental Lab ("Auto-Theme Pack from 1 Image")
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showBasePackDialog by remember { mutableStateOf(false) }

    val selectedBasePack = remember(installedIconPacks, selectedBasePackPackage) {
        installedIconPacks.firstOrNull { it.packageName == selectedBasePackPackage }
    }
    val canBuild = queueItems.isNotEmpty() || selectedBasePack != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(IndigoPrimary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = IndigoPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "IconCraft",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = if (selectedTabIndex == 0) {
                                    "Custom Selective Icon Studio"
                                } else {
                                    "Procedural Auto-Theme Lab (Zero AI)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onInspectLauncherClick) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Launcher Diagnostic",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selectedTabIndex == 0 && queueItems.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = onCustomizeAppClick,
                            modifier = Modifier.padding(end = 12.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add App")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectedTabIndex == 0) {
                Surface(
                    color = SlateSurface,
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Button(
                            onClick = onBuildPackClick,
                            enabled = canBuild,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = IndigoPrimary,
                                disabledContainerColor = SlateSurfaceVariant
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val btnText = if (selectedBasePack != null) {
                                "Build & Merge Icon Pack (${queueItems.size} custom + ${selectedBasePack.packLabel})"
                            } else {
                                "Build & Install Icon Pack (${queueItems.size} ${if (queueItems.size == 1) "app" else "apps"})"
                            }
                            Text(
                                text = btnText,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
        ) {
            // Top-level Segmented Tab Switcher: Selective Studio vs Experimental Lab
            TopModeTabRow(
                selectedTabIndex = selectedTabIndex,
                onSelectTab = { selectedTabIndex = it }
            )

            // Template APK & 500-Slot Capacity Status Banner
            TemplateStatusBanner(
                templateApkSizeBytes = templateApkSizeBytes,
                totalInstalledApps = totalInstalledApps
            )

            if (selectedTabIndex == 1) {
                ExperimentalLabScreen(
                    installedApps = installedApps,
                    inspirationBitmap = labInspirationBitmap,
                    selectiveQueueCount = queueItems.size,
                    onPickInspirationImage = onPickLabInspirationClick,
                    onBatchExportPack = onBatchExportLabPack
                )
            } else if (queueItems.isEmpty()) {
                // Empty Queue State with Base Pack selector + prominent "+ Customize an App" CTA + Brand Guide
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        BaseIconPackSelectorCard(
                            selectedBasePack = selectedBasePack,
                            installedPackCount = installedIconPacks.size,
                            onClick = { showBasePackDialog = true }
                        )
                    }

                    item {
                        Surface(
                            color = SlateSurface,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    color = SlateSurfaceVariant,
                                    shape = RoundedCornerShape(20.dp)
                                )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(26.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(IndigoPrimary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = IndigoPrimary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Selective Icon Queue",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    )
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "Pick Letterboxd or any app to replace its icon with your custom artwork. Every other installed app stays 100% untouched (or inherits your chosen Base Pack).",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(22.dp))

                                Button(
                                    onClick = onCustomizeAppClick,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = IndigoPrimary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "+ Customize an App",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                        }
                    }

                    item {
                        BrandApplyGuideCard()
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        BaseIconPackSelectorCard(
                            selectedBasePack = selectedBasePack,
                            installedPackCount = installedIconPacks.size,
                            onClick = { showBasePackDialog = true }
                        )
                    }

                    items(
                        items = queueItems,
                        key = { it.app.componentInfo }
                    ) { item ->
                        QueueItemCard(
                            item = item,
                            onEdit = { onEditQueueItem(item) },
                            onPinWidget = { onPinWidgetItem(item) },
                            onRemove = { onRemoveQueueItem(item) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = onCustomizeAppClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("+ Customize Another App")
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        BrandApplyGuideCard(
                            sampleAppName = queueItems.first().app.appName,
                            canPinWidget = true,
                            onPinWidgetClick = { onPinWidgetItem(queueItems.first()) }
                        )
                    }
                }
            }
        }
    }

    if (showBasePackDialog) {
        BaseIconPackPickerDialog(
            installedPacks = installedIconPacks,
            selectedBasePackPackage = selectedBasePackPackage,
            onSelect = { pkg ->
                onSelectBasePack(pkg)
                showBasePackDialog = false
            },
            onDismiss = { showBasePackDialog = false }
        )
    }
}

@Composable
private fun BaseIconPackSelectorCard(
    selectedBasePack: InstalledIconPack?,
    installedPackCount: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (selectedBasePack != null) IndigoPrimary.copy(alpha = 0.6f) else SlateSurfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
        color = SlateSurface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(IndigoPrimary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = selectedBasePack?.iconImageBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = selectedBasePack.packLabel,
                        modifier = Modifier.size(34.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = IndigoPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Base Icon Pack (Inherit Existing Pack)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = selectedBasePack?.packLabel ?: "None — Keep System Icons",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (selectedBasePack != null) EmeraldAccent else MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (selectedBasePack != null) {
                        "Inherits ${selectedBasePack.packLabel} icons & masks for non-customized apps"
                    } else {
                        "$installedPackCount installed third-party pack(s) detected • Tap to change"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Select Base Pack",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BaseIconPackPickerDialog(
    installedPacks: List<InstalledIconPack>,
    selectedBasePackPackage: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateSurface,
        title = {
            Column {
                Text(
                    text = "Select Base Icon Pack",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Your custom IconCraft icons always override the Base Pack.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Option 0: None - Keep System Icons
                item {
                    val isNoneSelected = selectedBasePackPackage.isNullOrBlank()
                    Surface(
                        color = if (isNoneSelected) IndigoPrimary.copy(alpha = 0.18f) else SlateSurfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = if (isNoneSelected) IndigoPrimary else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelect(null) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isNoneSelected,
                                onClick = { onSelect(null) },
                                colors = RadioButtonDefaults.colors(selectedColor = IndigoPrimary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "None — Keep System Icons",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Only replace apps in your Custom Queue; leave all others 100% untouched",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                items(
                    items = installedPacks,
                    key = { it.packageName }
                ) { pack ->
                    val isSelected = pack.packageName == selectedBasePackPackage
                    Surface(
                        color = if (isSelected) IndigoPrimary.copy(alpha = 0.18f) else SlateSurfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = if (isSelected) IndigoPrimary else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelect(pack.packageName) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelect(pack.packageName) },
                                colors = RadioButtonDefaults.colors(selectedColor = IndigoPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val bmp = pack.iconImageBitmap
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = pack.packLabel,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pack.packLabel,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pack.packageName,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun TopModeTabRow(
    selectedTabIndex: Int,
    onSelectTab: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = SlateSurface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Tab 0: Selective Studio
            val isSelective = selectedTabIndex == 0
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelective) IndigoPrimary else Color.Transparent)
                    .clickable { onSelectTab(0) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = if (isSelective) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Selective Studio",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isSelective) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            // Tab 1: Auto-Theme Lab (badged EXPERIMENTAL)
            val isLab = selectedTabIndex == 1
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isLab) VioletSecondary else Color.Transparent)
                    .clickable { onSelectTab(1) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = null,
                        tint = if (isLab) Color.White else VioletSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Auto-Theme",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isLab) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isLab) Color.Black.copy(alpha = 0.28f)
                                else VioletSecondary.copy(alpha = 0.22f)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "EXPERIMENTAL",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isLab) Color.White else VioletSecondary
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateStatusBanner(
    templateApkSizeBytes: Long,
    totalInstalledApps: Int
) {
    val isTemplateReady = templateApkSizeBytes > 0
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = SlateSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isTemplateReady) EmeraldAccent else CoralDanger,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isTemplateReady) {
                        "Template APK ready (${templateApkSizeBytes / 1024} KB • 500 slots)"
                    } else {
                        "Template APK missing in assets"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = "$totalInstalledApps launcher apps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QueueItemCard(
    item: CustomIconQueueItem,
    onEdit: () -> Unit,
    onPinWidget: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = SlateSurfaceVariant,
                shape = RoundedCornerShape(16.dp)
            ),
        color = SlateSurface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Original Icon -> Arrow -> Custom Preview Icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SlateSurfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    // Original Icon
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SlateSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        val orig = item.app.iconImageBitmap
                        if (orig != null) {
                            Image(
                                bitmap = orig,
                                contentDescription = "Original icon",
                                modifier = Modifier.size(38.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Android,
                                contentDescription = null,
                                tint = IndigoPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Replaced with",
                        tint = VioletSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    // Custom Preview Icon
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(IndigoPrimary.copy(alpha = 0.18f))
                            .border(
                                width = 1.dp,
                                color = IndigoPrimary.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val customImg = item.customPreviewBitmap ?: item.app.iconImageBitmap
                        if (customImg != null) {
                            Image(
                                bitmap = customImg,
                                contentDescription = "Custom preview icon",
                                modifier = Modifier.size(38.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Target App Name & Slot Info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.app.appName,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Slot: ${item.drawableName}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = EmeraldAccent,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }

                // 1x1 Widget, Edit & Remove buttons
                IconButton(onClick = onPinWidget) {
                    Icon(
                        imageVector = Icons.Default.Widgets,
                        contentDescription = "Pin 1x1 Home Widget",
                        tint = EmeraldAccent
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit custom icon",
                        tint = IndigoPrimary
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Remove from queue",
                        tint = CoralDanger
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Exact ComponentInfo string for verification
            Text(
                text = item.app.componentInfo,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
