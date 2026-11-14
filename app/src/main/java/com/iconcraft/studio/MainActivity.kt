package com.iconcraft.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iconcraft.studio.apk.ApkPatcherAndSigner
import com.iconcraft.studio.apk.BaseIconPackManager
import com.iconcraft.studio.apk.InstalledIconCraftPackStatus
import com.iconcraft.studio.apk.InstalledIconPack
import com.iconcraft.studio.apk.LauncherInspector
import com.iconcraft.studio.apk.LocalKeystoreManager
import com.iconcraft.studio.data.AppRepository
import com.iconcraft.studio.data.QueueStorage
import com.iconcraft.studio.lab.ProceduralThemeEngine
import com.iconcraft.studio.model.CustomIconQueueItem
import com.iconcraft.studio.model.InstalledApp
import com.iconcraft.studio.ui.screens.AppPickerScreen
import com.iconcraft.studio.ui.screens.BrandApplyGuideCard
import com.iconcraft.studio.ui.screens.HomeQueueScreen
import com.iconcraft.studio.ui.screens.IconStudioScreen
import com.iconcraft.studio.ui.theme.EmeraldAccent
import com.iconcraft.studio.ui.theme.IconCraftTheme
import com.iconcraft.studio.ui.theme.IndigoPrimary
import com.iconcraft.studio.ui.theme.SlateSurface
import com.iconcraft.studio.widget.CustomIconWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private enum class ActiveScreen {
    HOME_QUEUE,
    APP_PICKER,
    ICON_STUDIO
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = AppRepository(applicationContext)
        val queueStorage = QueueStorage(applicationContext)
        val apkPatcherAndSigner = ApkPatcherAndSigner(applicationContext)
        val keystoreManager = LocalKeystoreManager(applicationContext)
        val basePackManager = BaseIconPackManager(applicationContext)

        setContent {
            IconCraftTheme {
                IconCraftApp(
                    repository = repository,
                    queueStorage = queueStorage,
                    apkPatcherAndSigner = apkPatcherAndSigner,
                    keystoreManager = keystoreManager,
                    basePackManager = basePackManager
                )
            }
        }
    }
}

