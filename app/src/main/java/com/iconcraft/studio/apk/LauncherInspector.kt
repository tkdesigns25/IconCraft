package com.iconcraft.studio.apk

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * On-device diagnostic helper that inspects:
 * 1. The user's active default Home Launcher (e.g. `com.nothing.launcher`) and its `<queries>` manifest declarations.
 * 2. Any installed Nothing / system / third-party icon packs (e.g. `com.nothing.icon`) and their `<intent-filter>` & `<meta-data>` declarations.
 * 3. Whether `com.iconcraft.pack` is currently installed, visible to intent queries, and whether its `res/xml/appfilter.xml` parses cleanly via `Resources.getXml()`.
 */
object LauncherInspector {

    suspend fun generateDiagnosticReport(context: Context): String = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val sb = StringBuilder()

        // 1. Default Home Launcher
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homeResolve = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val homePkg = homeResolve?.activityInfo?.packageName ?: "unknown"
        sb.appendLine("=== Default Launcher ===")
        sb.appendLine("Package: $homePkg")
        sb.appendLine("Activity: ${homeResolve?.activityInfo?.name ?: "unknown"}")

        if (homePkg != "unknown") {
            sb.appendLine("\n=== Launcher <queries> ($homePkg) ===")
            sb.appendLine(dumpManifestQueriesAndFilters(pm, homePkg, includeQueries = true, includeActivityFilters = false))
        }

        // 2. Check Nothing Icon Pack or other installed icon packs
        val candidatePacks = LinkedHashSet<String>()
        listOf(
            "com.nothing.icon",
            "com.nothing.icon.free",
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.gau.go.launcherex.theme"
        ).forEach { token ->
            if (token.startsWith("com.nothing.icon")) {
                runCatching {
                    pm.getPackageInfo(token, 0)
                    candidatePacks.add(token)
                }
            } else {
                runCatching {
                    pm.queryIntentActivities(Intent(token), 0).forEach { ri ->
                        ri.activityInfo?.packageName?.let { candidatePacks.add(it) }
                    }
                }
            }
        }

        sb.appendLine("\n=== Discovered Icon Packs (${candidatePacks.size}) ===")
        for (pkg in candidatePacks) {
            sb.appendLine("--- Package: $pkg ---")
            sb.appendLine(dumpManifestQueriesAndFilters(pm, pkg, includeQueries = false, includeActivityFilters = true))
            if (pkg.startsWith("com.nothing.icon")) {
                runCatching {
                    val res = pm.getResourcesForApplication(pkg)
                    val xmlId = res.getIdentifier("appfilter", "xml", pkg)
                    val rawId = res.getIdentifier("appfilter", "raw", pkg)
                    sb.appendLine("  res/xml/appfilter=0x${xmlId.toString(16)}, res/raw/appfilter=0x${rawId.toString(16)}")
                    if (xmlId != 0) {
                        var count = 0
                        res.getXml(xmlId).use { parser ->
                            var ev = parser.eventType
                            while (ev != XmlPullParser.END_DOCUMENT) {
                                if (ev == XmlPullParser.START_TAG && parser.name == "item") count++
                                ev = parser.next()
                            }
                        }
                        sb.appendLine("  appfilter <item> count: $count")
                    }
                }.onFailure { err ->
                    sb.appendLine("  appfilter inspect error: ${err.message}")
                }
            }
        }

        // 3. Verify installed com.iconcraft.pack
        sb.appendLine("\n=== IconCraft Pack Status (com.iconcraft.pack) ===")
        val iconCraftInstalled = runCatching { pm.getPackageInfo("com.iconcraft.pack", 0) }.getOrNull()
        if (iconCraftInstalled == null) {
            sb.appendLine("Installed: FALSE (Build & install the pack first)")
        } else {
            sb.appendLine("Installed: TRUE (versionCode=${iconCraftInstalled.longVersionCode})")
            runCatching {
                val res = pm.getResourcesForApplication("com.iconcraft.pack")
                val xmlId = res.getIdentifier("appfilter", "xml", "com.iconcraft.pack")
                sb.appendLine("res/xml/appfilter ID: 0x${xmlId.toString(16)}")
                if (xmlId != 0) {
                    res.getXml(xmlId).use { parser ->
                        var itemCount = 0
                        val samples = ArrayList<String>()
                        val probeMatches = ArrayList<String>()
                        var event = parser.eventType
                        while (event != XmlPullParser.END_DOCUMENT) {
                            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                                itemCount++
                                val comp = parser.getAttributeValue(null, "component") ?: ""
                                val draw = parser.getAttributeValue(null, "drawable") ?: ""
                                if (samples.size < 3) samples.add("$comp -> $draw")
                                if (comp.contains("com.android.chrome") || comp.contains("com.brave.browser")) {
                                    probeMatches.add("$comp -> $draw")
                                }
                            }
                            event = parser.next()
                        }
                        sb.appendLine("Resources.getXml(appfilter) parsed OK: $itemCount item(s)")
                        samples.forEach { sb.appendLine("  • $it") }
                        sb.appendLine("Browser probes (Chrome/Brave): ${probeMatches.size}")
                        probeMatches.forEach { sb.appendLine("  • $it") }
                    }
                }
            }.onFailure { err ->
                sb.appendLine("Resources.getXml(appfilter) ERROR: ${err.message}")
            }
        }

        sb.toString()
    }

    private fun dumpManifestQueriesAndFilters(
        pm: PackageManager,
        packageName: String,
        includeQueries: Boolean,
        includeActivityFilters: Boolean
    ): String {
        val out = StringBuilder()
        runCatching {
            val res = pm.getResourcesForApplication(packageName)
            res.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
                var inQueries = false
                var inActivity = false
                var currentComponent = ""
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "queries" -> inQueries = true
                                "activity", "activity-alias", "service", "receiver", "provider" -> {
                                    inActivity = true
                                    currentComponent = "${parser.name}:${getAttr(parser, "name")}"
                                }
                                "action", "category", "data", "package" -> {
                                    val nameVal = getAttr(parser, "name") ?: getAttr(parser, "scheme") ?: ""
                                    if (includeQueries && inQueries) {
                                        out.appendLine("  [query] <${parser.name} name=\"$nameVal\" />")
                                    } else if (includeActivityFilters && inActivity) {
                                        out.appendLine("  [$currentComponent] <${parser.name} name=\"$nameVal\" />")
                                    }
                                }
                                "meta-data" -> {
                                    if (includeActivityFilters) {
                                        val mName = getAttr(parser, "name")
                                        val mVal = getAttr(parser, "value") ?: getAttr(parser, "resource")
                                        out.appendLine("  [meta-data] name=\"$mName\" val=\"$mVal\"")
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (parser.name) {
                                "queries" -> inQueries = false
                                "activity", "activity-alias", "service", "receiver", "provider" -> inActivity = false
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        }.onFailure { err ->
            out.appendLine("  (Could not read manifest for $packageName: ${err.message})")
        }
        return out.toString().ifBlank { "  (none)" }
    }

    private fun getAttr(parser: XmlPullParser, attrName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == attrName) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }
}
