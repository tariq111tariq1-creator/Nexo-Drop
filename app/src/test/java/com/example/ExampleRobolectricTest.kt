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

        val jsonQr = """{"t":"nx_direct","ssid":"DIRECT-xy-Nexo","pass":"pass1234","ip":"192.168.49.1","port":45892,"name":"MyGroup","pin":true}"""
        val parsedJson = vm.parseScannedQr(jsonQr)
        assertEquals("DIRECT-xy-Nexo", parsedJson?.ssid)
        assertEquals("pass1234", parsedJson?.passphrase)
        assertEquals("192.168.49.1", parsedJson?.hostIp)
        assertEquals(45892, parsedJson?.hostPort)
        assertEquals("MyGroup", parsedJson?.groupName)
        assertEquals(true, parsedJson?.hasPin)

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
}
