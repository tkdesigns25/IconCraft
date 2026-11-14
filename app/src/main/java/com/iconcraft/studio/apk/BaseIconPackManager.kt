package com.iconcraft.studio.apk

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.iconcraft.studio.model.CustomIconQueueItem
import com.iconcraft.studio.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream

data class InstalledIconPack(
    val packageName: String,
    val packLabel: String,
    val iconImageBitmap: ImageBitmap? = null
)

/**
 * Optional `<iconback>`, `<iconmask>`, `<iconupon>`, and `<scale>` definitions
 * inherited from a selected Base Icon Pack.
 * When no Base Pack is selected ("None - Keep System Icons"), all lists are empty
 * and `scaleFactor` is null so non-customized apps stay 100% untouched.
 */
data class AppFilterExtras(
    val iconBackSlotNames: List<String> = emptyList(),
    val iconMaskSlotNames: List<String> = emptyList(),
    val iconUponSlotNames: List<String> = emptyList(),
    val scaleFactor: String? = null
) {
    val isEmpty: Boolean
        get() = iconBackSlotNames.isEmpty() &&
            iconMaskSlotNames.isEmpty() &&
            iconUponSlotNames.isEmpty() &&
            scaleFactor.isNullOrBlank()
}

data class MergedPackPayload(
    val items: List<CustomIconQueueItem>,
    val extras: AppFilterExtras = AppFilterExtras(),
    val inMemoryPngBytesByFilename: Map<String, ByteArray> = emptyMap(),
    val userCustomizedCount: Int = 0,
    val inheritedBaseCount: Int = 0,
    val basePackLabel: String? = null
)

class BaseIconPackManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedBasePackPackage(): String? {
        return prefs.getString(KEY_BASE_PACK_PKG, null)?.takeIf { it.isNotBlank() }
    }

    fun setSelectedBasePackPackage(packageName: String?) {
        prefs.edit().putString(KEY_BASE_PACK_PKG, packageName ?: "").apply()
    }

    /**
     * Queries PackageManager for installed third-party icon packs using standard intents:
     * - `org.adw.launcher.THEMES`
     * - `com.novalauncher.THEME`
     * - `com.anddoes.launcher.THEME`
     */
    suspend fun loadInstalledIconPacks(): List<InstalledIconPack> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val themeTokens = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "org.adw.launcher.icons.ACTION_PICK_ICON",
            "ch.deletescape.lawnchair.ICONPACK"
        )

        val discoveredPackages = LinkedHashSet<String>()

        for (token in themeTokens) {
            // Query as direct intent action
            runCatching {
                val byAction = pm.queryIntentActivities(Intent(token), PackageManager.GET_META_DATA)
                for (ri in byAction) {
                    discoveredPackages.add(ri.activityInfo.packageName)
                }
            }
            // Query as category under ACTION_MAIN (used by Nova/Nothing/Apex style manifests)
            runCatching {
                val byCategory = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(token),
                    PackageManager.GET_META_DATA
                )
                for (ri in byCategory) {
                    discoveredPackages.add(ri.activityInfo.packageName)
                }
            }
        }

        // Exclude IconCraft studio and our generated IconCraft Pack
        discoveredPackages.remove(context.packageName)
        discoveredPackages.remove("com.iconcraft.pack")

        val packs = ArrayList<InstalledIconPack>()
        for (pkg in discoveredPackages) {
            runCatching {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString().ifBlank { pkg }
                val iconDrawable = pm.getApplicationIcon(appInfo)
                val bmp = renderDrawableToBitmap(iconDrawable, 96)
                packs.add(
                    InstalledIconPack(
                        packageName = pkg,
                        packLabel = label,
                        iconImageBitmap = bmp?.asImageBitmap()
                    )
                )
            }
        }

        packs.sortedBy { it.packLabel.lowercase() }
    }

    /**
     * Merges the user's IconCraft Custom Queue (highest priority, 512x512 PNG) with the selected
     * Base Pack (256x256 ARGB_8888 PNG for currently installed launchable apps not overridden by the user,
     * plus `<iconback>`, `<iconmask>`, `<iconupon>`, and `<scale>`).
     */
    suspend fun buildMergedPackPayload(
        userQueueItems: List<CustomIconQueueItem>,
        installedApps: List<InstalledApp>,
        basePackPackageName: String?,
        maxSlots: Int = 500,
        onProgress: (String) -> Unit = {}
    ): MergedPackPayload = withContext(Dispatchers.IO) {
        if (basePackPackageName.isNullOrBlank()) {
            return@withContext MergedPackPayload(
                items = userQueueItems,
                extras = AppFilterExtras(),
                inMemoryPngBytesByFilename = emptyMap(),
                userCustomizedCount = userQueueItems.size,
                inheritedBaseCount = 0,
                basePackLabel = null
            )
        }

        val pm = context.packageManager
        val baseResources = runCatching {
            pm.getResourcesForApplication(basePackPackageName)
        }.getOrNull() ?: return@withContext MergedPackPayload(
            items = userQueueItems,
            extras = AppFilterExtras(),
            inMemoryPngBytesByFilename = emptyMap(),
            userCustomizedCount = userQueueItems.size,
            inheritedBaseCount = 0,
            basePackLabel = null
        )

        val basePackLabel = runCatching {
            val appInfo = pm.getApplicationInfo(basePackPackageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(basePackPackageName)

        onProgress("Reading Base Pack ($basePackLabel) appfilter.xml...")
        val parsed = parseBasePackAppFilter(baseResources, basePackPackageName)

        val mergedItems = ArrayList<CustomIconQueueItem>(userQueueItems.size + installedApps.size)
        val usedSlots = HashSet<Int>()
        val overriddenComponents = HashSet<String>()
        val inMemoryBytes = HashMap<String, ByteArray>()

        // 1. User-customized apps in IconCraft ALWAYS override the Base Pack entry
        for (userItem in userQueueItems) {
            mergedItems.add(userItem)
            usedSlots.add(userItem.slotIndex)
            overriddenComponents.add(userItem.app.componentInfo)
        }

        fun allocateNextSlot(): Int? {
            return (1..maxSlots).firstOrNull { it !in usedSlots }?.also { usedSlots.add(it) }
        }

        // Cache already-converted Base Pack drawables so if multiple components share a drawable or mask,
        // we only render/encode it once.
        val drawableNameToSlotName = HashMap<String, String>()

        fun importBaseDrawableToSlot(baseDrawableName: String): String? {
            drawableNameToSlotName[baseDrawableName]?.let { return it }
            val slot = allocateNextSlot() ?: return null
            val pngBytes = renderBasePackDrawableToPngBytes(
                baseResources = baseResources,
                basePackageName = basePackPackageName,
                drawableName = baseDrawableName,
                targetSize = 256
            ) ?: run {
                usedSlots.remove(slot)
                return null
            }
            val slotName = "custom_icon_%03d".format(slot)
            inMemoryBytes["$slotName.png"] = pngBytes
            drawableNameToSlotName[baseDrawableName] = slotName
            return slotName
        }

        // 2. Import <iconback>, <iconmask>, <iconupon>, and <scale> from Base Pack if present
        val iconBackSlots = parsed.iconBackDrawables.mapNotNull { importBaseDrawableToSlot(it) }
        val iconMaskSlots = parsed.iconMaskDrawables.mapNotNull { importBaseDrawableToSlot(it) }
        val iconUponSlots = parsed.iconUponDrawables.mapNotNull { importBaseDrawableToSlot(it) }

        val extras = AppFilterExtras(
            iconBackSlotNames = iconBackSlots,
            iconMaskSlotNames = iconMaskSlots,
            iconUponSlotNames = iconUponSlots,
            scaleFactor = parsed.scaleFactor
        )

        // 3. Match ONLY launchable apps currently installed on the user's device that aren't overridden
        var inheritedCount = 0
        for ((idx, installedApp) in installedApps.withIndex()) {
            if (installedApp.componentInfo in overriddenComponents) continue
            if (usedSlots.size >= maxSlots) break

            val baseDrawableName = parsed.componentToDrawable[installedApp.componentInfo]
                ?: parsed.packageFallbackToDrawable[installedApp.packageName]
                ?: continue

            onProgress("Inheriting Base Pack icon for ${installedApp.appName} (${idx + 1}/${installedApps.size})...")

            val slot = allocateNextSlot() ?: break
            val slotName = "custom_icon_%03d".format(slot)

            // Reuse PNG bytes if another activity of the same app used the exact same base drawable
            val existingSlotForDrawable = drawableNameToSlotName[baseDrawableName]
            val pngBytes = if (existingSlotForDrawable != null) {
                inMemoryBytes["$existingSlotForDrawable.png"]
            } else {
                renderBasePackDrawableToPngBytes(
                    baseResources = baseResources,
                    basePackageName = basePackPackageName,
                    drawableName = baseDrawableName,
                    targetSize = 256
                )
            }

            if (pngBytes == null) {
                usedSlots.remove(slot)
                continue
            }

            inMemoryBytes["$slotName.png"] = pngBytes
            drawableNameToSlotName.putIfAbsent(baseDrawableName, slotName)

            mergedItems.add(
                CustomIconQueueItem(
                    app = installedApp,
                    slotIndex = slot,
                    customPngPath = null
                )
            )
            inheritedCount++
        }

        MergedPackPayload(
            items = mergedItems,
            extras = extras,
            inMemoryPngBytesByFilename = inMemoryBytes,
            userCustomizedCount = userQueueItems.size,
            inheritedBaseCount = inheritedCount,
            basePackLabel = basePackLabel
        )
    }

    private data class ParsedBasePackFilter(
        val componentToDrawable: Map<String, String>,
        val packageFallbackToDrawable: Map<String, String>,
        val iconBackDrawables: List<String>,
        val iconMaskDrawables: List<String>,
        val iconUponDrawables: List<String>,
        val scaleFactor: String?
    )

    /**
     * Parses `appfilter.xml` from the Base Pack, checking:
     * 1. `res/xml/appfilter.xml` via `XmlResourceParser`
     * 2. `res/raw/appfilter.xml`
     * 3. `assets/appfilter.xml`
     */
    private fun parseBasePackAppFilter(
        baseResources: Resources,
        basePkg: String
    ): ParsedBasePackFilter {
        val componentMap = LinkedHashMap<String, String>()
        val packageFallbackMap = LinkedHashMap<String, String>()
        val iconBacks = ArrayList<String>()
        val iconMasks = ArrayList<String>()
        val iconUpons = ArrayList<String>()
        var scaleFactor: String? = null

        fun consumeParser(parser: XmlPullParser) {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "item" -> {
                            val rawComp = parser.getAttributeValue(null, "component")
                            val rawDraw = parser.getAttributeValue(null, "drawable")
                            if (!rawComp.isNullOrBlank() && !rawDraw.isNullOrBlank()) {
                                val normalizedComp = normalizeComponentInfo(rawComp.trim())
                                componentMap[normalizedComp] = rawDraw.trim()
                                val pkg = extractPackageFromComponentInfo(normalizedComp)
                                if (pkg != null) {
                                    packageFallbackMap.putIfAbsent(pkg, rawDraw.trim())
                                }
                            }
                        }
                        "iconback" -> {
                            iconBacks.addAll(extractImgAttributes(parser))
                        }
                        "iconmask" -> {
                            iconMasks.addAll(extractImgAttributes(parser))
                        }
                        "iconupon" -> {
                            iconUpons.addAll(extractImgAttributes(parser))
                        }
                        "scale" -> {
                            val factor = parser.getAttributeValue(null, "factor")
                            if (!factor.isNullOrBlank()) {
                                scaleFactor = factor.trim()
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }

        // 1. Try res/xml/appfilter.xml via XmlResourceParser
        val xmlResId = baseResources.getIdentifier("appfilter", "xml", basePkg)
        if (xmlResId != 0) {
            runCatching {
                baseResources.getXml(xmlResId).use { xmlParser ->
                    consumeParser(xmlParser)
                }
            }
        }

        // 2. If empty, try res/raw/appfilter.xml
        if (componentMap.isEmpty()) {
            val rawResId = baseResources.getIdentifier("appfilter", "raw", basePkg)
            if (rawResId != 0) {
                runCatching {
                    baseResources.openRawResource(rawResId).use { rawStream ->
                        val parser = XmlPullParserFactory.newInstance().newPullParser()
                        parser.setInput(rawStream, "UTF-8")
                        consumeParser(parser)
                    }
                }
            }
        }

        // 3. If still empty, try assets/appfilter.xml
        if (componentMap.isEmpty()) {
            runCatching {
                baseResources.assets.open("appfilter.xml").use { assetStream ->
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(assetStream, "UTF-8")
                    consumeParser(parser)
                }
            }
        }

        return ParsedBasePackFilter(
            componentToDrawable = componentMap,
            packageFallbackToDrawable = packageFallbackMap,
            iconBackDrawables = iconBacks.distinct(),
            iconMaskDrawables = iconMasks.distinct(),
            iconUponDrawables = iconUpons.distinct(),
            scaleFactor = scaleFactor
        )
    }

    private fun extractImgAttributes(parser: XmlPullParser): List<String> {
        val list = ArrayList<String>()
        val count = parser.attributeCount
        for (i in 0 until count) {
            val attrName = parser.getAttributeName(i) ?: ""
            val attrVal = parser.getAttributeValue(i)?.trim() ?: ""
            if (attrName.startsWith("img") && attrVal.isNotEmpty()) {
                list.add(attrVal)
            }
        }
        return list
    }

    /**
     * Normalizes shorthand `ComponentInfo{com.pkg/.MainActivity}` into full
     * `ComponentInfo{com.pkg/com.pkg.MainActivity}` so it matches PackageManager's
     * `InstalledApp.componentInfo` format.
     */
    private fun normalizeComponentInfo(raw: String): String {
        if (!raw.startsWith("ComponentInfo{") || !raw.endsWith("}")) return raw
        val inner = raw.removePrefix("ComponentInfo{").removeSuffix("}")
        val slashIdx = inner.indexOf('/')
        if (slashIdx <= 0) return raw
        val pkg = inner.substring(0, slashIdx)
        val cls = inner.substring(slashIdx + 1)
        val fullCls = if (cls.startsWith(".")) "$pkg$cls" else cls
        return "ComponentInfo{$pkg/$fullCls}"
    }

    private fun extractPackageFromComponentInfo(comp: String): String? {
        if (!comp.startsWith("ComponentInfo{")) return null
        val inner = comp.removePrefix("ComponentInfo{").removeSuffix("}")
        val slashIdx = inner.indexOf('/')
        return if (slashIdx > 0) inner.substring(0, slashIdx) else null
    }

    private fun renderBasePackDrawableToPngBytes(
        baseResources: Resources,
        basePackageName: String,
        drawableName: String,
        targetSize: Int = 256
    ): ByteArray? {
        return runCatching {
            var resId = baseResources.getIdentifier(drawableName, "drawable", basePackageName)
            if (resId == 0) {
                resId = baseResources.getIdentifier(drawableName, "mipmap", basePackageName)
            }
            if (resId == 0) return null

            val drawable = baseResources.getDrawable(resId, null) ?: return null
            val bmp = renderDrawableToBitmap(drawable, targetSize) ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }.getOrNull()
    }

    private fun renderDrawableToBitmap(drawable: Drawable, size: Int): Bitmap? {
        return runCatching {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bmp
        }.getOrNull()
    }

    companion object {
        private const val PREFS_NAME = "iconcraft_base_pack_prefs"
        private const val KEY_BASE_PACK_PKG = "selected_base_pack_package"
    }
}
