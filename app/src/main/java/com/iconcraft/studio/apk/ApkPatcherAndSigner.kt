package com.iconcraft.studio.apk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.android.apksig.ApkSigner
import com.iconcraft.studio.model.CustomIconQueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class IconCraftPackMetadata(
    val basePackPackage: String?,
    val customComponents: List<String>
)

/**
 * Patches `assets/template_iconpack.apk` with the user's selective custom icons,
 * encodes both plain-text UTF-8 XML (`assets/`, `res/raw/`) and compiled Android
 * Binary XML (`res/xml/`), embeds `assets/iconcraft_meta.json`, increments `versionCode`,
 * aligns uncompressed entries to 4-byte boundaries (required by Android 11+ `targetSdk 35`),
 * and signs the output APK on-device using Google's `ApkSigner` (v1 + v2 + v3) with the
 * persistent local `.p12` keystore.
 */
class ApkPatcherAndSigner(private val context: Context) {

    data class BuildResult(
        val signedApkFile: File,
        val versionCode: Int,
        val customizedCount: Int,
        val inheritedBaseCount: Int = 0,
        val basePackLabel: String? = null,
        val keystorePath: String
    )

    private val keystoreManager = LocalKeystoreManager(context)

    suspend fun buildAndSignIconPack(
        queueItems: List<CustomIconQueueItem>,
        appFilterExtras: AppFilterExtras = AppFilterExtras(),
        inMemoryPngBytesByFilename: Map<String, ByteArray> = emptyMap(),
        inheritedBaseCount: Int = 0,
        basePackLabel: String? = null,
        basePackPackage: String? = null,
        customComponents: List<String>? = null,
        onProgress: (String) -> Unit = {}
    ): Result<BuildResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(queueItems.isNotEmpty() || !appFilterExtras.isEmpty) {
                "Please customize at least one app or select a Base Icon Pack before building."
            }

            onProgress("Preparing local PKCS#12 signing keystore...")
            val credentials = keystoreManager.getOrCreateSigningCredentials()

            onProgress("Encoding appfilter.xml & drawable.xml (UTF-8 + Binary AXML)...")
            val appFilterPlain = AxmlEncoder.encodeAppFilterPlainXml(queueItems, appFilterExtras)
            val drawablePlain = AxmlEncoder.encodeDrawablePlainXml(queueItems)
            val appFilterAxml = AxmlEncoder.encodeAppFilterAxml(queueItems, appFilterExtras)
            val drawableAxml = AxmlEncoder.encodeDrawableAxml(queueItems)

            val effectiveCustomComponents = customComponents
                ?: queueItems.filter { it.customPngPath != null }.map { it.app.componentInfo }
            val metaJsonBytes = encodeMetaJson(
                basePackPackage = basePackPackage?.takeIf { it.isNotBlank() },
                customComponents = effectiveCustomComponents
            )

            // Map drawable slot names ("custom_icon_001.png") to custom or inherited PNG bytes
            val customPngBytesByFilename = HashMap<String, ByteArray>(inMemoryPngBytesByFilename)
            for (item in queueItems) {
                val fileName = "${item.drawableName}.png"
                if (customPngBytesByFilename.containsKey(fileName)) continue

                val pngPath = item.customPngPath
                val bytes = if (pngPath != null && File(pngPath).exists()) {
                    File(pngPath).readBytes()
                } else {
                    // Fallback: encode the app's original icon bitmap if customPngPath wasn't saved yet
                    val bmp = item.app.iconBitmap
                        ?: throw IllegalStateException("Missing custom icon image for ${item.app.appName}")
                    ByteArrayOutputStream().also { out ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }.toByteArray()
                }
                customPngBytesByFilename[fileName] = bytes
            }

            val nextVersionCode = getAndIncrementVersionCode()
            onProgress("Patching template APK (versionCode $nextVersionCode)...")

            val workDir = File(context.filesDir, "generated_apks").apply { mkdirs() }
            val unsignedApkFile = File(workDir, "unsigned_iconcraft_pack.apk")
            val signedApkFile = File(workDir, "IconCraft_Pack.apk")

            if (unsignedApkFile.exists()) unsignedApkFile.delete()
            if (signedApkFile.exists()) signedApkFile.delete()

