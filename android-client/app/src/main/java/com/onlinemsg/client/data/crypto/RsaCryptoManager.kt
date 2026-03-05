package com.onlinemsg.client.data.crypto

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.security.spec.MGF1ParameterSpec

class RsaCryptoManager(
    context: Context
) {
    data class Identity(
        val publicKeyBase64: String,
        val privateKey: PrivateKey
    )

    private val secureRandom = SecureRandom()
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateIdentity(): Identity {
        val cachedPrivate = prefs.getString(KEY_PRIVATE_PKCS8_B64, null)
        val cachedPublic = prefs.getString(KEY_PUBLIC_SPKI_B64, null)
        if (!cachedPrivate.isNullOrBlank() && !cachedPublic.isNullOrBlank()) {
            runCatching {
                val privateKey = parsePrivateKey(cachedPrivate)
                return Identity(
                    publicKeyBase64 = cachedPublic,
                    privateKey = privateKey
                )
            }.onFailure {
                prefs.edit()
                    .remove(KEY_PRIVATE_PKCS8_B64)
                    .remove(KEY_PUBLIC_SPKI_B64)
                    .apply()
            }
        }

        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_SIZE_BITS)
        val keyPair = generator.generateKeyPair()
        val publicB64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val privateB64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)

        prefs.edit()
            .putString(KEY_PRIVATE_PKCS8_B64, privateB64)
            .putString(KEY_PUBLIC_SPKI_B64, publicB64)
            .apply()

        return Identity(
            publicKeyBase64 = publicB64,
            privateKey = keyPair.private
        )
    }

    fun signText(privateKey: PrivateKey, text: String): String {
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(text.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    fun encryptChunked(publicKeyBase64: String, plainText: String): String {
        if (plainText.isEmpty()) return ""
        val publicKey = parsePublicKey(publicKeyBase64)

        val src = plainText.toByteArray(StandardCharsets.UTF_8)
        val output = ByteArrayOutputStream(src.size + 256)
        var offset = 0
        while (offset < src.size) {
            val len = minOf(ENCRYPT_BLOCK_SIZE, src.size - offset)
            val cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA256_SPEC)
            val block = cipher.doFinal(src, offset, len)
            output.write(block)
            offset += len
        }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    fun decryptChunked(privateKey: PrivateKey, cipherTextBase64: String): String {
        if (cipherTextBase64.isEmpty()) return ""
        val encrypted = Base64.decode(cipherTextBase64, Base64.DEFAULT)
        require(encrypted.size % DECRYPT_BLOCK_SIZE == 0) {
            "ciphertext length invalid"
        }

        val attempts = listOf<(PrivateKey, ByteArray) -> ByteArray>(
            { key, block ->
                val cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, OAEP_SHA256_SPEC)
                cipher.doFinal(block)
            },
            { key, block ->
                val cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, OAEP_SHA256_MGF1_SHA1_SPEC)
                cipher.doFinal(block)
            },
            { key, block ->
                val cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key)
                cipher.doFinal(block)
            }
        )

        var lastError: Throwable? = null
        for (decryptBlock in attempts) {
            try {
                val plainBytes = decryptBlocks(encrypted, privateKey, decryptBlock)
                return String(plainBytes, StandardCharsets.UTF_8)
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw IllegalStateException("rsa oaep decrypt failed", lastError)
    }

    fun createNonce(size: Int = 18): String {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun unixSecondsNow(): Long = System.currentTimeMillis() / 1000L

    private fun parsePublicKey(publicKeyBase64: String): PublicKey {
        val keyBytes = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    private fun parsePrivateKey(privateKeyBase64: String): PrivateKey {
        val keyBytes = Base64.decode(privateKeyBase64, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    private fun decryptBlocks(
        encrypted: ByteArray,
        privateKey: PrivateKey,
        decryptBlock: (PrivateKey, ByteArray) -> ByteArray
    ): ByteArray {
        val output = ByteArrayOutputStream(encrypted.size)
        var offset = 0
        while (offset < encrypted.size) {
            val cipherBlock = encrypted.copyOfRange(offset, offset + DECRYPT_BLOCK_SIZE)
            output.write(decryptBlock(privateKey, cipherBlock))
            offset += DECRYPT_BLOCK_SIZE
        }
        return output.toByteArray()
    }

    private companion object {
        private const val KEY_SIZE_BITS = 2048
        private const val ENCRYPT_BLOCK_SIZE = 190
        private const val DECRYPT_BLOCK_SIZE = 256
        private const val RSA_OAEP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private const val PREFS_NAME = "oms_crypto_identity"
        private const val KEY_PRIVATE_PKCS8_B64 = "private_pkcs8_b64"
        private const val KEY_PUBLIC_SPKI_B64 = "public_spki_b64"
        private val OAEP_SHA256_SPEC: OAEPParameterSpec = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
        private val OAEP_SHA256_MGF1_SHA1_SPEC: OAEPParameterSpec = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA1,
            PSource.PSpecified.DEFAULT
        )
    }
}
