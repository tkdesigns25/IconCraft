package com.iconcraft.studio.apk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages a persistent local PKCS#12 (`.p12`) keystore in `context.filesDir`.
 *
 * On first launch of IconCraft, generates an RSA-2048 keypair and self-signed X.509
 * certificate stored in `context.filesDir/iconcraft_keystore.p12`, encrypted with a
 * random per-device password protected by the Android Keystore (`AES/GCM/NoPadding`).
 * Includes seamless in-place migration if a legacy `.p12` already exists on disk.
 */
class LocalKeystoreManager(private val context: Context) {

    data class SigningCredentials(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
        val keystoreFile: File
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val keystoreFile: File
        get() = File(context.filesDir, KEYSTORE_FILENAME)

    @Synchronized
    fun getOrCreateSigningCredentials(): SigningCredentials {
        val activePassword = getOrCreateDeviceKeystorePassword()

        if (keystoreFile.exists() && keystoreFile.length() > 0) {
            // 1. Try loading with the Android Keystore-protected per-device password
            runCatching {
                return loadExistingCredentials(keystoreFile, activePassword)
            }
            // 2. Safe migration path if the .p12 was created prior to the Android Keystore password upgrade
            runCatching {
                val legacyCreds = loadExistingCredentials(keystoreFile, LEGACY_FALLBACK_PASSWORD)
                saveCredentialsToPkcs12(keystoreFile, legacyCreds.privateKey, legacyCreds.certificate, activePassword)
                return legacyCreds
            }
        }
        return generateAndStoreNewCredentials(keystoreFile, activePassword)
    }

    private fun loadExistingCredentials(file: File, password: CharArray): SigningCredentials {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { fis ->
            ks.load(fis, password)
        }
        val privateKey = ks.getKey(KEY_ALIAS, password) as? PrivateKey
            ?: throw IllegalStateException("Missing private key in local .p12 keystore")
        val cert = ks.getCertificate(KEY_ALIAS) as? X509Certificate
            ?: throw IllegalStateException("Missing X.509 certificate in local .p12 keystore")
        return SigningCredentials(
            privateKey = privateKey,
            certificate = cert,
            keystoreFile = file
        )
    }

    private fun generateAndStoreNewCredentials(file: File, password: CharArray): SigningCredentials {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val cert = generateSelfSignedX509Certificate(
            publicKeyEncoded = keyPair.public.encoded,
            privateKey = keyPair.private
        )

        saveCredentialsToPkcs12(file, keyPair.private, cert, password)

        return SigningCredentials(
            privateKey = keyPair.private,
            certificate = cert,
            keystoreFile = file
        )
    }

    private fun saveCredentialsToPkcs12(
        file: File,
        privateKey: PrivateKey,
        cert: X509Certificate,
        password: CharArray
    ) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, password)
        ks.setKeyEntry(
            KEY_ALIAS,
            privateKey,
            password,
            arrayOf(cert)
        )
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            ks.store(fos, password)
        }
    }

    /**
     * Generates a random 256-bit password per device, encrypts it using an AES-256-GCM
     * master key inside `AndroidKeyStore`, and persists the IV + ciphertext in SharedPreferences.
     */
    private fun getOrCreateDeviceKeystorePassword(): CharArray {
        return runCatching {
            val existingCipherB64 = prefs.getString(KEY_ENCRYPTED_P12_PASS, null)
            val existingIvB64 = prefs.getString(KEY_ENCRYPTED_P12_IV, null)
            val secretKey = getOrCreateAndroidKeystoreSecretKey()

            if (!existingCipherB64.isNullOrBlank() && !existingIvB64.isNullOrBlank()) {
                val cipherBytes = Base64.decode(existingCipherB64, Base64.NO_WRAP)
                val ivBytes = Base64.decode(existingIvB64, Base64.NO_WRAP)
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, ivBytes))
                val decrypted = cipher.doFinal(cipherBytes)
                return@runCatching String(decrypted, Charsets.UTF_8).toCharArray()
            }

            // Generate a fresh random 32-byte hex password
            val randomBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val newPasswordStr = Base64.encodeToString(randomBytes, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val ivBytes = cipher.iv
            val cipherBytes = cipher.doFinal(newPasswordStr.toByteArray(Charsets.UTF_8))

            prefs.edit()
                .putString(KEY_ENCRYPTED_P12_PASS, Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
                .putString(KEY_ENCRYPTED_P12_IV, Base64.encodeToString(ivBytes, Base64.NO_WRAP))
                .apply()

            newPasswordStr.toCharArray()
        }.getOrElse {
            LEGACY_FALLBACK_PASSWORD
        }
    }

    private fun getOrCreateAndroidKeystoreSecretKey(): SecretKey {
        val androidKeyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        val existingKey = (androidKeyStore.getEntry(ANDROID_KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            ANDROID_KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Builds a standards-compliant DER-encoded X.509 v3 self-signed certificate
     * signed with `SHA256withRSA` using pure Kotlin ASN.1 DER encoding.
     */
    private fun generateSelfSignedX509Certificate(
        publicKeyEncoded: ByteArray,
        privateKey: PrivateKey
    ): X509Certificate {
        // OID 1.2.840.113549.1.1.11 (sha256WithRSAEncryption) + NULL
        val sha256WithRsaAlgId = derSequence(
            derOid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)),
            derNull()
        )

        // Version: [0] EXPLICIT INTEGER 2 (v3)
        val version = derExplicit(0, derInteger(BigInteger.valueOf(2L)))

        // SerialNumber: positive random 64-bit integer
        val serialBytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        serialBytes[0] = (serialBytes[0].toInt() and 0x7F).toByte()
        if (serialBytes[0].toInt() == 0) serialBytes[0] = 1
        val serialNumber = derInteger(BigInteger(1, serialBytes))

        // Distinguished Name: CN=IconCraft Pack Signer, O=IconCraft, C=US
        val dn = derSequence(
            derSet(
                derSequence(
                    // countryName (2.5.4.6)
                    derOid(byteArrayOf(0x55, 0x04, 0x06)),
                    derPrintableString("US")
                )
            ),
            derSet(
                derSequence(
                    // organizationName (2.5.4.10)
                    derOid(byteArrayOf(0x55, 0x04, 0x0A)),
                    derUtf8String("IconCraft")
                )
            ),
            derSet(
                derSequence(
                    // commonName (2.5.4.3)
                    derOid(byteArrayOf(0x55, 0x04, 0x03)),
                    derUtf8String("IconCraft Pack Signer")
                )
            )
        )

        // Validity: 2024-01-01 to 2049-12-31 (UTCTime)
        val validity = derSequence(
            derUtcTime("240101000000Z"),
            derUtcTime("491231235959Z")
        )

        // TBSCertificate
        val tbsCertificate = derSequence(
            version,
            serialNumber,
            sha256WithRsaAlgId,
            dn,
            validity,
            dn,
            publicKeyEncoded // SubjectPublicKeyInfo is already DER-encoded
        )

        // Sign TBSCertificate with SHA256withRSA
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(tbsCertificate)
        val signatureBytes = signature.sign()

        // Full X.509 Certificate SEQUENCE
        val certDer = derSequence(
            tbsCertificate,
            sha256WithRsaAlgId,
            derBitString(signatureBytes)
        )

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun derSequence(vararg elements: ByteArray): ByteArray =
        derTag(0x30, concat(*elements))

    private fun derSet(vararg elements: ByteArray): ByteArray =
        derTag(0x31, concat(*elements))

    private fun derExplicit(tagNumber: Int, content: ByteArray): ByteArray =
        derTag(0xA0 or (tagNumber and 0x1F), content)

    private fun derInteger(value: BigInteger): ByteArray =
        derTag(0x02, value.toByteArray())

    private fun derBitString(bytes: ByteArray): ByteArray {
        val payload = ByteArray(bytes.size + 1)
        payload[0] = 0x00 // 0 unused bits
        System.arraycopy(bytes, 0, payload, 1, bytes.size)
        return derTag(0x03, payload)
    }

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derOid(encodedOidBody: ByteArray): ByteArray =
        derTag(0x06, encodedOidBody)

    private fun derUtf8String(str: String): ByteArray =
        derTag(0x0C, str.toByteArray(Charsets.UTF_8))

    private fun derPrintableString(str: String): ByteArray =
        derTag(0x13, str.toByteArray(Charsets.US_ASCII))

    private fun derUtcTime(str: String): ByteArray =
        derTag(0x17, str.toByteArray(Charsets.US_ASCII))

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len <= 0xFF -> {
                out.write(0x81)
                out.write(len)
            }
            len <= 0xFFFF -> {
                out.write(0x82)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write((len shr 16) and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (a in arrays) {
            out.write(a)
        }
        return out.toByteArray()
    }

    companion object {
        private const val PREFS_NAME = "iconcraft_keystore_prefs"
        private const val KEY_ENCRYPTED_P12_PASS = "encrypted_p12_pass"
        private const val KEY_ENCRYPTED_P12_IV = "encrypted_p12_iv"
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val ANDROID_KEYSTORE_ALIAS = "iconcraft_p12_master_key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        private const val KEYSTORE_FILENAME = "iconcraft_keystore.p12"
        private const val KEY_ALIAS = "iconcraft_key"
        private val LEGACY_FALLBACK_PASSWORD = "iconcraft_local_pack_key".toCharArray()
    }
}