            val replacementEntries = mapOf(
                "assets/appfilter.xml" to appFilterPlain,
                "res/raw/appfilter.xml" to appFilterPlain,
                "res/xml/appfilter.xml" to appFilterAxml,
                "assets/drawable.xml" to drawablePlain,
                "res/raw/drawable.xml" to drawablePlain,
                "res/xml/drawable.xml" to drawableAxml,
                META_ASSET_PATH to metaJsonBytes
            )

            val writtenNames = HashSet<String>()

            try {
                context.assets.open("template_iconpack.apk").use { assetStream ->
                    ZipInputStream(assetStream).use { zis ->
                        val countingOut = CountingOutputStream(FileOutputStream(unsignedApkFile))
                        ZipOutputStream(countingOut).use { zos ->
                            var entry: ZipEntry? = zis.nextEntry
                            while (entry != null) {
                                val name = entry.name

                                // Skip existing META-INF signature files from template build
                                if (isSignatureFile(name)) {
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                    continue
                                }

                                val originalBytes = zis.readBytes()
                                val payloadBytes: ByteArray = when {
                                    name == "AndroidManifest.xml" -> {
                                        AxmlEncoder.patchManifestVersionCode(originalBytes, nextVersionCode)
                                    }
                                    replacementEntries.containsKey(name) -> {
                                        replacementEntries.getValue(name)
                                    }
                                    name.startsWith("res/drawable-nodpi") && name.endsWith(".png") -> {
                                        val fileName = name.substringAfterLast('/')
                                        customPngBytesByFilename[fileName] ?: originalBytes
                                    }
                                    else -> originalBytes
                                }

                                // Keep resources.arsc and PNGs uncompressed (STORED) and 4-byte aligned
                                val shouldStoreUncompressed =
                                    name == "resources.arsc" ||
                                        name.endsWith(".png") ||
                                        entry.method == ZipEntry.STORED

                                writeZipEntry(
                                    zos = zos,
                                    countingOut = countingOut,
                                    name = name,
                                    data = payloadBytes,
                                    storeUncompressed = shouldStoreUncompressed
                                )
                                writtenNames.add(name)

                                zis.closeEntry()
                                entry = zis.nextEntry
                            }

                            // Ensure all mapping XML files and iconcraft_meta.json exist even if absent from template
                            for ((targetName, xmlBytes) in replacementEntries) {
                                if (writtenNames.add(targetName)) {
                                    writeZipEntry(
                                        zos = zos,
                                        countingOut = countingOut,
                                        name = targetName,
                                        data = xmlBytes,
                                        storeUncompressed = true
                                    )
                                }
                            }
                        }
                    }
                }

                onProgress("Signing APK with ApkSigner (v1 + v2 + v3)...")
                val signerConfig = ApkSigner.SignerConfig.Builder(
                    "ICONCRAFT",
                    credentials.privateKey,
                    listOf(credentials.certificate)
                ).build()

                val apkSigner = ApkSigner.Builder(listOf(signerConfig))
                    .setInputApk(unsignedApkFile)
                    .setOutputApk(signedApkFile)
                    .setMinSdkVersion(26)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .build()

                apkSigner.sign()
            } finally {
                if (unsignedApkFile.exists()) {
                    unsignedApkFile.delete()
                }
            }

