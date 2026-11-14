package com.iconcraft.studio.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import android.widget.RemoteViews
import com.iconcraft.studio.R
import com.iconcraft.studio.model.CustomIconQueueItem
import java.io.File

/**
 * Universal 1x1 Home Screen Widget fallback (`CustomIconWidgetProvider`) supporting resizable
 * 1x1 widgets that launch the target app directly with zero Android shortcut corner watermark.
 */
class CustomIconWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetMappingStore(context)
        for (id in appWidgetIds) {
            store.removeBinding(id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_PIN_WIDGET_CALLBACK) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            val store = WidgetMappingStore(context)
            val pending = store.consumePendingPinBinding()
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && pending != null) {
                store.saveBinding(appWidgetId, pending)
                val mgr = AppWidgetManager.getInstance(context)
                updateSingleWidget(context, mgr, appWidgetId)
            }
        }
    }

    companion object {
        const val ACTION_PIN_WIDGET_CALLBACK = "com.iconcraft.studio.ACTION_PIN_WIDGET_CALLBACK"

        fun refreshAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, CustomIconWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componentName)
            for (id in ids) {
                updateSingleWidget(context, appWidgetManager, id)
            }
        }

        fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val store = WidgetMappingStore(context)
            val binding = store.getBinding(appWidgetId) ?: return

            val views = RemoteViews(context.packageName, R.layout.widget_custom_icon)

            // Load custom 512x512 PNG (scaled to 256x256 for safe RemoteViews Binder limits)
            val iconBitmap = loadWidgetBitmap(context, binding)
            if (iconBitmap != null) {
                views.setImageViewBitmap(R.id.widget_icon_image, iconBitmap)
            } else {
                views.setImageViewResource(R.id.widget_icon_image, R.drawable.ic_launcher)
            }

            if (binding.showLabel) {
                views.setTextViewText(R.id.widget_icon_label, binding.appName)
                views.setViewVisibility(R.id.widget_icon_label, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_icon_label, View.GONE)
            }

            // Build direct launch Intent for the target app
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(binding.packageName, binding.activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Requests the launcher to pin a 1x1 custom icon widget directly onto the Home Screen.
         */
        fun requestPinWidgetForQueueItem(
            context: Context,
            item: CustomIconQueueItem,
            showLabel: Boolean = false
        ): Boolean {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            if (!appWidgetManager.isRequestPinAppWidgetSupported) {
                return false
            }

            val binding = WidgetBinding(
                appName = item.app.appName,
                packageName = item.app.packageName,
                activityName = item.app.activityName,
                pngFilePath = item.customPngPath,
                showLabel = showLabel
            )
            WidgetMappingStore(context).savePendingPinBinding(binding)

            val providerComponent = ComponentName(context, CustomIconWidgetProvider::class.java)
            val callbackIntent = Intent(context, CustomIconWidgetProvider::class.java).apply {
                action = ACTION_PIN_WIDGET_CALLBACK
            }
            val successCallback = PendingIntent.getBroadcast(
                context,
                item.slotIndex,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            return appWidgetManager.requestPinAppWidget(providerComponent, null, successCallback)
        }

        private fun loadWidgetBitmap(context: Context, binding: WidgetBinding): Bitmap? {
            val path = binding.pngFilePath
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    val decoded = BitmapFactory.decodeFile(file.absolutePath)
                    if (decoded != null) {
                        return Bitmap.createScaledBitmap(decoded, 256, 256, true)
                    }
                }
            }
            // Fallback to original app icon via PackageManager
            return runCatching {
                val pm = context.packageManager
                val drawable = pm.getActivityIcon(ComponentName(binding.packageName, binding.activityName))
                val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, 192, 192)
                drawable.draw(canvas)
                bmp
            }.getOrNull()
        }
    }
}
