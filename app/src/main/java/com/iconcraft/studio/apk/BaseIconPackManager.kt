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
import com.iconcraft.studio.data.QueueStorage
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

data class InstalledIconCraftPackStatus(
    val isInstalled: Boolean = false,
    val customIconCount: Int = 0,
    val basePackPackage: String? = null,
    val customComponents: Set<String> = emptySet()
)

data class FetchedIconCraftPackResult(
    val mergedQueueItems: List<CustomIconQueueItem>,
    val newlyImportedCount: Int,
    val retainedExistingCount: Int,
    val restoredBasePackPackage: String?
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
    val basePackLabel: String? = null,
    val basePackPackage: String? = null,
    val customComponents: List<String> = emptyList()
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
     * Inspects whether `com.iconcraft.pack` is currently installed on the device and
     * counts its active custom icon mappings (reading `assets/iconcraft_meta.json` if present,
     * or falling back to `appfilter.xml` on older packs).
     */
    suspend fun inspectInstalledIconCraftPack(): InstalledIconCraftPackStatus = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packResources = runCatching {
            pm.getPackageInfo(ICONCRAFT_PACK_PKG, 0)
            pm.getResourcesForApplication(ICONCRAFT_PACK_PKG)
        }.getOrNull() ?: return@withContext InstalledIconCraftPackStatus(isInstalled = false)

        val meta = readInstalledPackMetadata(packResources)
        val parsedFilter = parseBasePackAppFilter(packResources, ICONCRAFT_PACK_PKG)

        val activeCustomComponents: Set<String> = if (meta != null && meta.customComponents.isNotEmpty()) {
            val normalizedMeta = meta.customComponents.map { normalizeComponentInfo(it) }.toSet()
            parsedFilter.componentToDrawable.keys.intersect(normalizedMeta)
        } else if (meta != null && !meta.basePackPackage.isNullOrBlank()) {
            // Pack was built with only a Base Pack and 0 custom overrides
            emptySet()
        } else {
            // Fallback for older packs built before iconcraft_meta.json existed
            parsedFilter.componentToDrawable.keys
        }

        InstalledIconCraftPackStatus(
            isInstalled = true,
            customIconCount = activeCustomComponents.size,
            basePackPackage = meta?.basePackPackage,
            customComponents = activeCustomComponents
        )
    }

    /**
     * Fetches and retains active custom icons from the currently installed `com.iconcraft.pack` APK:
     * - Checks if `com.iconcraft.pack` is installed via `PackageManager`.
     * - Loads its `Resources` via `packageManager.getResourcesForApplication("com.iconcraft.pack")`.
     * - Reads `assets/iconcraft_meta.json` if present (falling back gracefully to `appfilter.xml`).
     * - Extracts each custom icon's PNG back into `filesDir/queue_icons/` as a `512x512 ARGB_8888` PNG
     *   and maps it to its installed `InstalledApp` (keeping any already-queued local edits intact).
     * - If `basePackPackage` was saved in metadata and is still installed, restores that Base Pack selection.
     */
    suspend fun fetchInstalledIconCraftPack(
        installedApps: List<InstalledApp>,
        existingQueueItems: List<CustomIconQueueItem>,
        queueStorage: QueueStorage,
        installedIconPacks: List<InstalledIconPack>
    ): FetchedIconCraftPackResult? = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packResources = runCatching {
            pm.getPackageInfo(ICONCRAFT_PACK_PKG, 0)
            pm.getResourcesForApplication(ICONCRAFT_PACK_PKG)
        }.getOrNull() ?: return@withContext null

        val meta = readInstalledPackMetadata(packResources)
        val parsedFilter = parseBasePackAppFilter(packResources, ICONCRAFT_PACK_PKG)

        val targetComponents: Set<String> = if (meta != null && meta.customComponents.isNotEmpty()) {
            meta.customComponents.map { normalizeComponentInfo(it) }.toSet()
        } else if (meta != null && !meta.basePackPackage.isNullOrBlank()) {
            emptySet()
        } else {
            parsedFilter.componentToDrawable.keys
        }

        val byComponent = installedApps.associateBy { it.componentInfo }
        val byPackage = installedApps.groupBy { it.packageName }

        val mergedList = ArrayList<CustomIconQueueItem>(existingQueueItems)
        val existingComponents = existingQueueItems.map { it.app.componentInfo }.toHashSet()
        val usedSlots = existingQueueItems.map { it.slotIndex }.toHashSet()

        var newlyImported = 0

        for ((componentInfo, drawableName) in parsedFilter.componentToDrawable) {
            if (componentInfo !in targetComponents) continue
            if (componentInfo in existingComponents) continue

            val matchedApp = byComponent[componentInfo] ?: run {
                val pkg = extractPackageFromComponentInfo(componentInfo)
                pkg?.let { byPackage[it]?.firstOrNull() }
            } ?: continue

            if (matchedApp.componentInfo in existingComponents) continue

            val bmp512 = loadPackDrawableAs512Bitmap(
                packResources = packResources,
                packPkg = ICONCRAFT_PACK_PKG,
                drawableName = drawableName
            ) ?: continue

            val preferredSlot = parseSlotIndexFromDrawableName(drawableName)
            val assignedSlot = if (preferredSlot != null && preferredSlot in 1..500 && preferredSlot !in usedSlots) {
                preferredSlot
            } else {
                (1..500).firstOrNull { it !in usedSlots } ?: break
            }
            usedSlots.add(assignedSlot)
            existingComponents.add(matchedApp.componentInfo)

            val savedCustomFile = queueStorage.saveCustomIconBitmap(assignedSlot, bmp512)
            val savedSourceFile = queueStorage.saveSourceBitmap(assignedSlot, bmp512)

            mergedList.add(
                CustomIconQueueItem(
                    app = matchedApp,
                    slotIndex = assignedSlot,
                    customPngPath = savedCustomFile.absolutePath,
                    sourcePngPath = savedSourceFile.absolutePath,
                    customPreviewBitmap = bmp512.asImageBitmap(),
                    shapeMaskName = "FULL_BLEED",
                    imageScale = 1.0f,
                    roundInnerCorners = false,
                    backgroundColorArgb = 0,
                    userZoom = 1.0f,
                    userPanX = 0f,
                    userPanY = 0f
                )
            )
            newlyImported++
        }

        var restoredBasePack: String? = null
        val savedBasePkg = meta?.basePackPackage?.takeIf { it.isNotBlank() }
        if (savedBasePkg != null && installedIconPacks.any { it.packageName == savedBasePkg }) {
            setSelectedBasePackPackage(savedBasePkg)
            restoredBasePack = savedBasePkg
        }

        if (newlyImported > 0) {
            queueStorage.saveQueue(mergedList)
        }

        FetchedIconCraftPackResult(
            mergedQueueItems = mergedList,
            newlyImportedCount = newlyImported,
            retainedExistingCount = existingQueueItems.size,
            restoredBasePackPackage = restoredBasePack
        )
    }

    private fun readInstalledPackMetadata(packResources: Resources): IconCraftPackMetadata? {
        return runCatching {
            val jsonText = packResources.assets.open(ApkPatcherAndSigner.META_ASSET_PATH).use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
            ApkPatcherAndSigner.parseMetaJson(jsonText)
        }.getOrNull()
    }

    private fun parseSlotIndexFromDrawableName(drawableName: String): Int? {
        if (!drawableName.startsWith("custom_icon_")) return null
        return drawableName.removePrefix("custom_icon_").toIntOrNull()
    }

    private fun loadPackDrawableAs512Bitmap(
        packResources: Resources,
        packPkg: String,
        drawableName: String
    ): Bitmap? {
        return runCatching {
            val resId = packResources.getIdentifier(drawableName, "drawable", packPkg)
            if (resId == 0) return null
            val drawable = packResources.getDrawable(resId, null) ?: return null
            renderDrawableToBitmap(drawable, 512)
        }.getOrNull()
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
        discoveredPackages.remove(ICONCRAFT_PACK_PKG)

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
        val explicitCustomComponents = userQueueItems.map { it.app.componentInfo }

        if (basePackPackageName.isNullOrBlank()) {
            return@withContext MergedPackPayload(
                items = userQueueItems,
                extras = AppFilterExtras(),
                inMemoryPngBytesByFilename = emptyMap(),
                userCustomizedCount = userQueueItems.size,
                inheritedBaseCount = 0,
                basePackLabel = null,
                basePackPackage = null,
                customComponents = explicitCustomComponents
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
            basePackLabel = null,
            basePackPackage = null,
            customComponents = explicitCustomComponents
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
            basePackLabel = basePackLabel,
            basePackPackage = basePackPackageName,
            customComponents = explicitCustomComponents
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
     * Parses `appfilter.xml` from an icon pack, checking:
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
        const val ICONCRAFT_PACK_PKG = "com.iconcraft.pack"
        private const val PREFS_NAME = "iconcraft_base_pack_prefs"
        private const val KEY_BASE_PACK_PKG = "selected_base_pack_package"
    }
}
