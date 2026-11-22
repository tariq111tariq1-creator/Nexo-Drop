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

        val jsonQr = """{"t":"nx_direct","ssid":"DIRECT-xy-Nexo","pass":"pass1234","ip":"192.168.49.1","port":45892,"name":"MyGroup","pin":true,"mac":"aa:bb:cc:dd:ee:ff","freq":5180,"gid":"grp999"}"""
        val parsedJson = vm.parseScannedQr(jsonQr)
        assertEquals("DIRECT-xy-Nexo", parsedJson?.ssid)
        assertEquals("pass1234", parsedJson?.passphrase)
        assertEquals("192.168.49.1", parsedJson?.hostIp)
        assertEquals(45892, parsedJson?.hostPort)
        assertEquals("MyGroup", parsedJson?.groupName)
        assertEquals(true, parsedJson?.hasPin)
        assertEquals("aa:bb:cc:dd:ee:ff", parsedJson?.hostMac)
        assertEquals(5180, parsedJson?.frequency)
        assertEquals("grp999", parsedJson?.groupId)

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

    @Test
    fun `storageRepository collects folder files recursively and clears temp transfer cache`() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<NexoDropApplication>()
        val rootDir = java.io.File(app.cacheDir, "test_recursive_folder_${System.currentTimeMillis()}").apply { mkdirs() }
        val subDir = java.io.File(rootDir, "nested").apply { mkdirs() }
        java.io.File(rootDir, "a.txt").writeText("hello")
        java.io.File(subDir, "b.bin").writeBytes(byteArrayOf(1, 2, 3, 4))

        val relayTmp = java.io.File(app.cacheDir, "relay_test_tmp.bin").apply { writeBytes(ByteArray(128)) }
        val apkDir = java.io.File(app.cacheDir, "apk_export").apply { mkdirs() }
        val apkTmp = java.io.File(apkDir, "demo.apk").apply { writeBytes(ByteArray(256)) }

        try {
            val collected = app.storageRepository.collectFolderFilesRecursively(rootDir.absolutePath)
            assertEquals(2, collected.size)
            assertTrue(collected.any { it.name == "a.txt" })
            assertTrue(collected.any { it.name == "b.bin" })

            val sizeBefore = app.storageRepository.getTempTransferCacheBytes()
            assertTrue(sizeBefore >= 384L)
            val freed = app.storageRepository.clearTempTransferCache(includePartialDownloads = false)
            assertTrue(freed >= 384L)
            assertFalse(relayTmp.exists())
            assertFalse(apkTmp.exists())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `delivery ack protocol message encodes and workspace state stores receipts`() {
        val rawBytes = com.example.p2p.P2pProtocol.msg(com.example.p2p.P2pProtocol.TYPE_DELIVERY_ACK) {
            put("itemId", "item_01")
            put("receiverName", "Pixel 8")
        }
        val json = com.example.p2p.P2pProtocol.parse(rawBytes)
        assertEquals(com.example.p2p.P2pProtocol.TYPE_DELIVERY_ACK, json.getString("type"))
        assertEquals("item_01", json.getString("itemId"))
        assertEquals("Pixel 8", json.getString("receiverName"))

        val ws = com.example.p2p.GroupWorkspaceState(
            myDeviceId = "dev-1",
            deliveryReceipts = mapOf("item_01" to listOf("Pixel 8"))
        )
        assertEquals("dev-1", ws.myDeviceId)
        assertEquals(listOf("Pixel 8"), ws.deliveryReceipts["item_01"])
    }

    @Test
    fun `cryptoEngine writeEncryptedChunk roundtrips large buffered streams accurately`() {
        val hostKeys = CryptoEngine.generateEphemeralEcKeyPair()
        val peerKeys = CryptoEngine.generateEphemeralEcKeyPair()
        val salt = "nexodrop-hkdf-v1".toByteArray()
        val (hostSession, _) = CryptoEngine.deriveSessionKey(hostKeys, peerKeys.public.encoded, salt)
        val (peerSession, _) = CryptoEngine.deriveSessionKey(peerKeys, hostKeys.public.encoded, salt)

        val payload = ByteArray(600 * 1024) { idx -> (idx * 31 and 0xFF).toByte() }
        val baos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(java.io.BufferedOutputStream(baos, 256 * 1024))
        CryptoEngine.writeEncryptedChunk(dos, hostSession, payload, 0, 262_144, flush = false)
        CryptoEngine.writeEncryptedChunk(dos, hostSession, payload, 262_144, payload.size - 262_144, flush = false)
        CryptoEngine.writeEncryptedFrame(dos, hostSession, ByteArray(0))
        dos.flush()

        val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(baos.toByteArray()))
        val reconstructed = java.io.ByteArrayOutputStream()
        while (true) {
            val frame = CryptoEngine.readEncryptedFrame(dis, peerSession)
            if (frame.isEmpty()) break
            reconstructed.write(frame)
        }
        assertArrayEquals(payload, reconstructed.toByteArray())
    }

    @Test
    fun `storageRepository deterministic partial file resumes offset and survives normal cache clear`() {
        val app = ApplicationProvider.getApplicationContext<NexoDropApplication>()
        val sourceFile = java.io.File(app.cacheDir, "resume_src.bin").apply {
            writeBytes(ByteArray(1024) { i -> (i and 0x7F).toByte() })
        }
        val partialFile = app.storageRepository.getDeterministicPartialFile("movie_clip.mp4", 1024L)
        try {
            partialFile.writeBytes(ByteArray(256))
            app.storageRepository.clearTempTransferCache(includePartialDownloads = false)
            assertTrue("Partial file must survive session cache clear for cross-QR resume", partialFile.exists())
            assertEquals(256L, partialFile.length())

            val (stream, actualOffset) = app.storageRepository.openSeekableStreamAt(
                uri = null,
                filePath = sourceFile.absolutePath,
                fileName = "movie_clip.mp4",
                stripExif = false,
                offset = 256L
            )
            assertEquals(256L, actualOffset)
            stream.use {
                val remaining = it.readBytes()
                assertEquals(768, remaining.size)
                assertEquals((256 and 0x7F).toByte(), remaining[0])
            }
        } finally {
            sourceFile.delete()
            partialFile.delete()
        }
    }

    @Test
    fun `webShareServer serves two-way HTML portal and accepts multipart file upload`() {
        val app = ApplicationProvider.getApplicationContext<NexoDropApplication>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val uploadedNames = mutableListOf<String>()
        val uploadedContents = mutableListOf<String>()
        val latch = java.util.concurrent.CountDownLatch(1)

        val server = com.example.p2p.WebShareServer(
            scope = scope,
            storageRepository = app.storageRepository,
            cacheDir = app.cacheDir,
            groupNameProvider = { "TestPortal" },
            groupPinProvider = { "" },
            sharedItemsProvider = { emptyList() },
            localSourceLookup = { null },
            onBrowserFileUploaded = { name, file, _ ->
                uploadedNames += name
                uploadedContents += file.readText()
                file.delete()
                latch.countDown()
            }
        )
        val port = server.start()
        try {
            val connGet = java.net.URL("http://127.0.0.1:$port/").openConnection() as java.net.HttpURLConnection
            assertEquals(200, connGet.responseCode)
            val html = connGet.inputStream.bufferedReader().readText()
            assertTrue(html.contains("TestPortal"))
            assertTrue(html.contains("enctype=\"multipart/form-data\""))

            val boundary = "----NexoTestBoundary123"
            val connPost = (java.net.URL("http://127.0.0.1:$port/upload").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            val payload = (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"files\"; filename=\"hello.txt\"\r\n" +
                    "Content-Type: text/plain\r\n\r\n" +
                    "Two-way LAN upload content!\r\n" +
                    "--$boundary--\r\n"
                ).toByteArray()
            connPost.setFixedLengthStreamingMode(payload.size)
            connPost.instanceFollowRedirects = false
            connPost.outputStream.use { it.write(payload) }
            assertEquals(303, connPost.responseCode)
            val location = connPost.getHeaderField("Location")
            assertTrue(location != null && location.contains("uploaded=1"))
            val connAfter = java.net.URL("http://127.0.0.1:$port$location").openConnection() as java.net.HttpURLConnection
            assertEquals(200, connAfter.responseCode)
            val afterHtml = connAfter.inputStream.bufferedReader().readText()
            assertTrue(afterHtml.contains("Uploaded 1 file(s)"))
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("hello.txt"), uploadedNames)
            assertEquals(listOf("Two-way LAN upload content!"), uploadedContents)
        } finally {
            server.stop()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
