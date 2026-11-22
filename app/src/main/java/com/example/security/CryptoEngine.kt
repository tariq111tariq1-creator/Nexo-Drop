package com.example.security

import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real cryptographic engine providing:
 * - Ephemeral ECDH (secp256r1) key exchange + HKDF-SHA256 session key derivation
 * - AES-256-GCM authenticated framing for control messages and file chunks
 * - SHA-256 file integrity digests
 * - PBKDF2-HMAC-SHA256 PIN hashing & verification
 */
object CryptoEngine {

    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 12000
    private val secureRandom = SecureRandom()

    fun generateEphemeralEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return kpg.generateKeyPair()
    }

    fun decodeEcPublicKey(encoded: ByteArray): PublicKey {
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(X509EncodedKeySpec(encoded))
    }

    fun deriveSessionKey(localKeyPair: KeyPair, remotePublicKeyBytes: ByteArray, groupSalt: ByteArray): Pair<SecretKey, String> {
        val remotePub = decodeEcPublicKey(remotePublicKeyBytes)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localKeyPair.private)
        agreement.doPhase(remotePub, true)
        val sharedSecret = agreement.generateSecret()

        // HKDF-Extract & Expand using HMAC-SHA256
        val prk = hmacSha256(if (groupSalt.isEmpty()) ByteArray(32) else groupSalt, sharedSecret)
        val okm = hmacSha256(prk, "nexodrop-aes256-gcm-v1".toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))
        val sessionKey = SecretKeySpec(okm.copyOf(32), "AES")

        // Human-readable 6-char verification fingerprint derived from both public keys
        val digest = MessageDigest.getInstance("SHA-256")
        val localBytes = localKeyPair.public.encoded
        val first = if (localBytes.contentHashCode() < remotePublicKeyBytes.contentHashCode()) localBytes else remotePublicKeyBytes
        val second = if (first === localBytes) remotePublicKeyBytes else localBytes
        digest.update(first)
        digest.update(second)
        val fpBytes = digest.digest()
        val fingerprint = "%02X%02X-%02X%02X".format(fpBytes[0], fpBytes[1], fpBytes[2], fpBytes[3])

        return sessionKey to fingerprint
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private val tlsEncryptCipher = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") }
    private val tlsDecryptCipher = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") }

    fun encryptBytes(key: SecretKey, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = ByteArray(GCM_IV_LEN)
        secureRandom.nextBytes(iv)
        val cipher = tlsEncryptCipher.get() ?: Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArray(GCM_IV_LEN + ciphertext.size).apply {
            System.arraycopy(iv, 0, this, 0, GCM_IV_LEN)
            System.arraycopy(ciphertext, 0, this, GCM_IV_LEN, ciphertext.size)
        }
    }

    fun decryptBytes(key: SecretKey, ivAndCiphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(ivAndCiphertext.size > GCM_IV_LEN) { "Invalid encrypted payload" }
        val cipher = tlsDecryptCipher.get() ?: Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, ivAndCiphertext, 0, GCM_IV_LEN))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ivAndCiphertext, GCM_IV_LEN, ivAndCiphertext.size - GCM_IV_LEN)
    }

    fun writeEncryptedFrame(out: DataOutputStream, key: SecretKey, payload: ByteArray) {
        writeEncryptedChunk(out, key, payload, 0, payload.size, flush = true)
    }

    /**
     * High-throughput chunk writer avoiding intermediate ByteArray concatenations and per-chunk flushes during bulk transfers.
     * Preserves the exact [4-byte frame length][12-byte IV][AES-GCM ciphertext+tag] wire format.
     */
    fun writeEncryptedChunk(
        out: DataOutputStream,
        key: SecretKey,
        plaintext: ByteArray,
        offset: Int = 0,
        length: Int = plaintext.size,
        flush: Boolean = false
    ) {
        val iv = ByteArray(GCM_IV_LEN)
        secureRandom.nextBytes(iv)
        val cipher = tlsEncryptCipher.get() ?: Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val outSize = cipher.getOutputSize(length)
        val cipherBuf = ByteArray(outSize)
        val cipherLen = cipher.doFinal(plaintext, offset, length, cipherBuf, 0)
        out.writeInt(GCM_IV_LEN + cipherLen)
        out.write(iv)
        out.write(cipherBuf, 0, cipherLen)
        if (flush) out.flush()
    }

    fun readEncryptedFrame(input: DataInputStream, key: SecretKey, maxFrameSize: Int = 16 * 1024 * 1024): ByteArray {
        val len = input.readInt()
        if (len <= 0 || len > maxFrameSize) {
            throw IllegalStateException("Frame length out of bounds: $len")
        }
        val buf = ByteArray(len)
        input.readFully(buf)
        return decryptBytes(key, buf)
    }

    fun sha256Hex(file: File): String {
        return file.inputStream().use { sha256Hex(it) }
    }

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var read = input.read(buffer)
        while (read != -1) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun generateSalt(): String {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    fun hashPin(pin: String, saltBase64: String): String {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun verifyPin(pin: String, saltBase64: String, expectedHash: String): Boolean {
        if (saltBase64.isBlank() || expectedHash.isBlank()) return false
        val actual = hashPin(pin, saltBase64)
        return MessageDigest.isEqual(actual.toByteArray(), expectedHash.toByteArray())
    }

    fun generateRandomPin(digits: Int = 6): String {
        return (1..digits).joinToString("") { secureRandom.nextInt(10).toString() }
    }
}
