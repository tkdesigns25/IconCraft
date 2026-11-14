package com.iconcraft.studio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.iconcraft.studio.model.CustomIconQueueItem
import com.iconcraft.studio.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class QueueStorage(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val iconsDir = File(context.filesDir, "queue_icons").apply { mkdirs() }
    private val legacyIconsDir = File(context.filesDir, "custom_icons")
    private val sourcesDir = File(context.filesDir, "source_icons").apply { mkdirs() }

    suspend fun saveCustomIconBitmap(slotIndex: Int, bitmap: Bitmap): File = withContext(Dispatchers.IO) {
        iconsDir.mkdirs()
        val outFile = File(iconsDir, "custom_icon_%03d.png".format(slotIndex))
        val scaled = if (bitmap.width == 512 && bitmap.height == 512 && bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, 512, 512, true)
                .copy(Bitmap.Config.ARGB_8888, false)
        }
        FileOutputStream(outFile).use { fos ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        outFile
    }

    suspend fun saveSourceBitmap(slotIndex: Int, bitmap: Bitmap): File = withContext(Dispatchers.IO) {
        sourcesDir.mkdirs()
        val outFile = File(sourcesDir, "source_icon_%03d.png".format(slotIndex))
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        outFile
    }

    suspend fun saveQueue(items: List<CustomIconQueueItem>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("appName", item.app.appName)
                put("packageName", item.app.packageName)
                put("activityName", item.app.activityName)
                put("slotIndex", item.slotIndex)
                put("customPngPath", item.customPngPath ?: "")
                put("sourcePngPath", item.sourcePngPath ?: "")
                put("shapeMaskName", item.shapeMaskName)
                put("imageScale", item.imageScale.toDouble())
                put("roundInnerCorners", item.roundInnerCorners)
                put("backgroundColorArgb", item.backgroundColorArgb)
                put("userZoom", item.userZoom.toDouble())
                put("userPanX", item.userPanX.toDouble())
                put("userPanY", item.userPanY.toDouble())
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_QUEUE_JSON, arr.toString()).apply()
    }

    suspend fun loadQueue(installedApps: List<InstalledApp>): List<CustomIconQueueItem> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_QUEUE_JSON, null) ?: return@withContext emptyList()
        val byComponent = installedApps.associateBy { it.componentInfo }
        val result = ArrayList<CustomIconQueueItem>()

        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val appName = obj.optString("appName")
                val packageName = obj.optString("packageName")
                val activityName = obj.optString("activityName")
                val slotIndex = obj.optInt("slotIndex", i + 1)
                val rawCustomPngPath = obj.optString("customPngPath").takeIf { it.isNotEmpty() }
                val sourcePngPath = obj.optString("sourcePngPath").takeIf { it.isNotEmpty() }
                val shapeMaskName = obj.optString("shapeMaskName", "ROUNDED_SQUARE")
                val imageScale = obj.optDouble("imageScale", 1.0).toFloat()
                val roundInnerCorners = obj.optBoolean("roundInnerCorners", false)
                val backgroundColorArgb = obj.optInt("backgroundColorArgb", 0)
                val userZoom = obj.optDouble("userZoom", 1.0).toFloat()
                val userPanX = obj.optDouble("userPanX", 0.0).toFloat()
                val userPanY = obj.optDouble("userPanY", 0.0).toFloat()

                val resolvedPngFile = resolveCustomPngFile(slotIndex, rawCustomPngPath)
                val resolvedCustomPngPath = resolvedPngFile?.absolutePath ?: rawCustomPngPath

                val compKey = "ComponentInfo{$packageName/$activityName}"
                val app = byComponent[compKey] ?: InstalledApp(
                    appName = appName,
                    packageName = packageName,
                    activityName = activityName,
                    iconBitmap = null,
                    iconImageBitmap = null
                )

                val previewBitmap = resolvedPngFile
                    ?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }

                result.add(
                    CustomIconQueueItem(
                        app = app,
                        slotIndex = slotIndex,
                        customPngPath = resolvedCustomPngPath,
                        sourcePngPath = sourcePngPath,
                        customPreviewBitmap = previewBitmap,
                        shapeMaskName = shapeMaskName,
                        imageScale = imageScale,
                        roundInnerCorners = roundInnerCorners,
                        backgroundColorArgb = backgroundColorArgb,
                        userZoom = userZoom,
                        userPanX = userPanX,
                        userPanY = userPanY
                    )
                )
            }
        }
        result
    }

    private fun resolveCustomPngFile(slotIndex: Int, savedPath: String?): File? {
        if (!savedPath.isNullOrBlank()) {
            val direct = File(savedPath)
            if (direct.exists()) return direct
        }
        val slotFileName = "custom_icon_%03d.png".format(slotIndex)
        val inQueueIcons = File(iconsDir, slotFileName)
        if (inQueueIcons.exists()) return inQueueIcons

        val inLegacyIcons = File(legacyIconsDir, slotFileName)
        if (inLegacyIcons.exists()) {
            // Migrate legacy file into queue_icons/
            runCatching {
                iconsDir.mkdirs()
                inLegacyIcons.copyTo(inQueueIcons, overwrite = true)
                return inQueueIcons
            }
            return inLegacyIcons
        }
        return null
    }

    companion object {
        private const val PREFS_NAME = "iconcraft_queue_prefs"
        private const val KEY_QUEUE_JSON = "saved_queue_items"
    }
}
