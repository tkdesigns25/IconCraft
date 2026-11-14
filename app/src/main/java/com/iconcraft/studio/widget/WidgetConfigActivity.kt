package com.iconcraft.studio.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iconcraft.studio.data.AppRepository
import com.iconcraft.studio.data.QueueStorage
import com.iconcraft.studio.model.CustomIconQueueItem
import com.iconcraft.studio.ui.theme.EmeraldAccent
import com.iconcraft.studio.ui.theme.IconCraftTheme
import com.iconcraft.studio.ui.theme.IndigoPrimary
import com.iconcraft.studio.ui.theme.SlateSurface
import com.iconcraft.studio.ui.theme.SlateSurfaceVariant

/**
 * Configuration activity launched when placing an IconCraft 1x1 Custom Icon Widget
 * on the Home Screen. Lets the user pick any customized app from their queue.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Check if this widget was triggered via in-app one-tap Pin Widget
        val store = WidgetMappingStore(applicationContext)
        val pendingBinding = store.consumePendingPinBinding()
        if (pendingBinding != null) {
            completeWidgetSetup(pendingBinding)
            return
        }

        val repository = AppRepository(applicationContext)
        val queueStorage = QueueStorage(applicationContext)

        setContent {
            IconCraftTheme {
                WidgetConfigScreen(
                    repository = repository,
                    queueStorage = queueStorage,
                    onItemChosen = { item, showLabel ->
                        completeWidgetSetup(
                            WidgetBinding(
                                appName = item.app.appName,
                                packageName = item.app.packageName,
                                activityName = item.app.activityName,
                                pngFilePath = item.customPngPath,
                                showLabel = showLabel
                            )
                        )
                    },
                    onCancel = { finish() }
                )
            }
        }
    }

    private fun completeWidgetSetup(binding: WidgetBinding) {
        val store = WidgetMappingStore(applicationContext)
        store.saveBinding(appWidgetId, binding)

        val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        CustomIconWidgetProvider.updateSingleWidget(applicationContext, appWidgetManager, appWidgetId)

        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(
    repository: AppRepository,
    queueStorage: QueueStorage,
    onItemChosen: (CustomIconQueueItem, Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var queueItems by remember { mutableStateOf<List<CustomIconQueueItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showLabel by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val apps = repository.loadLaunchableApps()
        val savedQueue = queueStorage.loadQueue(apps)
        queueItems = if (savedQueue.isNotEmpty()) {
            savedQueue
        } else {
            // If queue is empty, let user pick from installed apps
            apps.take(50).mapIndexed { index, app ->
                CustomIconQueueItem(app = app, slotIndex = index + 1)
            }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Select 1×1 Widget Icon",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Launches directly with zero shortcut watermark",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Toggle for showing app label below 1x1 widget icon
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = SlateSurface,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show App Label Under Icon",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Disable for a clean icon-only 1×1 home tile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showLabel,
                        onCheckedChange = { showLabel = it }
                    )
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = IndigoPrimary)
                }
            } else if (queueItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Widgets,
                            contentDescription = null,
                            tint = IndigoPrimary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No customized icons in queue yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Open IconCraft first to customize an app icon, then place this 1×1 widget.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = queueItems,
                        key = { it.app.componentInfo }
                    ) { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onItemChosen(item, showLabel) }
                                .border(
                                    width = 1.dp,
                                    color = SlateSurfaceVariant,
                                    shape = RoundedCornerShape(14.dp)
                                ),
                            color = SlateSurface,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SlateSurfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val preview = item.customPreviewBitmap ?: item.app.iconImageBitmap
                                    if (preview != null) {
                                        Image(
                                            bitmap = preview,
                                            contentDescription = item.app.appName,
                                            modifier = Modifier.size(46.dp),
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

                                Spacer(modifier = Modifier.width(14.dp))

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
                                        text = if (item.customPngPath != null) "Custom 512×512 Icon" else "Default Icon",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = EmeraldAccent,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
