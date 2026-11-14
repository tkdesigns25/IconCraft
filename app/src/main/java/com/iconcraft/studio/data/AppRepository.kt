package com.iconcraft.studio.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asImageBitmap
import com.iconcraft.studio.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    /**
     * Queries all launchable apps using:
     * PackageManager.queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER), 0)
     */
    suspend fun loadLaunchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

        val seenComponents = HashSet<String>()
        val apps = ArrayList<InstalledApp>(resolveInfos.size)

        for (resolveInfo in resolveInfos) {
            val activityInfo = resolveInfo.activityInfo ?: continue
            val packageName = activityInfo.packageName ?: continue
            val activityName = activityInfo.name ?: continue

            // Skip IconCraft's own generated pack if installed
            if (packageName == "com.iconcraft.pack") continue

            val componentKey = "ComponentInfo{$packageName/$activityName}"
            if (!seenComponents.add(componentKey)) continue

            val label = runCatching {
                resolveInfo.loadLabel(pm)?.toString()?.trim()
            }.getOrNull().takeUnless { it.isNullOrEmpty() } ?: packageName

            val bitmap = runCatching {
                val drawable = resolveInfo.loadIcon(pm)
                drawableToBitmap(drawable, 144)
            }.getOrNull()

            apps.add(
                InstalledApp(
                    appName = label,
                    packageName = packageName,
                    activityName = activityName,
                    iconBitmap = bitmap,
                    iconImageBitmap = bitmap?.asImageBitmap()
                )
            )
        }

        apps.sortedWith(
            compareBy<InstalledApp> { it.appName.lowercase() }
                .thenBy { it.packageName.lowercase() }
        )
    }

    /**
     * Checks that `template_iconpack.apk` is bundled in `:app/src/main/assets/`
     * and returns its byte size (or -1L if missing).
     */
    suspend fun getTemplateApkSize(): Long = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("template_iconpack.apk").use { input ->
                var total = 0L
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    total += read
                }
                total
            }
        }.getOrDefault(-1L)
    }

    private fun drawableToBitmap(drawable: Drawable, targetSizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val bmp = drawable.bitmap
            if (bmp.width == targetSizePx && bmp.height == targetSizePx) {
                return bmp
            }
        }
        val bitmap = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
