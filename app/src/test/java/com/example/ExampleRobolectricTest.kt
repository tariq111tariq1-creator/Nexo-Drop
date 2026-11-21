package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.security.CryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("NexoDrop", appName)
    }

    @Test
    fun `ecdh key exchange and aes256 gcm roundtrip`() {
        val hostKeys = CryptoEngine.generateEphemeralEcKeyPair()
        val peerKeys = CryptoEngine.generateEphemeralEcKeyPair()
        val salt = "nexodrop-hkdf-v1".toByteArray()

        val (hostSession, hostFp) = CryptoEngine.deriveSessionKey(hostKeys, peerKeys.public.encoded, salt)
        val (peerSession, peerFp) = CryptoEngine.deriveSessionKey(peerKeys, hostKeys.public.encoded, salt)

        assertEquals(hostFp, peerFp)

        val message = "Encrypted offline P2P payload".toByteArray()
        val encrypted = CryptoEngine.encryptBytes(hostSession, message)
        val decrypted = CryptoEngine.decryptBytes(peerSession, encrypted)
        assertArrayEquals(message, decrypted)
    }

    @Test
    fun `pbkdf2 pin hashing and verification`() {
        val salt = CryptoEngine.generateSalt()
        val hash = CryptoEngine.hashPin("482910", salt)
        assertTrue(CryptoEngine.verifyPin("482910", salt, hash))
        assertFalse(CryptoEngine.verifyPin("000000", salt, hash))
    }

    @Test
    fun `runtime discovery permissions include fine location and nearby wifi`() {
        val perms = com.example.util.PermissionsHelper.getRuntimeDiscoveryPermissions().toSet()
        assertTrue(perms.contains(android.Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(perms.contains(android.Manifest.permission.NEARBY_WIFI_DEVICES))
    }

    @Test
    fun `direct qr parser handles nexodrop json wifi and uri formats`() {
        val app = ApplicationProvider.getApplicationContext<NexoDropApplication>()
        val vm = MainViewModel(app)

        val jsonQr = """{"t":"nx_direct","ssid":"DIRECT-xy-Nexo","pass":"pass1234","ip":"192.168.49.1","port":45892,"name":"MyGroup","pin":true,"mac":"aa:bb:cc:dd:ee:ff","freq":5180}"""
        val parsedJson = vm.parseScannedQr(jsonQr)
        assertEquals("DIRECT-xy-Nexo", parsedJson?.ssid)
        assertEquals("pass1234", parsedJson?.passphrase)
        assertEquals("192.168.49.1", parsedJson?.hostIp)
        assertEquals(45892, parsedJson?.hostPort)
        assertEquals("MyGroup", parsedJson?.groupName)
        assertEquals(true, parsedJson?.hasPin)
        assertEquals("aa:bb:cc:dd:ee:ff", parsedJson?.hostMac)
        assertEquals(5180, parsedJson?.frequency)

        assertEquals(0, vm.joinApprovalFocusTick.value)
        vm.triggerFocusJoinApproval()
        assertEquals(1, vm.joinApprovalFocusTick.value)
        assertEquals("com.example.ACTION_OPEN_JOIN_REQUESTS", com.example.p2p.TransferForegroundService.ACTION_OPEN_JOIN_REQUESTS)

        val wifiQr = "WIFI:T:WPA;S:AndroidShare_12;P:secret99;;"
        val parsedWifi = vm.parseScannedQr(wifiQr)
        assertEquals("AndroidShare_12", parsedWifi?.ssid)
        assertEquals("secret99", parsedWifi?.passphrase)

        val uriQr = "nexodrop://192.168.43.1:45892?name=Test"
        val parsedUri = vm.parseScannedQr(uriQr)
        assertEquals("192.168.43.1", parsedUri?.hostIp)
        assertEquals(45892, parsedUri?.hostPort)
        assertEquals("Test", parsedUri?.groupName)
    }

    @Test
    fun `formatEta returns readable remaining duration strings`() {
        assertEquals("…", com.example.util.Formatters.formatEta(0L, 1000L))
        assertEquals("…", com.example.util.Formatters.formatEta(1000L, 0L))
        assertEquals("5s", com.example.util.Formatters.formatEta(5000L, 1000L))
        assertEquals("2m 10s", com.example.util.Formatters.formatEta(130_000L, 1000L))
        assertEquals("1h 5m", com.example.util.Formatters.formatEta(3900_000L, 1000L))
    }

    @Test
    fun `backupManager creates zip bundle from folder files`() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<NexoDropApplication>()
        val tempDir = java.io.File(app.cacheDir, "test_backup_src_${System.currentTimeMillis()}").apply { mkdirs() }
        val sampleFile = java.io.File(tempDir, "notes.txt").apply {
            writeText("NexoDrop offline encrypted transfer note")
        }
        try {
            val result = app.backupManager.backupFilesAndFolders(
                sourceFolderPath = tempDir.absolutePath,
                selectedFiles = emptyList(),
                compressZip = true,
                incrementalOnly = false,
                targetTreeUri = null
            )
            assertTrue("Expected ZIP backup success, error=${result.error}", result.success)
            assertEquals(1, result.fileCount)
            assertTrue(result.totalBytes > 0L)
            assertTrue(result.summaryLabel.endsWith(".zip"))
        } finally {
            sampleFile.delete()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `p2pGroupManager toggles autoReceiveFiles state cleanly`() {
        val app = ApplicationProvider.getApplicationContext<NexoDropApplication>()
        assertTrue(app.p2pManager.state.value.autoReceiveFiles)
        app.p2pManager.setAutoReceiveFiles(false)
        assertFalse(app.p2pManager.state.value.autoReceiveFiles)
        app.p2pManager.setAutoReceiveFiles(true)
        assertTrue(app.p2pManager.state.value.autoReceiveFiles)
    }
}
