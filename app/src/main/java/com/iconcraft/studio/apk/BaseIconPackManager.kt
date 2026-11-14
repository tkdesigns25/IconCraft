package com.iconcraft.studio.apk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.iconcraft.studio.data.QueueStorage
import com.iconcraft.studio.lab.ProceduralThemeEngine
import com.iconcraft.studio.model.CustomIconQueueItem
import com.iconcraft.studio.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class InstalledIconPack(
    val packageName: String,
    val packLabel: String,
    val iconImageBitmap: ImageBitmap? = null,
    val subtitle: String? = null
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
     * Queries PackageManager for installed icon packs using:
     * 1. Standard & OEM launcher theme intents (`org.adw.launcher.THEMES`, `com.novalauncher.THEME`, etc.)
     * 2. Direct inspection of Nothing Icon Pack (`com.nothing.icon`, `com.nothing.icon.free`, etc.)
     *    and OEM icon packs that do not declare third-party launcher activities.
     * 3. Scanning installed packages for `res/xml/appfilter.xml` or `res/raw/appfilter.xml`.
     * 4. Guaranteed Nothing UI Monochrome option on Nothing OS devices.
     */
    suspend fun loadInstalledIconPacks(): List<InstalledIconPack> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val themeTokens = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "org.adw.launcher.icons.ACTION_PICK_ICON",
            "ch.deletescape.lawnchair.ICONPACK",
            "com.gau.go.launcherex.theme",
            "com.SmartLauncher.THEME",
            "ginlemon.smartlauncher.THEMES",
            "com.nothing.launcher.THEME",
            "com.nothing.icon.ACTION_ICON_PACK"
        )

        val homeLauncherPackages = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())

        val discoveredPackages = LinkedHashSet<String>()

        // 1. Explicitly check known Nothing & OEM icon pack package names first
        val knownDirectPackPackages = listOf(
            NOTHING_ICON_PACK_PKG,
            "com.nothing.icon.free",
            "com.nothing.iconpack"
        )
        for (knownPkg in knownDirectPackPackages) {
            runCatching {
                pm.getApplicationInfo(knownPkg, 0)
                discoveredPackages.add(knownPkg)
            }
        }

        // 2. Query standard & OEM launcher intent actions and categories
        for (token in themeTokens) {
            runCatching {
                val byAction = pm.queryIntentActivities(Intent(token), PackageManager.GET_META_DATA)
                for (ri in byAction) {
                    discoveredPackages.add(ri.activityInfo.packageName)
                }
            }
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

        // 3. Scan installed applications for Nothing/OEM icon packs or packages bundling appfilter.xml
        runCatching {
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in allApps) {
                val pkg = appInfo.packageName ?: continue
                if (pkg == context.packageName || pkg == ICONCRAFT_PACK_PKG) continue
                if (pkg in discoveredPackages || pkg in homeLauncherPackages) continue

                if (pkg.startsWith("com.nothing.icon") ||
                    pkg.startsWith("com.oneplus.iconpack") ||
                    pkg.startsWith("com.oplus.iconpack")
                ) {
                    discoveredPackages.add(pkg)
                    continue
                }

                val isUserOrUpdatedSystemApp =
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val lowerPkg = pkg.lowercase()
                val looksLikeIconPackPkg =
                    lowerPkg.contains("icon") || lowerPkg.contains("theme") || lowerPkg.contains("pack")

                if (isUserOrUpdatedSystemApp || looksLikeIconPackPkg) {
                    val hasAppFilter = runCatching {
                        val res = pm.getResourcesForApplication(appInfo)
                        res.getIdentifier("appfilter", "xml", pkg) != 0 ||
                            res.getIdentifier("appfilter", "raw", pkg) != 0
                    }.getOrDefault(false)
                    if (hasAppFilter) {
                        discoveredPackages.add(pkg)
                    }
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
                val rawLabel = pm.getApplicationLabel(appInfo).toString().ifBlank { pkg }
                val isNothing = isNothingIconPack(pkg)
                val displayLabel = if (isNothing) {
                    "Nothing Icons (App)"
                } else {
                    rawLabel
                }
                val iconDrawable = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                val bmp = if (iconDrawable != null) {
                    renderDrawableToBitmap(iconDrawable, 96)
                } else if (isNothing) {
                    createNothingPackBadgeBitmap(96)
                } else {
                    null
                }
                packs.add(
                    InstalledIconPack(
                        packageName = pkg,
                        packLabel = displayLabel,
                        iconImageBitmap = bmp?.asImageBitmap(),
                        subtitle = if (isNothing) {
                            "Inherits Nothing Icons app + adds dark circle & auto-monochrome • $pkg"
                        } else {
                            pkg
                        }
                    )
                )
            }
        }

        // Always expose IconCraft's built-in Nothing OS Monochrome synthesizer at the top:
        // produces uniform #17181A dark matte circles + crisp #F1F3F5 white glyphs matching Nothing OS's built-in "Nothing" pack.
        val badgeBmp = createNothingPackBadgeBitmap(96)
        packs.add(
            InstalledIconPack(
                packageName = NOTHING_BUILTIN_PKG,
                packLabel = "Nothing Style Monochrome (Nothing OS Look)",
                iconImageBitmap = badgeBmp.asImageBitmap(),
                subtitle = "Matches Nothing OS built-in dark circle + crisp white glyph for all other apps"
            )
        )

        packs.sortedWith(
            compareBy<InstalledIconPack> {
                when {
                    it.packageName == NOTHING_BUILTIN_PKG -> 0
                    isNothingIconPack(it.packageName) -> 1
                    else -> 2
                }
            }.thenBy { it.packLabel.lowercase() }
        )
    }

    /**
     * Merges the user's IconCraft Custom Queue (highest priority, 512x512 PNG) with the selected
     * Base Pack (256x256 ARGB_8888 PNG for currently installed launchable apps not overridden by the user,
     * plus `<iconback>`, `<iconmask>`, `<iconupon>`, and `<scale>`).
     *
     * When the selected Base Pack is the Nothing Icon Pack (`com.nothing.icon`), every non-customized
     * installed app automatically receives either its dedicated drawable from `com.nothing.icon` OR
     * a synthesized Nothing UI Monochrome icon (using Android 13+ `AdaptiveIconDrawable.monochrome`
     * or high-contrast foreground glyph extraction on Nothing's `#1C1D1F` dark charcoal backdrop).
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
        val isNothingPack = isNothingIconPack(basePackPackageName)
        val baseResources = runCatching {
            pm.getResourcesForApplication(basePackPackageName)
        }.getOrNull()

        if (baseResources == null && !isNothingPack) {
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

        val basePackLabel = if (basePackPackageName == NOTHING_BUILTIN_PKG) {
            "Nothing Style Monochrome"
        } else {
            runCatching {
                val appInfo = pm.getApplicationInfo(basePackPackageName, 0)
                val raw = pm.getApplicationLabel(appInfo).toString()
                if (isNothingPack && (raw.isBlank() || raw.equals("Nothing", ignoreCase = true))) {
                    "Nothing Icons"
                } else {
                    raw
                }
            }.getOrDefault(if (isNothingPack) "Nothing Icons" else basePackPackageName)
        }

        onProgress("Reading Base Pack ($basePackLabel)...")
        val parsed = if (baseResources != null) {
            parseBasePackAppFilter(baseResources, basePackPackageName)
        } else {
            ParsedBasePackFilter(
                componentToDrawable = emptyMap(),
                packageFallbackToDrawable = emptyMap(),
                iconBackDrawables = emptyList(),
                iconMaskDrawables = emptyList(),
                iconUponDrawables = emptyList(),
                scaleFactor = null
            )
        }

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
            val res = baseResources ?: return null
            drawableNameToSlotName[baseDrawableName]?.let { return it }
            val slot = allocateNextSlot() ?: return null
            val pngBytes = renderBasePackDrawableToPngBytes(
                baseResources = res,
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
        //    (Skip for Nothing packs: Nothing icons already carry pre-masked #17181A dark circles
        //    and extra launcher masking shrinks or clips custom queue icons.)
        val extras = if (isNothingPack) {
            AppFilterExtras()
        } else {
            val iconBackSlots = parsed.iconBackDrawables.mapNotNull { importBaseDrawableToSlot(it) }
            val iconMaskSlots = parsed.iconMaskDrawables.mapNotNull { importBaseDrawableToSlot(it) }
            val iconUponSlots = parsed.iconUponDrawables.mapNotNull { importBaseDrawableToSlot(it) }
            AppFilterExtras(
                iconBackSlotNames = iconBackSlots,
                iconMaskSlotNames = iconMaskSlots,
                iconUponSlotNames = iconUponSlots,
                scaleFactor = parsed.scaleFactor
            )
        }

        val systemMaskBitmap256 by lazy { createSystemIconMaskBitmap(256) }

        // 3. Match ONLY launchable apps currently installed on the user's device that aren't overridden
        var inheritedCount = 0
        for ((idx, installedApp) in installedApps.withIndex()) {
            if (installedApp.componentInfo in overriddenComponents) continue
            if (usedSlots.size >= maxSlots) break

            val baseDrawableName = parsed.componentToDrawable[installedApp.componentInfo]
                ?: parsed.packageFallbackToDrawable[installedApp.packageName]
                ?: if (isNothingPack && baseResources != null) {
                    findCandidateDrawableInPack(baseResources, basePackPackageName, installedApp)
                } else {
                    null
                }

            if (baseDrawableName == null && !isNothingPack) {
                continue
            }

            onProgress("Applying $basePackLabel style to ${installedApp.appName} (${idx + 1}/${installedApps.size})...")

            val slot = allocateNextSlot() ?: break
            val slotName = "custom_icon_%03d".format(slot)

            var pngBytes: ByteArray? = null

            if (baseDrawableName != null && baseResources != null) {
                val existingSlotForDrawable = drawableNameToSlotName[baseDrawableName]
                pngBytes = if (existingSlotForDrawable != null) {
                    inMemoryBytes["$existingSlotForDrawable.png"]
                } else {
                    renderBasePackDrawableToPngBytes(
                        baseResources = baseResources,
                        basePackageName = basePackPackageName,
                        drawableName = baseDrawableName,
                        nothingSurfaceMask = if (isNothingPack) systemMaskBitmap256 else null,
                        targetSize = 256
                    )
                }
                if (pngBytes != null) {
                    drawableNameToSlotName.putIfAbsent(baseDrawableName, slotName)
                }
            }

            // Fallback for Nothing Icon Pack: synthesize the Nothing UI lowkey monochrome icon
            // for any installed app that doesn't have a static entry in com.nothing.icon's appfilter.xml
            if (pngBytes == null && isNothingPack) {
                pngBytes = renderNothingMonochromeIconToPngBytes(
                    pm = pm,
                    installedApp = installedApp,
                    systemMaskBitmap = systemMaskBitmap256,
                    targetSize = 256
                )
            }

            if (pngBytes == null) {
                usedSlots.remove(slot)
                continue
            }

            inMemoryBytes["$slotName.png"] = pngBytes

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
     * 1. `res/xml/appfilter.xml` (and `app_filter`, `icon_pack`) via `XmlResourceParser`
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
                            val rawPkgAttr = parser.getAttributeValue(null, "package")
                            val rawDraw = parser.getAttributeValue(null, "drawable")
                            if (!rawDraw.isNullOrBlank()) {
                                val drawTrimmed = rawDraw.trim()
                                if (!rawComp.isNullOrBlank()) {
                                    val normalizedComp = normalizeComponentInfo(rawComp.trim())
                                    componentMap[normalizedComp] = drawTrimmed
                                    val pkg = extractPackageFromComponentInfo(normalizedComp)
                                    if (pkg != null) {
                                        packageFallbackMap.putIfAbsent(pkg, drawTrimmed)
                                    } else if (!rawComp.contains('/')) {
                                        packageFallbackMap.putIfAbsent(rawComp.trim(), drawTrimmed)
                                    }
                                }
                                if (!rawPkgAttr.isNullOrBlank()) {
                                    packageFallbackMap.putIfAbsent(rawPkgAttr.trim(), drawTrimmed)
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

        // 1. Try res/xml/appfilter.xml (and common OEM variants) via XmlResourceParser
        for (xmlName in listOf("appfilter", "app_filter", "icon_pack")) {
            if (componentMap.isNotEmpty()) break
            val xmlResId = baseResources.getIdentifier(xmlName, "xml", basePkg)
            if (xmlResId != 0) {
                runCatching {
                    baseResources.getXml(xmlResId).use { xmlParser ->
                        consumeParser(xmlParser)
                    }
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
     * Normalizes shorthand `ComponentInfo{com.pkg/.MainActivity}` or `com.pkg/.MainActivity` into full
     * `ComponentInfo{com.pkg/com.pkg.MainActivity}` so it matches PackageManager's
     * `InstalledApp.componentInfo` format.
     */
    private fun normalizeComponentInfo(raw: String): String {
        val inner = if (raw.startsWith("ComponentInfo{") && raw.endsWith("}")) {
            raw.removePrefix("ComponentInfo{").removeSuffix("}")
        } else if (raw.contains('/')) {
            raw
        } else {
            return raw
        }
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

    private fun findCandidateDrawableInPack(
        baseResources: Resources,
        basePackageName: String,
        app: InstalledApp
    ): String? {
        val pkgUnderscore = app.packageName.replace('.', '_').lowercase()
        val candidates = listOf(
            pkgUnderscore,
            "ic_$pkgUnderscore",
            app.packageName.lowercase()
        )
        for (candidate in candidates) {
            val dId = baseResources.getIdentifier(candidate, "drawable", basePackageName)
            if (dId != 0) return candidate
            val mId = baseResources.getIdentifier(candidate, "mipmap", basePackageName)
            if (mId != 0) return candidate
        }
        return null
    }

    private fun renderBasePackDrawableToPngBytes(
        baseResources: Resources,
        basePackageName: String,
        drawableName: String,
        nothingSurfaceMask: Bitmap? = null,
        targetSize: Int = 256
    ): ByteArray? {
        return runCatching {
            var resId = baseResources.getIdentifier(drawableName, "drawable", basePackageName)
            if (resId == 0) {
                resId = baseResources.getIdentifier(drawableName, "mipmap", basePackageName)
            }
            if (resId == 0) return null

            val drawable = baseResources.getDrawable(resId, null) ?: return null
            var bmp = renderDrawableToBitmap(drawable, targetSize) ?: return null

            // Drawables from Play Store Nothing Icons are often transparent glyphs without a circle.
            // Composite them on Nothing's #17181A dark matte circular surface so they never float naked.
            if (nothingSurfaceMask != null) {
                bmp = if (hasTransparentPerimeter(bmp)) {
                    val isDarkGlyph = computeOpaqueMeanLuminance(bmp) < 120f
                    compositeOnNothingDarkSurface(
                        foregroundBmp = bmp,
                        systemMaskBitmap = nothingSurfaceMask,
                        targetSize = targetSize,
                        tintToNothingWhite = isDarkGlyph,
                        glyphScale = 0.90f
                    )
                } else {
                    applySystemMask(bmp, nothingSurfaceMask, targetSize)
                }
            }

            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }.getOrNull()
    }

    /**
     * Synthesizes a Nothing OS Monochrome (lowkey Nothing UI style) icon for an installed app:
     * 1. On Android 13+ (API 33+), uses the app's native `AdaptiveIconDrawable.monochrome` layer
     *    drawn directly onto a transparent canvas, tinted to `#F1F3F5` over `#17181A`.
     * 2. If `AdaptiveIconDrawable.foreground` is available, detects & removes any inner container shell
     *    (e.g. white circle behind MobiKwik's M) and lifts foreground logos cleanly to `#F1F3F5`.
     * 3. For full-bleed / legacy icons, performs dominant container-ring color-distance segmentation
     *    to turn any colored or white container background into `#17181A` while lifting logos to `#F1F3F5`.
     * 4. Guaranteed fallback using `installedApp.iconBitmap` (or initial glyph) so no app is ever left unthemed.
     */
    private fun renderNothingMonochromeIconToPngBytes(
        pm: PackageManager,
        installedApp: InstalledApp,
        systemMaskBitmap: Bitmap,
        targetSize: Int = 256
    ): ByteArray? {
        val activityDrawable = runCatching {
            pm.getActivityIcon(ComponentName(installedApp.packageName, installedApp.activityName))
        }.getOrNull()
        val appDrawable = runCatching {
            pm.getApplicationIcon(installedApp.packageName)
        }.getOrNull()

        var resultBmp: Bitmap? = null

        // Tier 1: Native Android 13+ AdaptiveIconDrawable.monochrome layer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultBmp = runCatching {
                val adaptiveWithMono = listOfNotNull(activityDrawable, appDrawable)
                    .filterIsInstance<AdaptiveIconDrawable>()
                    .firstOrNull { it.monochrome != null } ?: return@runCatching null

                adaptiveWithMono.setBounds(0, 0, targetSize, targetSize)
                val rawMono = adaptiveWithMono.monochrome ?: return@runCatching null
                val mono = runCatching {
                    rawMono.constantState?.newDrawable()?.mutate() ?: rawMono.mutate()
                }.getOrDefault(rawMono)

                val monoLayerBmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                val monoCanvas = Canvas(monoLayerBmp)
                val inset = -(targetSize * 0.25f).toInt()
                mono.colorFilter = null
                mono.setBounds(inset, inset, targetSize - inset, targetSize - inset)
                mono.draw(monoCanvas)

                var coverage = computeOpaqueCoverage(monoLayerBmp)
                if (coverage < 0.005f) {
                    runCatching {
                        mono.setTint(Color.WHITE)
                        mono.draw(monoCanvas)
                    }
                    coverage = computeOpaqueCoverage(monoLayerBmp)
                }

                when {
                    coverage in 0.005f..0.68f -> compositeOnNothingDarkSurface(
                        foregroundBmp = monoLayerBmp,
                        systemMaskBitmap = systemMaskBitmap,
                        targetSize = targetSize,
                        tintToNothingWhite = true,
                        glyphScale = 0.90f
                    )
                    coverage > 0.68f && hasTransparentCenterCutout(monoLayerBmp) -> invertStencilCutoutOnNothingSurface(
                        src = monoLayerBmp,
                        systemMaskBitmap = systemMaskBitmap,
                        targetSize = targetSize
                    )
                    coverage > 0.68f -> renderFullBitmapAsNothingMonochrome(
                        src = monoLayerBmp,
                        systemMaskBitmap = systemMaskBitmap,
                        targetSize = targetSize
                    )
                    else -> null
                }
            }.getOrNull()
        }

        // Tier 2: AdaptiveIconDrawable.foreground monochrome conversion (with inner shell stripping)
        if (resultBmp == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            resultBmp = runCatching {
                val adaptiveWithFg = listOfNotNull(activityDrawable, appDrawable)
                    .filterIsInstance<AdaptiveIconDrawable>()
                    .firstOrNull { it.foreground != null } ?: return@runCatching null

                adaptiveWithFg.setBounds(0, 0, targetSize, targetSize)
                val rawFg = adaptiveWithFg.foreground ?: return@runCatching null
                val fg = runCatching {
                    rawFg.constantState?.newDrawable()?.mutate() ?: rawFg.mutate()
                }.getOrDefault(rawFg)

                val fgLayerBmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                val fgCanvas = Canvas(fgLayerBmp)
                val inset = -(targetSize * 0.25f).toInt()
                fg.colorFilter = null
                fg.setBounds(inset, inset, targetSize - inset, targetSize - inset)
                fg.draw(fgCanvas)

                val coverage = computeOpaqueCoverage(fgLayerBmp)
                if (coverage in 0.005f..0.72f && hasTransparentPerimeter(fgLayerBmp)) {
                    val monoFg = convertIsolatedForegroundToNothingMonochrome(fgLayerBmp, coverage)
                    compositeOnNothingDarkSurface(
                        foregroundBmp = monoFg,
                        systemMaskBitmap = systemMaskBitmap,
                        targetSize = targetSize,
                        tintToNothingWhite = false,
                        glyphScale = 0.90f
                    )
                } else {
                    null
                }
            }.getOrNull()
        }

        // Tier 3: Dominant container-ring color-distance segmentation on rendered full icon
        if (resultBmp == null) {
            resultBmp = runCatching {
                val rawDrawable = activityDrawable ?: appDrawable
                val fullBmp = rawDrawable?.let { renderDrawableToBitmap(it, targetSize) } ?: return@runCatching null
                renderFullBitmapAsNothingMonochrome(
                    src = fullBmp,
                    systemMaskBitmap = systemMaskBitmap,
                    targetSize = targetSize
                )
            }.getOrNull()
        }

        // Tier 4: Guaranteed fallback from already-loaded AppRepository iconBitmap (never leave an app unthemed)
        if (resultBmp == null) {
            resultBmp = runCatching {
                val fallbackBmp = installedApp.iconBitmap
                    ?.let { Bitmap.createScaledBitmap(it, targetSize, targetSize, true) }
                if (fallbackBmp != null) {
                    renderFullBitmapAsNothingMonochrome(
                        src = fallbackBmp,
                        systemMaskBitmap = systemMaskBitmap,
                        targetSize = targetSize
                    )
                } else {
                    renderFallbackMonogramIcon(installedApp.appName, systemMaskBitmap, targetSize)
                }
            }.getOrNull() ?: renderFallbackMonogramIcon(installedApp.appName, systemMaskBitmap, targetSize)
        }

        val out = ByteArrayOutputStream()
        resultBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun hasTransparentPerimeter(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 8) return false
        var transparentCount = 0
        var totalSamples = 0
        val margin = (w * 0.08f).toInt().coerceAtLeast(2)

        for (x in 0 until w step 4) {
            totalSamples += 2
            if (Color.alpha(bmp.getPixel(x, margin)) < 40) transparentCount++
            if (Color.alpha(bmp.getPixel(x, h - 1 - margin)) < 40) transparentCount++
        }
        for (y in 0 until h step 4) {
            totalSamples += 2
            if (Color.alpha(bmp.getPixel(margin, y)) < 40) transparentCount++
            if (Color.alpha(bmp.getPixel(w - 1 - margin, y)) < 40) transparentCount++
        }
        return transparentCount.toFloat() / totalSamples.coerceAtLeast(1) > 0.55f
    }

    /**
     * Converts an isolated foreground layer (with transparent perimeter) into Nothing monochrome shades:
     * 1. If the foreground has an opaque container shell (e.g., MobiKwik's white circle + dark M,
     *    BookMyShow's red box + white logo), detects the uniform shell color along the foreground's
     *    outer boundary and dissolves the shell into transparent `#17181A` while boosting the inner
     *    emblem/glyph to crisp `#F1F3F5`.
     * 2. If it's a compact single-tone glyph (`coverage < 0.30f`), tints all opaque pixels cleanly
     *    to `#F1F3F5`.
     * 3. For compact multi-tone glyphs without a container shell (e.g., Chrome wheel, Brave lion, Kite),
     *    keeps all arms bright (`[0.62f..1.0f]`) against `#17181A` so nothing turns muddy dark grey.
     */
    private fun convertIsolatedForegroundToNothingMonochrome(src: Bitmap, coverage: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        var opaqueCount = 0
        var lumSum = 0.0
        var lumSqSum = 0.0
        var minLum = 255f
        var maxLum = 0f
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val px = pixels[rowOffset + x]
                val a = Color.alpha(px)
                if (a > 60) {
                    opaqueCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    val lum = (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px))
                    lumSum += lum
                    lumSqSum += (lum * lum).toDouble()
                    if (lum < minLum) minLum = lum
                    if (lum > maxLum) maxLum = lum
                }
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (opaqueCount == 0) return out

        val meanLum = lumSum / opaqueCount
        val variance = (lumSqSum / opaqueCount) - (meanLum * meanLum)
        val stdDev = sqrt(variance.coerceAtLeast(0.0))

        val bgR = Color.red(NOTHING_BG_COLOR)
        val bgG = Color.green(NOTHING_BG_COLOR)
        val bgB = Color.blue(NOTHING_BG_COLOR)
        val fgR = Color.red(NOTHING_FG_COLOR)
        val fgG = Color.green(NOTHING_FG_COLOR)
        val fgB = Color.blue(NOTHING_FG_COLOR)

        // Check if foreground actually contains an opaque inner badge/container shell (solid OR gradient)
        val bw = (maxX - minX + 1).coerceAtLeast(1)
        val bh = (maxY - minY + 1).coerceAtLeast(1)
        if (coverage >= 0.22f && bw >= 24 && bh >= 24) {
            val fcx = (minX + maxX) / 2f
            val fcy = (minY + maxY) / 2f
            val anchors = collectRingAnchors(
                pixels = pixels,
                w = w,
                h = h,
                cx = fcx,
                cy = fcy,
                rxPrimary = bw * 0.38f,
                ryPrimary = bh * 0.38f,
                rxSecondary = bw * 0.32f,
                rySecondary = bh * 0.32f
            )

            if (anchors.size >= 12) {
                val anchorColors = anchors.map { it.argb }
                val medoid = findMedoidRgb(anchorColors, tolerance = 42f)
                val adjDist = meanAdjacentAnchorDistance(anchors)
                val isSmoothOrSolidShell = medoid.matchFraction >= 0.50f || adjDist <= 48f

                if (isSmoothOrSolidShell) {
                    val useIdw = medoid.matchFraction < 0.72f
                    var coreOpaque = 0
                    var coreContrast = 0
                    val coreRx = (bw * 0.24f).toInt().coerceAtLeast(4)
                    val coreRy = (bh * 0.24f).toInt().coerceAtLeast(4)
                    val cyi = fcy.toInt()
                    val cxi = fcx.toInt()
                    for (y in (cyi - coreRy)..(cyi + coreRy) step 2) {
                        if (y !in 0 until h) continue
                        for (x in (cxi - coreRx)..(cxi + coreRx) step 2) {
                            if (x !in 0 until w) continue
                            val px = pixels[y * w + x]
                            if (Color.alpha(px) > 120) {
                                coreOpaque++
                                val dist = if (useIdw) {
                                    distanceToInterpolatedAnchors(px, x.toFloat(), y.toFloat(), anchors)
                                } else {
                                    rgbDistance(px, medoid.r, medoid.g, medoid.b)
                                }
                                if (dist > 34f) coreContrast++
                            }
                        }
                    }
                    val coreContrastRatio = if (coreOpaque > 0) coreContrast.toFloat() / coreOpaque else 0f

                    if (coreContrastRatio >= 0.045f) {
                        for (y in 0 until h) {
                            val rowOffset = y * w
                            for (x in 0 until w) {
                                val idx = rowOffset + x
                                val px = pixels[idx]
                                val a = Color.alpha(px)
                                if (a < 16) {
                                    pixels[idx] = 0
                                    continue
                                }
                                val dist = if (useIdw) {
                                    distanceToInterpolatedAnchors(px, x.toFloat(), y.toFloat(), anchors)
                                } else {
                                    rgbDistance(px, medoid.r, medoid.g, medoid.b)
                                }
                                val t = ((dist - 18f) / 48f).coerceIn(0f, 1f).pow(0.72f)
                                val r = (bgR + (fgR - bgR) * t).toInt().coerceIn(0, 255)
                                val g = (bgG + (fgG - bgG) * t).toInt().coerceIn(0, 255)
                                val b = (bgB + (fgB - bgB) * t).toInt().coerceIn(0, 255)
                                pixels[idx] = Color.argb(a, r, g, b)
                            }
                        }
                        out.setPixels(pixels, 0, w, 0, 0, w, h)
                        return out
                    }
                }
            }
        }

        // Compact single-tone glyph silhouette: tint cleanly to Nothing crisp white
        if (coverage < 0.36f && stdDev < 18.0 && (maxLum - minLum) < 52f) {
            for (i in pixels.indices) {
                val a = Color.alpha(pixels[i])
                pixels[i] = if (a < 16) 0 else Color.argb(a, fgR, fgG, fgB)
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return out
        }

        // Compact multi-color foreground emblem/logo (e.g. Chrome wheel, Brave lion, Kite):
        // Keep all colored logo strokes bright [0.74f..1.0f] so no colored segment turns muddy dark grey.
        val baseFloor = if (coverage < 0.36f) 0.74f else 0.52f
        val lumSpan = (maxLum - minLum).coerceAtLeast(32f)

        for (i in pixels.indices) {
            val px = pixels[i]
            val a = Color.alpha(px)
            if (a < 16) {
                pixels[i] = 0
                continue
            }
            val lum = 0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)
            val norm = ((lum - minLum) / lumSpan).coerceIn(0f, 1f)
            val t = (baseFloor + (1f - baseFloor) * norm).coerceIn(0f, 1f)
            val r = (bgR + (fgR - bgR) * t).toInt().coerceIn(0, 255)
            val g = (bgG + (fgG - bgG) * t).toInt().coerceIn(0, 255)
            val b = (bgB + (fgB - bgB) * t).toInt().coerceIn(0, 255)
            pixels[i] = Color.argb(a, r, g, b)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Converts a full icon bitmap to Nothing OS matte dark circle + crisp white glyph style:
     * - Finds the opaque bounding box and samples 16 angular container anchors inside it.
     * - Handles BOTH solid container colors AND smooth gradients (e.g. IconCraft, JioHotstar, Brave)
     *   via inverse-distance weighted ring-background subtraction -> container becomes `#1E1F22` and
     *   contrasting central logo/symbol becomes `#F3F4F6`.
     * - Scales centered monochrome content nicely (`0.86f`) inside `#1E1F22` so legacy/square icons
     *   never kiss the circle edge, then masks cleanly with `systemMaskBitmap`.
     */
    private fun renderFullBitmapAsNothingMonochrome(
        src: Bitmap,
        systemMaskBitmap: Bitmap,
        targetSize: Int
    ): Bitmap {
        val scaled = if (src.width != targetSize || src.height != targetSize) {
            Bitmap.createScaledBitmap(src, targetSize, targetSize, true)
        } else {
            src
        }

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                if (Color.alpha(pixels[rowOffset + x]) > 60) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        val bw = if (maxX > minX) (maxX - minX + 1).toFloat() else w.toFloat()
        val bh = if (maxY > minY) (maxY - minY + 1).toFloat() else h.toFloat()
        val cx = if (maxX > minX) (minX + maxX) / 2f else w / 2f
        val cy = if (maxY > minY) (minY + maxY) / 2f else h / 2f

        val anchors = collectRingAnchors(
            pixels = pixels,
            w = w,
            h = h,
            cx = cx,
            cy = cy,
            rxPrimary = bw * 0.37f,
            ryPrimary = bh * 0.37f,
            rxSecondary = bw * 0.31f,
            rySecondary = bh * 0.31f
        )

        val anchorColors = anchors.map { it.argb }
        val medoid = if (anchorColors.isNotEmpty()) {
            findMedoidRgb(anchorColors, tolerance = 42f)
        } else {
            MedoidResult(0f, 0f, 0f, 0f)
        }
        val adjDist = meanAdjacentAnchorDistance(anchors)
        val useIdw = medoid.matchFraction < 0.72f

        var centerOpaque = 0
        var centerContrastCount = 0
        var centerLumSum = 0f
        val cBoxX = (bw * 0.24f).toInt().coerceAtLeast(4)
        val cBoxY = (bh * 0.24f).toInt().coerceAtLeast(4)
        val cyi = cy.toInt()
        val cxi = cx.toInt()
        for (y in (cyi - cBoxY)..(cyi + cBoxY) step 2) {
            if (y !in 0 until h) continue
            for (x in (cxi - cBoxX)..(cxi + cBoxX) step 2) {
                if (x !in 0 until w) continue
                val px = pixels[y * w + x]
                if (Color.alpha(px) > 100) {
                    centerOpaque++
                    centerLumSum += (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px))
                    val dist = if (useIdw && anchors.isNotEmpty()) {
                        distanceToInterpolatedAnchors(px, x.toFloat(), y.toFloat(), anchors)
                    } else {
                        rgbDistance(px, medoid.r, medoid.g, medoid.b)
                    }
                    if (dist > 34f) centerContrastCount++
                }
            }
        }
        val centerContrastRatio = if (centerOpaque > 0) centerContrastCount.toFloat() / centerOpaque else 0f
        val centerMeanLum = if (centerOpaque > 0) centerLumSum / centerOpaque else 128f
        val ringMeanLum = if (anchorColors.isNotEmpty()) {
            anchorColors.map { 0.299f * Color.red(it) + 0.587f * Color.green(it) + 0.114f * Color.blue(it) }.average().toFloat()
        } else {
            0f
        }

        val useContainerDistanceSegmentation =
            anchors.size >= 10 && (medoid.matchFraction >= 0.45f || adjDist <= 52f) && centerContrastRatio >= 0.035f
        val invertLuminance = !useContainerDistanceSegmentation &&
            ringMeanLum > 160f && centerMeanLum < (ringMeanLum - 14f)

        val bgR = Color.red(NOTHING_BG_COLOR)
        val bgG = Color.green(NOTHING_BG_COLOR)
        val bgB = Color.blue(NOTHING_BG_COLOR)
        val fgR = Color.red(NOTHING_FG_COLOR)
        val fgG = Color.green(NOTHING_FG_COLOR)
        val fgB = Color.blue(NOTHING_FG_COLOR)

        val monoBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val idx = rowOffset + x
                val px = pixels[idx]
                val a = Color.alpha(px)
                if (a < 16) {
                    pixels[idx] = NOTHING_BG_COLOR
                    continue
                }
                val t = if (useContainerDistanceSegmentation) {
                    val dist = if (useIdw) {
                        distanceToInterpolatedAnchors(px, x.toFloat(), y.toFloat(), anchors)
                    } else {
                        rgbDistance(px, medoid.r, medoid.g, medoid.b)
                    }
                    ((dist - 18f) / 48f).coerceIn(0f, 1f).pow(0.72f)
                } else {
                    val rawLum = (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)) / 255f
                    val baseT = if (invertLuminance) (1f - rawLum) else rawLum
                    baseT.coerceIn(0f, 1f).pow(1.25f)
                }
                val r = (bgR + (fgR - bgR) * t).toInt().coerceIn(0, 255)
                val g = (bgG + (fgG - bgG) * t).toInt().coerceIn(0, 255)
                val b = (bgB + (fgB - bgB) * t).toInt().coerceIn(0, 255)
                pixels[idx] = Color.argb(255, r, g, b)
            }
        }
        monoBmp.setPixels(pixels, 0, w, 0, 0, w, h)

        return applySystemMaskWithPadding(monoBmp, systemMaskBitmap, targetSize, innerScale = 0.88f)
    }

    private data class RingAnchor(
        val x: Float,
        val y: Float,
        val argb: Int
    )

    private fun collectRingAnchors(
        pixels: IntArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        rxPrimary: Float,
        ryPrimary: Float,
        rxSecondary: Float,
        rySecondary: Float
    ): List<RingAnchor> {
        val out = ArrayList<RingAnchor>(16)
        for (i in 0 until 16) {
            val angle = i * (Math.PI / 8.0)
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            val x1 = (cx + rxPrimary * cosA).toInt().coerceIn(0, w - 1)
            val y1 = (cy + ryPrimary * sinA).toInt().coerceIn(0, h - 1)
            val px1 = pixels[y1 * w + x1]
            if (Color.alpha(px1) > 110) {
                out.add(RingAnchor(x1.toFloat(), y1.toFloat(), px1))
            } else {
                val x2 = (cx + rxSecondary * cosA).toInt().coerceIn(0, w - 1)
                val y2 = (cy + rySecondary * sinA).toInt().coerceIn(0, h - 1)
                val px2 = pixels[y2 * w + x2]
                if (Color.alpha(px2) > 110) {
                    out.add(RingAnchor(x2.toFloat(), y2.toFloat(), px2))
                }
            }
        }
        return out
    }

    private fun meanAdjacentAnchorDistance(anchors: List<RingAnchor>): Float {
        if (anchors.size < 2) return 0f
        var sum = 0f
        for (i in anchors.indices) {
            val a = anchors[i]
            val b = anchors[(i + 1) % anchors.size]
            sum += rgbDistance(a.argb, Color.red(b.argb).toFloat(), Color.green(b.argb).toFloat(), Color.blue(b.argb).toFloat())
        }
        return sum / anchors.size
    }

    private fun distanceToInterpolatedAnchors(px: Int, x: Float, y: Float, anchors: List<RingAnchor>): Float {
        var wSum = 0f
        var rSum = 0f
        var gSum = 0f
        var bSum = 0f
        for (anchor in anchors) {
            val dx = x - anchor.x
            val dy = y - anchor.y
            val distSq = dx * dx + dy * dy + 64f
            val weight = 1f / distSq
            wSum += weight
            rSum += Color.red(anchor.argb) * weight
            gSum += Color.green(anchor.argb) * weight
            bSum += Color.blue(anchor.argb) * weight
        }
        val denom = wSum.coerceAtLeast(1e-6f)
        return rgbDistance(px, rSum / denom, gSum / denom, bSum / denom)
    }

    private fun hasTransparentCenterCutout(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        val cx = w / 2
        val cy = h / 2
        val box = (w * 0.22f).toInt().coerceAtLeast(4)
        var transparentCenter = 0
        var totalCenter = 0
        for (y in (cy - box)..(cy + box) step 2) {
            for (x in (cx - box)..(cx + box) step 2) {
                totalCenter++
                if (Color.alpha(bmp.getPixel(x, y)) < 40) transparentCenter++
            }
        }
        return totalCenter > 0 && (transparentCenter.toFloat() / totalCenter) > 0.12f
    }

    private fun invertStencilCutoutOnNothingSurface(
        src: Bitmap,
        systemMaskBitmap: Bitmap,
        targetSize: Int
    ): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val a = Color.alpha(pixels[i])
            pixels[i] = if (a < 40) NOTHING_FG_COLOR else NOTHING_BG_COLOR
        }
        val inverted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        inverted.setPixels(pixels, 0, w, 0, 0, w, h)
        return applySystemMaskWithPadding(inverted, systemMaskBitmap, targetSize, innerScale = 0.88f)
    }

    private fun applySystemMaskWithPadding(
        src: Bitmap,
        systemMaskBitmap: Bitmap,
        targetSize: Int,
        innerScale: Float
    ): Bitmap {
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(NOTHING_BG_COLOR)
        val box = targetSize * innerScale
        val offset = (targetSize - box) / 2f
        canvas.drawBitmap(
            src,
            null,
            RectF(offset, offset, offset + box, offset + box),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(systemMaskBitmap, 0f, 0f, maskPaint)
        return out
    }

    private data class MedoidResult(
        val r: Float,
        val g: Float,
        val b: Float,
        val matchFraction: Float
    )

    private fun findMedoidRgb(samples: List<Int>, tolerance: Float): MedoidResult {
        if (samples.isEmpty()) return MedoidResult(0f, 0f, 0f, 0f)
        var bestIdx = 0
        var bestDistSum = Float.MAX_VALUE
        for (i in samples.indices) {
            val p1 = samples[i]
            val r1 = Color.red(p1).toFloat()
            val g1 = Color.green(p1).toFloat()
            val b1 = Color.blue(p1).toFloat()
            var sum = 0f
            for (j in samples.indices) {
                sum += rgbDistance(samples[j], r1, g1, b1)
            }
            if (sum < bestDistSum) {
                bestDistSum = sum
                bestIdx = i
            }
        }
        val seed = samples[bestIdx]
        val seedR = Color.red(seed).toFloat()
        val seedG = Color.green(seed).toFloat()
        val seedB = Color.blue(seed).toFloat()

        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        var matchCount = 0
        for (px in samples) {
            if (rgbDistance(px, seedR, seedG, seedB) <= tolerance) {
                sumR += Color.red(px)
                sumG += Color.green(px)
                sumB += Color.blue(px)
                matchCount++
            }
        }
        val denom = matchCount.coerceAtLeast(1)
        return MedoidResult(
            r = sumR / denom,
            g = sumG / denom,
            b = sumB / denom,
            matchFraction = matchCount.toFloat() / samples.size
        )
    }

    private fun rgbDistance(px: Int, r2: Float, g2: Float, b2: Float): Float {
        val dr = Color.red(px) - r2
        val dg = Color.green(px) - g2
        val db = Color.blue(px) - b2
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun computeOpaqueMeanLuminance(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var lumSum = 0.0
        var count = 0
        for (px in pixels) {
            if (Color.alpha(px) > 80) {
                lumSum += (0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px))
                count++
            }
        }
        return if (count > 0) (lumSum / count).toFloat() else 255f
    }

    private fun applySystemMask(src: Bitmap, systemMaskBitmap: Bitmap, targetSize: Int): Bitmap {
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, null, RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(systemMaskBitmap, 0f, 0f, maskPaint)
        return out
    }

    private fun renderFallbackMonogramIcon(
        appName: String,
        systemMaskBitmap: Bitmap,
        targetSize: Int
    ): Bitmap {
        val raw = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(raw)
        canvas.drawColor(NOTHING_BG_COLOR)
        val letter = appName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NOTHING_FG_COLOR
            textSize = targetSize * 0.42f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val baseline = (targetSize / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(letter, targetSize / 2f, baseline, textPaint)
        return applySystemMask(raw, systemMaskBitmap, targetSize)
    }

    private fun compositeOnNothingDarkSurface(
        foregroundBmp: Bitmap,
        systemMaskBitmap: Bitmap,
        targetSize: Int,
        tintToNothingWhite: Boolean,
        glyphScale: Float
    ): Bitmap {
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // 1. Fill Nothing dark charcoal surface (#1C1D1F)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NOTHING_BG_COLOR
        }
        canvas.drawRect(0f, 0f, targetSize.toFloat(), targetSize.toFloat(), bgPaint)

        // 2. Draw foreground glyph centered
        val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            if (tintToNothingWhite) {
                colorFilter = PorterDuffColorFilter(NOTHING_FG_COLOR, PorterDuff.Mode.SRC_IN)
            }
        }
        val boxSize = targetSize * glyphScale
        val offset = (targetSize - boxSize) / 2f
        val destRect = RectF(offset, offset, offset + boxSize, offset + boxSize)
        canvas.drawBitmap(foregroundBmp, null, destRect, fgPaint)

        // 3. Apply device's native AdaptiveIconDrawable mask (circle on Nothing OS)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(systemMaskBitmap, 0f, 0f, maskPaint)
        return out
    }

    private fun createSystemIconMaskBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.WHITE), null)
            adaptive.setBounds(0, 0, size, size)
            adaptive.draw(canvas)
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        }
        return bmp
    }

    private fun computeOpaqueCoverage(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var opaque = 0
        for (px in pixels) {
            if (Color.alpha(px) > 40) opaque++
        }
        return opaque.toFloat() / (w * h).coerceAtLeast(1)
    }

    private fun createNothingPackBadgeBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NOTHING_BG_COLOR
        }
        canvas.drawCircle(cx, cy, size * 0.46f, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.03f
            color = 0xFF34363A.toInt()
        }
        canvas.drawCircle(cx, cy, size * 0.45f, borderPaint)

        // Draw Nothing-style dot-matrix glyph in off-white
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NOTHING_FG_COLOR
        }
        val dotRadius = size * 0.042f
        val spacing = size * 0.12f
        val coords = listOf(
            -1 to -1, -1 to 0, -1 to 1,
            0 to -0.3f, 0 to 0.3f,
            1 to -1, 1 to 0, 1 to 1
        )
        for ((gx, gy) in coords) {
            canvas.drawCircle(
                cx + gx.toFloat() * spacing,
                cy + gy.toFloat() * spacing,
                dotRadius,
                dotPaint
            )
        }
        return bmp
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
        const val NOTHING_ICON_PACK_PKG = "com.nothing.icon"
        const val NOTHING_BUILTIN_PKG = "builtin.nothing.monochrome"
        private const val NOTHING_BG_COLOR = 0xFF1E1F22.toInt()
        private const val NOTHING_FG_COLOR = 0xFFF3F4F6.toInt()
        private const val PREFS_NAME = "iconcraft_base_pack_prefs"
        private const val KEY_BASE_PACK_PKG = "selected_base_pack_package"

        fun isNothingIconPack(packageName: String?): Boolean {
            if (packageName.isNullOrBlank()) return false
            return packageName == NOTHING_BUILTIN_PKG ||
                packageName == NOTHING_ICON_PACK_PKG ||
                packageName.startsWith("com.nothing.icon")
        }
    }
}
