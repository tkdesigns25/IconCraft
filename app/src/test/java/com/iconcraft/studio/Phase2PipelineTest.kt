package com.iconcraft.studio

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.iconcraft.studio.apk.ApkPatcherAndSigner
import com.iconcraft.studio.apk.AppFilterExtras
import com.iconcraft.studio.apk.AxmlEncoder
import com.iconcraft.studio.model.CustomIconQueueItem
import com.iconcraft.studio.model.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class Phase2PipelineTest {

    @Test
    fun testSelectiveLetterboxdXmlAndAxmlAndApkSign() {
        val letterboxdApp = InstalledApp(
            appName = "Letterboxd",
            packageName = "com.letterboxd.buzz",
            activityName = "com.letterboxd.buzz.ui.MainActivity",
            iconBitmap = null,
            iconImageBitmap = null
        )
        val queue = listOf(
            CustomIconQueueItem(
                app = letterboxdApp,
                slotIndex = 1
            )
        )

        // 1. Verify Selective Mode appfilter.xml rules
        val appFilterXml = String(AxmlEncoder.encodeAppFilterPlainXml(queue), Charsets.UTF_8)
        assertTrue(
            appFilterXml.contains(
                "<item component=\"ComponentInfo{com.letterboxd.buzz/com.letterboxd.buzz.ui.MainActivity}\" drawable=\"custom_icon_001\" />"
            )
        )
        assertFalse(appFilterXml.contains("iconback"))
        assertFalse(appFilterXml.contains("iconmask"))
        assertFalse(appFilterXml.contains("iconupon"))
        assertFalse(appFilterXml.contains("scale"))

        // 2. Verify drawable.xml cleanliness
        val drawableXml = String(AxmlEncoder.encodeDrawablePlainXml(queue), Charsets.UTF_8)
        assertTrue(drawableXml.contains("<item drawable=\"custom_icon_001\" />"))
        assertFalse(drawableXml.contains("custom_icon_002"))

        // 3. Verify Binary AXML headers and size consistency
        val appFilterAxml = AxmlEncoder.encodeAppFilterAxml(queue)
        val afBuf = ByteBuffer.wrap(appFilterAxml).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0003, afBuf.getShort(0).toInt() and 0xFFFF)
        assertEquals(appFilterAxml.size, afBuf.getInt(4))

        val drawableAxml = AxmlEncoder.encodeDrawableAxml(queue)
        val drBuf = ByteBuffer.wrap(drawableAxml).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0003, drBuf.getShort(0).toInt() and 0xFFFF)
        assertEquals(drawableAxml.size, drBuf.getInt(4))

        // 4. Verify template_iconpack.apk patching + versionCode increment + ApkSigner v1/v2/v3 verification
        val templateApk = File("src/main/assets/template_iconpack.apk")
        assertTrue("template_iconpack.apk must exist", templateApk.exists())

        val tempDir = File(System.getProperty("java.io.tmpdir"), "iconcraft_phase2_test").apply { mkdirs() }
        val unsignedApk = File(tempDir, "unsigned_test.apk")
        val signedApk = File(tempDir, "signed_test.apk")

        ZipInputStream(templateApk.inputStream()).use { zis ->
            val countingOut = object : FilterOutputStream(FileOutputStream(unsignedApk)) {
                var written = 0L
                override fun write(b: Int) {
                    out.write(b)
                    written += 1
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    written += len
                }
            }
            ZipOutputStream(countingOut).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.startsWith("META-INF/")) {
                        val orig = zis.readBytes()
                        val data = when (name) {
                            "AndroidManifest.xml" -> AxmlEncoder.patchManifestVersionCode(orig, 7)
                            "assets/appfilter.xml", "res/raw/appfilter.xml" -> appFilterXml.toByteArray()
                            "res/xml/appfilter.xml" -> appFilterAxml
                            "assets/drawable.xml", "res/raw/drawable.xml" -> drawableXml.toByteArray()
                            "res/xml/drawable.xml" -> drawableAxml
                            else -> orig
                        }
                        val outEntry = ZipEntry(name).apply { time = 0L }
                        val store = name == "resources.arsc" || name.endsWith(".png") || entry.method == ZipEntry.STORED
                        if (store) {
                            outEntry.method = ZipEntry.STORED
                            outEntry.size = data.size.toLong()
                            outEntry.compressedSize = data.size.toLong()
                            val crc = CRC32().apply { update(data) }
                            outEntry.crc = crc.value
                            val nameLen = name.toByteArray(Charsets.UTF_8).size
                            val pad = ((4L - ((countingOut.written + 30L + nameLen) % 4L)) % 4L).toInt()
                            if (pad > 0) outEntry.extra = ByteArray(pad)
                        } else {
                            outEntry.method = ZipEntry.DEFLATED
                        }
                        zos.putNextEntry(outEntry)
                        zos.write(data)
                        zos.closeEntry()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        // Generate RSA-2048 keypair and sign APK with ApkSigner v1 + v2 + v3
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
        val kp = kpg.generateKeyPair()
        val cert = buildTestSelfSignedCert(kp.public.encoded, kp.private)

        // Verify PKCS12 storage roundtrip
        val p12File = File(tempDir, "test_keystore.p12")
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, "testpass".toCharArray())
        ks.setKeyEntry("alias", kp.private, "testpass".toCharArray(), arrayOf(cert))
        p12File.outputStream().use { ks.store(it, "testpass".toCharArray()) }
        assertTrue(p12File.exists() && p12File.length() > 0)

        val signerConfig = ApkSigner.SignerConfig.Builder("ICONCRAFT", kp.private, listOf(cert)).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()

        val verifyResult = ApkVerifier.Builder(signedApk)
            .setMinCheckedPlatformVersion(21)
            .build()
            .verify()

        assertTrue("Signed APK must pass ApkVerifier", verifyResult.isVerified)
        assertTrue("v1 signature must verify", verifyResult.isVerifiedUsingV1Scheme)
        assertTrue("v2 signature must verify", verifyResult.isVerifiedUsingV2Scheme)
        assertTrue("v3 signature must verify", verifyResult.isVerifiedUsingV3Scheme)

        // Verify patched XML contents and 500-slot capacity inside signed APK
        ZipFile(signedApk).use { zf ->
            val patchedFilter = zf.getInputStream(zf.getEntry("assets/appfilter.xml")).readBytes().decodeToString()
            assertTrue(patchedFilter.contains("com.letterboxd.buzz/com.letterboxd.buzz.ui.MainActivity"))
            assertTrue(
                "Template APK must contain slot 500 (custom_icon_500.png)",
                zf.getEntry("res/drawable-nodpi-v4/custom_icon_500.png") != null ||
                    zf.getEntry("res/drawable-nodpi/custom_icon_500.png") != null
            )
        }

        // Verify Base Pack inheritance extras (<iconback>, <iconmask>, <iconupon>, <scale>)
        val extras = AppFilterExtras(
            iconBackSlotNames = listOf("custom_icon_002"),
            iconMaskSlotNames = listOf("custom_icon_003"),
            iconUponSlotNames = listOf("custom_icon_004"),
            scaleFactor = "0.85"
        )
        val mergedXml = String(AxmlEncoder.encodeAppFilterPlainXml(queue, extras), Charsets.UTF_8)
        assertTrue(mergedXml.contains("<iconback img1=\"custom_icon_002\" />"))
        assertTrue(mergedXml.contains("<iconmask img1=\"custom_icon_003\" />"))
        assertTrue(mergedXml.contains("<iconupon img1=\"custom_icon_004\" />"))
        assertTrue(mergedXml.contains("<scale factor=\"0.85\" />"))

        val mergedAxml = AxmlEncoder.encodeAppFilterAxml(queue, extras)
        val mergedAxmlBuf = ByteBuffer.wrap(mergedAxml).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0003, mergedAxmlBuf.getShort(0).toInt() and 0xFFFF)
        assertEquals(mergedAxml.size, mergedAxmlBuf.getInt(4))

        // Verify assets/iconcraft_meta.json encoding & parsing roundtrip
        val metaBytes = ApkPatcherAndSigner.encodeMetaJson(
            basePackPackage = "com.whicons.iconpack",
            customComponents = listOf("ComponentInfo{com.letterboxd.buzz/com.letterboxd.buzz.ui.MainActivity}")
        )
        val parsedMeta = ApkPatcherAndSigner.parseMetaJson(metaBytes.decodeToString())
        assertEquals("com.whicons.iconpack", parsedMeta?.basePackPackage)
        assertEquals(
            listOf("ComponentInfo{com.letterboxd.buzz/com.letterboxd.buzz.ui.MainActivity}"),
            parsedMeta?.customComponents
        )

        val metaNoBase = ApkPatcherAndSigner.encodeMetaJson(
            basePackPackage = null,
            customComponents = listOf("ComponentInfo{com.spotify.music/com.spotify.music.MainActivity}")
        )
        val parsedNoBase = ApkPatcherAndSigner.parseMetaJson(metaNoBase.decodeToString())
        assertEquals(null, parsedNoBase?.basePackPackage)
        assertEquals(
            listOf("ComponentInfo{com.spotify.music/com.spotify.music.MainActivity}"),
            parsedNoBase?.customComponents
        )
    }

    private fun buildTestSelfSignedCert(pubEncoded: ByteArray, privKey: java.security.PrivateKey): X509Certificate {
        fun tag(t: Int, c: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(t)
            if (c.size < 128) out.write(c.size) else {
                out.write(0x82)
                out.write((c.size shr 8) and 0xFF)
                out.write(c.size and 0xFF)
            }
            out.write(c)
            return out.toByteArray()
        }
        fun seq(vararg a: ByteArray) = tag(0x30, a.reduce { acc, bytes -> acc + bytes })
        val alg = seq(tag(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)), byteArrayOf(0x05, 0x00))
        val dn = seq(tag(0x31, seq(tag(0x06, byteArrayOf(0x55, 0x04, 0x03)), tag(0x0C, "IconCraft".toByteArray()))))
        val valid = seq(tag(0x17, "240101000000Z".toByteArray()), tag(0x17, "491231235959Z".toByteArray()))
        val tbs = seq(tag(0xA0, tag(0x02, byteArrayOf(2))), tag(0x02, BigInteger.valueOf(1001L).toByteArray()), alg, dn, valid, dn, pubEncoded)
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(privKey); update(tbs) }.sign()
        val certBytes = seq(tbs, alg, tag(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
    }
}