            onProgress("Icon Pack APK signed and ready!")
            BuildResult(
                signedApkFile = signedApkFile,
                versionCode = nextVersionCode,
                customizedCount = (queueItems.size - inheritedBaseCount).coerceAtLeast(0),
                inheritedBaseCount = inheritedBaseCount,
                basePackLabel = basePackLabel,
                keystorePath = credentials.keystoreFile.absolutePath
            )
        }
    }

    /**
     * Launches Android's package installer for the signed Icon Pack APK via `FileProvider`.
     * Returns `true` if the package installer was launched directly, or `false` if the user
     * was directed to grant "Install unknown apps" permission first.
     */
    fun promptInstallApk(signedApkFile: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return false
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            signedApkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }

    private fun writeZipEntry(
        zos: ZipOutputStream,
        countingOut: CountingOutputStream,
        name: String,
        data: ByteArray,
        storeUncompressed: Boolean
    ) {
        val outEntry = ZipEntry(name)
        outEntry.time = 0L // Deterministic timestamp

        if (storeUncompressed) {
            outEntry.method = ZipEntry.STORED
            outEntry.size = data.size.toLong()
            outEntry.compressedSize = data.size.toLong()
            val crc = CRC32()
            crc.update(data)
            outEntry.crc = crc.value

            // 4-byte align uncompressed entry payload (mandatory for Android 11+ resources.arsc)
            val nameBytesLen = name.toByteArray(Charsets.UTF_8).size
            val headerEndWithoutExtra = countingOut.bytesWritten + 30L + nameBytesLen
            val alignment = 4L
            val padding = ((alignment - (headerEndWithoutExtra % alignment)) % alignment).toInt()
            if (padding > 0) {
                outEntry.extra = ByteArray(padding)
            }
        } else {
            outEntry.method = ZipEntry.DEFLATED
        }

        zos.putNextEntry(outEntry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun isSignatureFile(entryName: String): Boolean {
        if (!entryName.startsWith("META-INF/")) return false
        val upper = entryName.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC") ||
            upper.startsWith("META-INF/SIG-")
    }

    private fun getAndIncrementVersionCode(): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(KEY_VERSION_CODE, 1)
        val next = current + 1
        prefs.edit().putInt(KEY_VERSION_CODE, next).apply()
        return next
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len
        }
    }

    companion object {
        const val META_ASSET_PATH = "assets/iconcraft_meta.json"
        private const val PREFS_NAME = "iconcraft_build_prefs"
        private const val KEY_VERSION_CODE = "pack_version_code"

        fun encodeMetaJson(
            basePackPackage: String?,
            customComponents: List<String>
        ): ByteArray {
            val sb = StringBuilder()
            sb.append("{\n")
            sb.append("  \"basePackPackage\": ")
            if (basePackPackage.isNullOrBlank()) {
                sb.append("null")
            } else {
                sb.append("\"").append(escapeJsonString(basePackPackage)).append("\"")
            }
            sb.append(",\n")
            sb.append("  \"customComponents\": [")
            if (customComponents.isNotEmpty()) {
                sb.append("\n")
                customComponents.forEachIndexed { idx, comp ->
                    sb.append("    \"").append(escapeJsonString(comp)).append("\"")
                    if (idx < customComponents.lastIndex) sb.append(",")
                    sb.append("\n")
                }
                sb.append("  ")
            }
            sb.append("]\n")
            sb.append("}\n")
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        fun parseMetaJson(jsonText: String): IconCraftPackMetadata? {
            if (jsonText.isBlank()) return null
            val basePackRegex = Regex("\"basePackPackage\"\\s*:\\s*(null|\"((?:\\\\.|[^\"\\\\])*)\")")
            val baseMatch = basePackRegex.find(jsonText)
            val basePackPkg = baseMatch?.groups?.get(2)?.value?.let { unescapeJsonString(it) }
                ?.takeIf { it.isNotBlank() }

            val arrayRegex = Regex("\"customComponents\"\\s*:\\s*\\[(.*?)]", setOf(RegexOption.DOT_MATCHES_ALL))
            val arrayMatch = arrayRegex.find(jsonText)
            val components = ArrayList<String>()
            if (arrayMatch != null) {
                val inner = arrayMatch.groupValues[1]
                val itemRegex = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
                for (m in itemRegex.findAll(inner)) {
                    val unescaped = unescapeJsonString(m.groupValues[1]).trim()
                    if (unescaped.isNotEmpty()) {
                        components.add(unescaped)
                    }
                }
            }
            return IconCraftPackMetadata(
                basePackPackage = basePackPkg,
                customComponents = components
            )
        }

        private fun escapeJsonString(input: String): String {
            return buildString(input.length + 8) {
                for (ch in input) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
            }
        }

        private fun unescapeJsonString(input: String): String {
            val sb = StringBuilder(input.length)
            var i = 0
            while (i < input.length) {
                val ch = input[i]
                if (ch == '\\' && i + 1 < input.length) {
                    when (val next = input[i + 1]) {
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        else -> sb.append(next)
                    }
                    i += 2
                } else {
                    sb.append(ch)
                    i += 1
                }
            }
            return sb.toString()
        }
    }
}
