package com.example

import com.example.p2p.GroupWorkspaceState
import com.example.security.CryptoEngine
import com.example.util.Formatters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class NexoDropCoreUnitTest {

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
    fun `cryptoEngine writeEncryptedChunk roundtrips large buffered streams accurately`() {
        val hostKeys = CryptoEngine.generateEphemeralEcKeyPair()
        val peerKeys = CryptoEngine.generateEphemeralEcKeyPair()
        val salt = "nexodrop-hkdf-v1".toByteArray()
        val (hostSession, _) = CryptoEngine.deriveSessionKey(hostKeys, peerKeys.public.encoded, salt)
        val (peerSession, _) = CryptoEngine.deriveSessionKey(peerKeys, hostKeys.public.encoded, salt)

        val payload = ByteArray(600 * 1024) { idx -> (idx * 31 and 0xFF).toByte() }
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(BufferedOutputStream(baos, 256 * 1024))
        CryptoEngine.writeEncryptedChunk(dos, hostSession, payload, 0, 262_144, flush = false)
        CryptoEngine.writeEncryptedChunk(dos, hostSession, payload, 262_144, payload.size - 262_144, flush = false)
        CryptoEngine.writeEncryptedFrame(dos, hostSession, ByteArray(0))
        dos.flush()

        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val reconstructed = ByteArrayOutputStream()
        while (true) {
            val frame = CryptoEngine.readEncryptedFrame(dis, peerSession)
            if (frame.isEmpty()) break
            reconstructed.write(frame)
        }
        assertArrayEquals(payload, reconstructed.toByteArray())
    }

    @Test
    fun `formatEta and delivery receipt workspace state work accurately`() {
        assertEquals("…", Formatters.formatEta(0L, 1000L))
        assertEquals("…", Formatters.formatEta(1000L, 0L))
        assertEquals("5s", Formatters.formatEta(5000L, 1000L))
        assertEquals("2m 10s", Formatters.formatEta(130_000L, 1000L))
        assertEquals("1h 5m", Formatters.formatEta(3900_000L, 1000L))

        val ws = GroupWorkspaceState(
            myDeviceId = "dev-1",
            deliveryReceipts = mapOf("item_01" to listOf("Pixel 8"))
        )
        assertEquals("dev-1", ws.myDeviceId)
        assertEquals(listOf("Pixel 8"), ws.deliveryReceipts["item_01"])
    }
}
