package com.iconcraft.studio.widget

import android.content.Context
import org.json.JSONObject

data class WidgetBinding(
    val appName: String,
    val packageName: String,
    val activityName: String,
    val pngFilePath: String?,
    val showLabel: Boolean = false
)

class WidgetMappingStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveBinding(appWidgetId: Int, binding: WidgetBinding) {
        val json = JSONObject().apply {
            put("appName", binding.appName)
            put("packageName", binding.packageName)
            put("activityName", binding.activityName)
            put("pngFilePath", binding.pngFilePath ?: "")
            put("showLabel", binding.showLabel)
        }
        prefs.edit().putString("widget_$appWidgetId", json.toString()).apply()
    }

    fun getBinding(appWidgetId: Int): WidgetBinding? {
        val raw = prefs.getString("widget_$appWidgetId", null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            WidgetBinding(
                appName = obj.optString("appName"),
                packageName = obj.optString("packageName"),
                activityName = obj.optString("activityName"),
                pngFilePath = obj.optString("pngFilePath").takeIf { it.isNotEmpty() },
                showLabel = obj.optBoolean("showLabel", false)
            )
        }.getOrNull()
    }

    fun removeBinding(appWidgetId: Int) {
        prefs.edit().remove("widget_$appWidgetId").apply()
    }

    fun savePendingPinBinding(binding: WidgetBinding) {
        val json = JSONObject().apply {
            put("appName", binding.appName)
            put("packageName", binding.packageName)
            put("activityName", binding.activityName)
            put("pngFilePath", binding.pngFilePath ?: "")
            put("showLabel", binding.showLabel)
        }
        prefs.edit().putString(KEY_PENDING_PIN, json.toString()).apply()
    }

    fun consumePendingPinBinding(): WidgetBinding? {
        val raw = prefs.getString(KEY_PENDING_PIN, null) ?: return null
        prefs.edit().remove(KEY_PENDING_PIN).apply()
        return runCatching {
            val obj = JSONObject(raw)
            WidgetBinding(
                appName = obj.optString("appName"),
                packageName = obj.optString("packageName"),
                activityName = obj.optString("activityName"),
                pngFilePath = obj.optString("pngFilePath").takeIf { it.isNotEmpty() },
                showLabel = obj.optBoolean("showLabel", false)
            )
        }.getOrNull()
    }

    companion object {
        private const val PREFS_NAME = "iconcraft_widget_mappings"
        private const val KEY_PENDING_PIN = "pending_pin_binding"
    }
}
