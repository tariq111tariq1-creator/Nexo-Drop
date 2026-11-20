package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES-256-GCM Private Vault.
 * Files stored here reside in app-private storage (filesDir/vault) and are
 * encrypted chunk-by-chunk so they are invisible to Gallery and external file browsers.
 */
class VaultManager(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "nexodrop_vault_master_key_v1"
        private const val CHUNK_SIZE = 256 * 1024
        private const val MAGIC_HEADER = 0x4E585631 // "NXV1"
    }

    val vaultDir: File by lazy {
        File(context.filesDir, "vault").apply { if (!exists()) mkdirs() }
    }

    val previewCacheDir: File by lazy {
        File(context.cacheDir, "vault_preview").apply { if (!exists()) mkdirs() }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    data class EncryptedSaveResult(
        val encryptedFileName: String,
        val plainSize: Long,
        val sha256: String
    )

    fun encryptIntoVault(inputStream: InputStream): EncryptedSaveResult {
        val masterKey = getOrCreateMasterKey()
        val encryptedFileName = "${UUID.randomUUID()}.nxv"
        val outFile = File(vaultDir, encryptedFileName)
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L

        DataOutputStream(outFile.outputStream().buffered()).use { dos ->
            dos.writeInt(MAGIC_HEADER)
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = inputStream.read(buffer)
                if (read <= 0) break
                totalBytes += read
                digest.update(buffer, 0, read)
                val plainChunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, masterKey)
                val iv = cipher.iv
                val encrypted = cipher.doFinal(plainChunk)
                dos.writeInt(iv.size)
                dos.write(iv)
                dos.writeInt(encrypted.size)
                dos.write(encrypted)
            }
            dos.writeInt(-1) // EOF marker
            dos.flush()
        }

        val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
        return EncryptedSaveResult(encryptedFileName, totalBytes, shaHex)
    }

    fun decryptFromVault(encryptedFileName: String, outputStream: OutputStream) {
        val masterKey = getOrCreateMasterKey()
        val inFile = File(vaultDir, encryptedFileName)
        require(inFile.exists()) { "Encrypted vault file not found" }

        DataInputStream(inFile.inputStream().buffered()).use { dis ->
            val magic = dis.readInt()
            require(magic == MAGIC_HEADER) { "Invalid vault format" }
            while (true) {
                val ivLen = dis.readInt()
                if (ivLen == -1) break
                val iv = ByteArray(ivLen)
                dis.readFully(iv)
                val encLen = dis.readInt()
                val enc = ByteArray(encLen)
                dis.readFully(enc)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
                val plain = cipher.doFinal(enc)
                outputStream.write(plain)
            }
            outputStream.flush()
        }
    }

    fun decryptToTempPreviewFile(encryptedFileName: String, originalName: String): File {
        clearPreviewCache()
        val safeName = originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val tempFile = File(previewCacheDir, safeName)
        tempFile.outputStream().buffered().use { out ->
            decryptFromVault(encryptedFileName, out)
        }
        return tempFile
    }

    fun deleteEncryptedFile(encryptedFileName: String): Boolean {
        val target = File(vaultDir, encryptedFileName)
        return !target.exists() || target.delete()
    }

    fun clearPreviewCache() {
        previewCacheDir.listFiles()?.forEach { it.delete() }
    }
}