@Composable
private fun IconCraftApp(
    repository: AppRepository,
    queueStorage: QueueStorage,
    apkPatcherAndSigner: ApkPatcherAndSigner,
    keystoreManager: LocalKeystoreManager,
    basePackManager: BaseIconPackManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var templateApkSize by remember { mutableLongStateOf(-1L) }

    // Installed third-party icon packs & selected Base Pack
    var installedIconPacks by remember { mutableStateOf<List<InstalledIconPack>>(emptyList()) }
    var selectedBasePackPackage by remember {
        mutableStateOf(basePackManager.getSelectedBasePackPackage())
    }
    var installedPackStatus by remember { mutableStateOf(InstalledIconCraftPackStatus()) }

    val queueItems = remember { mutableStateListOf<CustomIconQueueItem>() }
    var currentScreen by remember { mutableStateOf(ActiveScreen.HOME_QUEUE) }

    // Studio state
    var studioTargetApp by remember { mutableStateOf<InstalledApp?>(null) }
    var studioUploadedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var studioEditingQueueItem by remember { mutableStateOf<CustomIconQueueItem?>(null) }

    // Experimental Lab state
    var labInspirationBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Build & Sign state
    var isBuildingApk by remember { mutableStateOf(false) }
    var buildStepMessage by remember { mutableStateOf("Initializing...") }
    var lastBuildResult by remember { mutableStateOf<ApkPatcherAndSigner.BuildResult?>(null) }
    var diagnosticReport by remember { mutableStateOf<String?>(null) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    // Photo Picker launcher for Single-App Icon Studio
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        scope.launch {
            if (uri != null) {
                val decoded = decodeBitmapFromUri(context, uri)
                if (decoded != null) {
                    studioUploadedBitmap = decoded
                }
            }
            if (studioTargetApp != null) {
                currentScreen = ActiveScreen.ICON_STUDIO
            }
        }
    }

    // Photo Picker launcher for Experimental Lab Inspiration Image
    val labPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        scope.launch {
            if (uri != null) {
                val decoded = decodeBitmapFromUri(context, uri)
                if (decoded != null) {
                    labInspirationBitmap = decoded
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !isLoadingApps) {
                scope.launch {
                    installedPackStatus = basePackManager.inspectInstalledIconCraftPack()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        // Ensure persistent RSA-2048 PKCS#12 (.p12) keystore is created on first launch
        withContext(Dispatchers.IO) {
            runCatching { keystoreManager.getOrCreateSigningCredentials() }
        }
        templateApkSize = repository.getTemplateApkSize()
        val loadedPacks = basePackManager.loadInstalledIconPacks()
        installedIconPacks = loadedPacks
        if (selectedBasePackPackage != null && loadedPacks.none { it.packageName == selectedBasePackPackage }) {
            selectedBasePackPackage = null
            basePackManager.setSelectedBasePackPackage(null)
        }
        val loadedApps = repository.loadLaunchableApps()
        installedApps = loadedApps
        val savedQueue = queueStorage.loadQueue(loadedApps)
        val packStatus = basePackManager.inspectInstalledIconCraftPack()
        installedPackStatus = packStatus

        if (savedQueue.isEmpty() && packStatus.isInstalled && packStatus.customIconCount > 0) {
            val fetched = basePackManager.fetchInstalledIconCraftPack(
                installedApps = loadedApps,
                existingQueueItems = emptyList(),
                queueStorage = queueStorage,
                installedIconPacks = loadedPacks
            )
            if (fetched != null && fetched.mergedQueueItems.isNotEmpty()) {
                queueItems.clear()
                queueItems.addAll(fetched.mergedQueueItems)
                if (fetched.restoredBasePackPackage != null) {
                    selectedBasePackPackage = fetched.restoredBasePackPackage
                }
                isLoadingApps = false
                return@LaunchedEffect
            }
        }

        queueItems.clear()
        queueItems.addAll(savedQueue)
        isLoadingApps = false
    }

    BackHandler(enabled = currentScreen != ActiveScreen.HOME_QUEUE) {
        currentScreen = when (currentScreen) {
            ActiveScreen.ICON_STUDIO -> ActiveScreen.HOME_QUEUE
            ActiveScreen.APP_PICKER -> ActiveScreen.HOME_QUEUE
            ActiveScreen.HOME_QUEUE -> ActiveScreen.HOME_QUEUE
        }
    }

    Crossfade(targetState = currentScreen, label = "MainScreenTransition") { screen ->
        when (screen) {
            ActiveScreen.APP_PICKER -> {
                val customizedIds = remember(queueItems.size) {
                    queueItems.map { it.app.componentInfo }.toSet()
                }
                AppPickerScreen(
                    apps = installedApps,
                    isLoading = isLoadingApps,
                    customizedComponentIds = customizedIds,
                    onAppSelected = { selectedApp ->
                        studioTargetApp = selectedApp
                        val existing = queueItems.firstOrNull {
                            it.app.componentInfo == selectedApp.componentInfo
                        }
                        studioEditingQueueItem = existing
                        studioUploadedBitmap = (existing?.sourcePngPath ?: existing?.customPngPath)
                            ?.let { File(it) }
                            ?.takeIf { it.exists() }
                            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                        currentScreen = ActiveScreen.ICON_STUDIO
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onDismiss = { currentScreen = ActiveScreen.HOME_QUEUE }
                )
            }

            ActiveScreen.ICON_STUDIO -> {
                val targetApp = studioTargetApp
                if (targetApp != null) {
                    IconStudioScreen(
                        targetApp = targetApp,
                        uploadedBitmap = studioUploadedBitmap,
                        initialQueueItem = studioEditingQueueItem,
                        onPickNewPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onSaveIcon = { payload ->
                            scope.launch {
                                val existingIdx = queueItems.indexOfFirst {
                                    it.app.componentInfo == targetApp.componentInfo
                                }
                                val slotIndex = if (existingIdx >= 0) {
                                    queueItems[existingIdx].slotIndex
                                } else {
                                    val usedSlots = queueItems.map { it.slotIndex }.toSet()
                                    val available = (1..500).firstOrNull { it !in usedSlots }
                                    if (available == null) {
                                        snackbarHostState.showSnackbar(
                                            "All 500 icon slots are full. Remove an app from the queue first."
                                        )
                                        return@launch
                                    }
                                    available
                                }

                                val savedFile = queueStorage.saveCustomIconBitmap(
                                    slotIndex = slotIndex,
                                    bitmap = payload.renderedBitmap
                                )
                                val savedSourceFile = payload.sourceBitmap?.let { srcBmp ->
                                    queueStorage.saveSourceBitmap(
                                        slotIndex = slotIndex,
                                        bitmap = srcBmp
                                    )
                                }

                                val updatedItem = CustomIconQueueItem(
                                    app = targetApp,
                                    slotIndex = slotIndex,
                                    customPngPath = savedFile.absolutePath,
                                    sourcePngPath = savedSourceFile?.absolutePath
                                        ?: studioEditingQueueItem?.sourcePngPath,
                                    customPreviewBitmap = payload.renderedBitmap.asImageBitmap(),
                                    shapeMaskName = payload.shapeMaskName,
                                    imageScale = payload.imageScale,
                                    roundInnerCorners = payload.roundInnerCorners,
                                    backgroundColorArgb = payload.backgroundColorArgb,
                                    userZoom = payload.userZoom,
                                    userPanX = payload.userPanX,
                                    userPanY = payload.userPanY
                                )

                                if (existingIdx >= 0) {
                                    queueItems[existingIdx] = updatedItem
                                } else {
                                    queueItems.add(updatedItem)
                                }
                                queueStorage.saveQueue(queueItems.toList())
                                CustomIconWidgetProvider.refreshAllWidgets(context)
                                currentScreen = ActiveScreen.HOME_QUEUE
                            }
                        },
                        onCancel = {
                            currentScreen = ActiveScreen.HOME_QUEUE
                        }
                    )
                } else {
                    currentScreen = ActiveScreen.HOME_QUEUE
                }
            }

            ActiveScreen.HOME_QUEUE -> {
                HomeQueueScreen(
                    queueItems = queueItems,
                    installedApps = installedApps,
                    totalInstalledApps = installedApps.size,
                    templateApkSizeBytes = templateApkSize,
                    installedIconPacks = installedIconPacks,
                    selectedBasePackPackage = selectedBasePackPackage,
                    onSelectBasePack = { pkg ->
                        selectedBasePackPackage = pkg
                        basePackManager.setSelectedBasePackPackage(pkg)
                    },
                    installedPackStatus = installedPackStatus,
                    onFetchCurrentPackClick = {
                        scope.launch {
                            val fetched = basePackManager.fetchInstalledIconCraftPack(
                                installedApps = installedApps,
                                existingQueueItems = queueItems.toList(),
                                queueStorage = queueStorage,
                                installedIconPacks = installedIconPacks
                            )
                            if (fetched == null) {
                                snackbarHostState.showSnackbar(
                                    "IconCraft Pack (com.iconcraft.pack) is not installed yet."
                                )
                            } else {
                                queueItems.clear()
                                queueItems.addAll(fetched.mergedQueueItems)
                                if (fetched.restoredBasePackPackage != null) {
                                    selectedBasePackPackage = fetched.restoredBasePackPackage
                                }
                                installedPackStatus = basePackManager.inspectInstalledIconCraftPack()
                                CustomIconWidgetProvider.refreshAllWidgets(context)
                                val msg = if (fetched.newlyImportedCount > 0) {
                                    "Retained ${fetched.newlyImportedCount} custom icon(s) from installed IconCraft Pack into Queue"
                                } else {
                                    "All ${fetched.mergedQueueItems.size} active icon(s) from IconCraft Pack are already in your Queue"
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    labInspirationBitmap = labInspirationBitmap,
                    snackbarHostState = snackbarHostState,
                    onCustomizeAppClick = {
                        currentScreen = ActiveScreen.APP_PICKER
                    },
                    onEditQueueItem = { item ->
                        studioTargetApp = item.app
                        studioEditingQueueItem = item
                        scope.launch {
                            val existingBmp = withContext(Dispatchers.IO) {
                                val preferredPath = item.sourcePngPath ?: item.customPngPath
                                preferredPath
                                    ?.let { File(it) }
                                    ?.takeIf { it.exists() }
                                    ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                            }
                            studioUploadedBitmap = existingBmp
                            currentScreen = ActiveScreen.ICON_STUDIO
                        }
                    },
                    onRemoveQueueItem = { item ->
                        queueItems.removeAll { it.app.componentInfo == item.app.componentInfo }
                        scope.launch {
                            queueStorage.saveQueue(queueItems.toList())
                        }
                    },
                    onPinWidgetItem = { item ->
                        val pinned = CustomIconWidgetProvider.requestPinWidgetForQueueItem(
                            context = context,
                            item = item,
                            showLabel = false
                        )
                        if (!pinned) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Long-press your Home Screen → Widgets → IconCraft 1×1 Custom Icon"
                                )
                            }
                        }
                    },
                    onBuildPackClick = {
                        if (!isBuildingApk && (queueItems.isNotEmpty() || selectedBasePackPackage != null)) {
                            isBuildingApk = true
                            buildStepMessage = "Starting APK patcher..."
                            scope.launch {
                                val mergedPayload = basePackManager.buildMergedPackPayload(
                                    userQueueItems = queueItems.toList(),
                                    installedApps = installedApps,
                                    basePackPackageName = selectedBasePackPackage,
                                    maxSlots = 500,
                                    onProgress = { msg -> buildStepMessage = msg }
                                )

                                val result = apkPatcherAndSigner.buildAndSignIconPack(
                                    queueItems = mergedPayload.items,
                                    appFilterExtras = mergedPayload.extras,
                                    inMemoryPngBytesByFilename = mergedPayload.inMemoryPngBytesByFilename,
                                    inheritedBaseCount = mergedPayload.inheritedBaseCount,
                                    basePackLabel = mergedPayload.basePackLabel,
                                    basePackPackage = mergedPayload.basePackPackage,
                                    customComponents = mergedPayload.customComponents,
                                    onProgress = { msg -> buildStepMessage = msg }
                                )
                                isBuildingApk = false
                                result.onSuccess { buildInfo ->
                                    lastBuildResult = buildInfo
                                    val launchedDirect = apkPatcherAndSigner.promptInstallApk(
                                        buildInfo.signedApkFile
                                    )
                                    if (!launchedDirect) {
                                        snackbarHostState.showSnackbar(
                                            "Grant 'Install unknown apps' permission, then tap Install below."
                                        )
                                    }
                                }.onFailure { err ->
                                    snackbarHostState.showSnackbar(
                                        "Build failed: ${err.message ?: "Unknown error"}"
                                    )
                                }
                            }
                        }
                    },
                    onPickLabInspirationClick = {
                        labPhotoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onBatchExportLabPack = { inspiration, palette, style, invertVariant, preserveSelective, syncToSelectiveQueue ->
                        if (!isBuildingApk && installedApps.isNotEmpty()) {
                            isBuildingApk = true
                            buildStepMessage = "Preparing procedural batch export..."
                            scope.launch {
                                val batchResult = runCatching {
                                    val batchQueue = withContext(Dispatchers.Default) {
                                        val labDir = File(context.filesDir, "lab_icons").apply { mkdirs() }
                                        val outList = ArrayList<CustomIconQueueItem>()
                                        val usedSlots = HashSet<Int>()
                                        val alreadyMappedComponents = HashSet<String>()

                                        if (preserveSelective) {
                                            for (sel in queueItems) {
                                                outList.add(sel)
                                                usedSlots.add(sel.slotIndex)
                                                alreadyMappedComponents.add(sel.app.componentInfo)
                                            }
                                        }

                                        val appsToTheme = installedApps
                                            .filter { it.componentInfo !in alreadyMappedComponents }
                                            .take(200 - outList.size)

                                        val totalToRender = appsToTheme.size
                                        for ((idx, app) in appsToTheme.withIndex()) {
                                            val slot = (1..500).first { it !in usedSlots }
                                            usedSlots.add(slot)
                                            withContext(Dispatchers.Main) {
                                                buildStepMessage = "Procedural icon ${idx + 1} / $totalToRender (${app.appName})..."
                                            }
                                            val fgGlyph = ProceduralThemeEngine.extractAppForegroundGlyph(
                                                context = context,
                                                app = app,
                                                targetSize = 256
                                            )
                                            val rendered512 = ProceduralThemeEngine.renderProceduralIcon(
                                                inspirationBitmap = inspiration,
                                                palette = palette,
                                                foregroundGlyph = fgGlyph,
                                                style = style,
                                                outputSize = 512,
                                                invertSilhouette = invertVariant
                                            )
                                            val savedFile = if (syncToSelectiveQueue) {
                                                queueStorage.saveCustomIconBitmap(slot, rendered512)
                                            } else {
                                                val outFile = File(labDir, "lab_icon_%03d.png".format(slot))
                                                FileOutputStream(outFile).use { fos ->
                                                    rendered512.compress(Bitmap.CompressFormat.PNG, 100, fos)
                                                }
                                                outFile
                                            }
                                            val savedSourceFile = if (syncToSelectiveQueue) {
                                                queueStorage.saveSourceBitmap(slot, rendered512)
                                            } else null

                                            outList.add(
                                                CustomIconQueueItem(
                                                    app = app,
                                                    slotIndex = slot,
                                                    customPngPath = savedFile.absolutePath,
                                                    sourcePngPath = savedSourceFile?.absolutePath,
                                                    customPreviewBitmap = rendered512.asImageBitmap(),
                                                    shapeMaskName = "FULL_BLEED",
                                                    imageScale = 1.0f,
                                                    roundInnerCorners = false,
                                                    backgroundColorArgb = 0
                                                )
                                            )
                                        }
                                        outList
                                    }

                                    if (syncToSelectiveQueue) {
                                        queueItems.clear()
                                        queueItems.addAll(batchQueue)
                                        queueStorage.saveQueue(batchQueue)
                                    }

                                    apkPatcherAndSigner.buildAndSignIconPack(
                                        queueItems = batchQueue,
                                        customComponents = batchQueue.map { it.app.componentInfo },
                                        onProgress = { msg -> buildStepMessage = msg }
                                    ).getOrThrow()
                                }

                                isBuildingApk = false
                                batchResult.onSuccess { buildInfo ->
                                    lastBuildResult = buildInfo
                                    val launchedDirect = apkPatcherAndSigner.promptInstallApk(
                                        buildInfo.signedApkFile
                                    )
                                    if (!launchedDirect) {
                                        snackbarHostState.showSnackbar(
                                            "Grant 'Install unknown apps' permission, then tap Install below."
                                        )
                                    }
                                }.onFailure { err ->
                                    snackbarHostState.showSnackbar(
                                        "Batch build failed: ${err.message ?: "Unknown error"}"
                                    )
                                }
                            }
                        }
                    },
                    onInspectLauncherClick = {
                        scope.launch {
                            diagnosticReport = "Scanning launcher & icon packs..."
                            diagnosticReport = LauncherInspector.generateDiagnosticReport(context)
                        }
                    }
                )
            }
        }
    }

    val currentReport = diagnosticReport
    if (currentReport != null) {
        AlertDialog(
            onDismissRequest = { diagnosticReport = null },
            containerColor = SlateSurface,
            title = {
                Text(
                    text = "Launcher & Icon Pack Diagnostic",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = currentReport,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(currentReport))
                        diagnosticReport = null
                        scope.launch {
                            snackbarHostState.showSnackbar("Copied diagnostic report to clipboard")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                ) {
                    Text("Copy Report")
                }
            },
            dismissButton = {
                TextButton(onClick = { diagnosticReport = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Progress Dialog while patching & signing APK
    if (isBuildingApk) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = SlateSurface,
            title = {
                Text(
                    text = "Building & Signing Icon Pack",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    CircularProgressIndicator(color = IndigoPrimary, modifier = Modifier.size(32.dp))
                    Text(
                        text = buildStepMessage,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {}
        )
    }

    // Build Complete / Re-Install Dialog
    val completedBuild = lastBuildResult
    if (completedBuild != null && !isBuildingApk) {
        AlertDialog(
            onDismissRequest = { lastBuildResult = null },
            containerColor = SlateSurface,
            icon = {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = EmeraldAccent,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "IconCraft Pack Signed!",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    val summaryMsg = if (completedBuild.basePackLabel != null) {
                        "Customized ${completedBuild.customizedCount} app(s) + inherited ${completedBuild.inheritedBaseCount} installed app icon(s) from ${completedBuild.basePackLabel}."
                    } else {
                        "Customized ${completedBuild.customizedCount} ${if (completedBuild.customizedCount == 1) "app" else "apps"}. All other apps remain 100% untouched."
                    }
                    Text(
                        text = summaryMsg,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Version Code: v${completedBuild.versionCode}\nSigning: RSA-2048 (.p12) • ApkSigner v1+v2+v3\nFormat: UTF-8 + Binary AXML (500-slot template)",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BrandApplyGuideCard(
                        sampleAppName = queueItems.firstOrNull()?.app?.appName ?: "Letterboxd",
                        canPinWidget = queueItems.isNotEmpty(),
                        onPinWidgetClick = {
                            val firstItem = queueItems.firstOrNull()
                            if (firstItem != null) {
                                lastBuildResult = null
                                CustomIconWidgetProvider.requestPinWidgetForQueueItem(
                                    context = context,
                                    item = firstItem,
                                    showLabel = false
                                )
                            }
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        apkPatcherAndSigner.promptInstallApk(completedBuild.signedApkFile)
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdateAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Install Icon Pack")
                }
            },
            dismissButton = {
                TextButton(onClick = { lastBuildResult = null }) {
                    Text("Done")
                }
            }
        )
    }
}

private suspend fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val maxDim = maxOf(info.size.width, info.size.height)
                    if (maxDim > 1024) {
                        val scale = 1024f / maxDim.toFloat()
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            } else {
                // First pass: decode bounds only (inJustDecodeBounds = true)
                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, boundsOptions)
                }
                val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                var sampleSize = 1
                while (maxDim / sampleSize > 1024) {
                    sampleSize *= 2
                }
                // Second pass: decode downsampled bitmap
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }
            }
            rawBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
    }
