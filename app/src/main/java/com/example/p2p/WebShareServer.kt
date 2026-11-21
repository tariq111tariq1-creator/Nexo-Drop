package com.example.p2p

import com.example.data.StorageRepository
import com.example.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Built-in two-way offline LAN HTTP/1.1 server on the Host device.
 * Allows any laptop, iPhone, or browser on the same Wi-Fi / Hotspot / Wi-Fi Direct network
 * to browse, download, AND upload files directly to the Host group without installing the app.
 */
class WebShareServer(
    private val scope: CoroutineScope,
    private val storageRepository: StorageRepository,
    private val cacheDir: File,
    private val groupNameProvider: () -> String,
    private val groupPinProvider: () -> String,
    private val sharedItemsProvider: () -> List<SharedGroupItem>,
    private val localSourceLookup: (String) -> LocalSourceDescriptor?,
    private val onBrowserFileUploaded: (fileName: String, tempFile: File, mimeType: String) -> Unit = { _, _, _ -> }
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    var activePort: Int = 0
        private set

    fun start(): Int {
        stop()
        val socket = ServerSocket(0)
        serverSocket = socket
        activePort = socket.localPort
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { handleClient(client) }
            }
        }
        return activePort
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        activePort = 0
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 60_000
            val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
            val requestLine = readHttpHeaderLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHttpHeaderLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullTarget = parts[1]
            val path = fullTarget.substringBefore("?")
            val query = fullTarget.substringAfter("?", "")
            val params = parseQuery(query)

            val requiredPin = groupPinProvider()
            val providedPin = params["pin"] ?: ""
            val out = BufferedOutputStream(sock.getOutputStream())

            if (requiredPin.isNotBlank() && providedPin != requiredPin) {
                val html = buildPinPage(groupNameProvider(), providedPin.isNotEmpty())
                writeHtml(out, html)
                return
            }

            if (method == "POST" && path == "/upload") {
                val contentType = headers["content-type"] ?: ""
                val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
                val uploadedCount = handleMultipartUpload(input, contentType, contentLength)
                val pinQuery = if (requiredPin.isNotBlank()) {
                    "pin=${URLEncoder.encode(requiredPin, "UTF-8")}&"
                } else ""
                val redirectUrl = "/?${pinQuery}uploaded=$uploadedCount"
                val header = "HTTP/1.1 303 See Other\r\nLocation: $redirectUrl\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }

            if (path.startsWith("/download/")) {
                val itemId = URLDecoder.decode(path.removePrefix("/download/"), "UTF-8")
                val item = sharedItemsProvider().firstOrNull { it.itemId == itemId }
                val source = localSourceLookup(itemId)
                if (item == null || source == null) {
                    writeNotFound(out)
                    return
                }
                val stream = runCatching {
                    storageRepository.openInputStream(source.uri, source.filePath)
                }.getOrNull()
                if (stream == null) {
                    writeNotFound(out)
                    return
                }
                stream.use { fileIn ->
                    val safeName = item.fileName.replace("\"", "_")
                    val header = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: ${item.mimeType}\r\n")
                        append("Content-Length: ${item.fileSize}\r\n")
                        append("Content-Disposition: attachment; filename=\"$safeName\"\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(header.toByteArray(Charsets.UTF_8))
                    BufferedInputStream(fileIn).copyTo(out, 64 * 1024)
                    out.flush()
                }
            } else {
                val uploadedBanner = params["uploaded"]?.toIntOrNull() ?: 0
                val html = buildPortalPage(groupNameProvider(), sharedItemsProvider(), requiredPin, uploadedBanner)
                writeHtml(out, html)
            }
        }
    }

    private fun handleMultipartUpload(
        rawInput: BufferedInputStream,
        contentType: String,
        contentLength: Long
    ): Int {
        val boundaryMatch = Regex("boundary=([^;\\s]+)").find(contentType) ?: return 0
        val boundary = boundaryMatch.groupValues[1].trim('"')
        val boundedStream = if (contentLength > 0) BoundedInputStream(rawInput, contentLength) else rawInput

        val startBoundary = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val partBoundaryDelimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        var uploaded = 0

        // Seek past initial boundary line
        if (!seekPastSequence(boundedStream, startBoundary)) return 0

        while (true) {
            // Expect "\r\n" or "--" (end of multipart)
            val b1 = boundedStream.read()
            val b2 = boundedStream.read()
            if (b1 == '-'.code && b2 == '-'.code) break
            if (b1 == -1 || b2 == -1) break
            if (!(b1 == '\r'.code && b2 == '\n'.code)) {
                // Skip any remainder of boundary line
                readHttpHeaderLine(boundedStream)
            }

            // Read part headers
            val partHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = readHttpHeaderLine(boundedStream) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    partHeaders[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            val disp = partHeaders["content-disposition"] ?: continue
            val rawName = Regex("filename=\"([^\"]*)\"").find(disp)?.groupValues?.getOrNull(1)
            val fileName = rawName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
            val partMime = partHeaders["content-type"]?.substringBefore(';')?.trim().orEmpty()

            if (fileName.isBlank()) {
                // Non-file field or empty file input: drain until next boundary
                drainUntilDelimiter(boundedStream, partBoundaryDelimiter, null)
                continue
            }

            val safeTemp = File.createTempFile("web_up_", "_${fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}", cacheDir)
            var ok = false
            try {
                BufferedOutputStream(FileOutputStream(safeTemp), 64 * 1024).use { fileOut ->
                    ok = drainUntilDelimiter(boundedStream, partBoundaryDelimiter, fileOut)
                    fileOut.flush()
                }
                if (ok && safeTemp.length() >= 0L) {
                    val mime = partMime.ifBlank { storageRepository.guessMimeType(fileName) }
                    onBrowserFileUploaded(fileName, safeTemp, mime)
                    uploaded++
                } else {
                    safeTemp.delete()
                }
            } catch (_: Exception) {
                safeTemp.delete()
            }
        }
        return uploaded
    }

    private fun readHttpHeaderLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        while (true) {
            val ch = input.read()
            if (ch == -1) return if (baos.size() == 0) null else baos.toString("UTF-8")
            if (ch == '\n'.code) break
            if (ch != '\r'.code) baos.write(ch)
        }
        return baos.toString("UTF-8")
    }

    private fun seekPastSequence(input: InputStream, seq: ByteArray): Boolean {
        var matched = 0
        while (true) {
            val b = input.read()
            if (b == -1) return false
            if (b.toByte() == seq[matched]) {
                matched++
                if (matched == seq.size) return true
            } else {
                matched = if (b.toByte() == seq[0]) 1 else 0
            }
        }
    }

    /**
     * Streams data to [out] while buffering only delimiter.size bytes so `\r\n--boundary`
     * is never written to the uploaded file even when split across TCP reads.
     */
    private fun drainUntilDelimiter(
        input: InputStream,
        delimiter: ByteArray,
        out: BufferedOutputStream?
    ): Boolean {
        val tail = ByteArray(delimiter.size)
        var tailLen = 0
        val lps = computeLps(delimiter)
        var matchIdx = 0

        while (true) {
            val b = input.read()
            if (b == -1) return false
            val byteVal = b.toByte()

            if (tailLen < tail.size) {
                tail[tailLen++] = byteVal
            } else {
                out?.write(tail[0].toInt())
                System.arraycopy(tail, 1, tail, 0, tail.size - 1)
                tail[tail.size - 1] = byteVal
            }

            while (matchIdx > 0 && delimiter[matchIdx] != byteVal) {
                matchIdx = lps[matchIdx - 1]
            }
            if (delimiter[matchIdx] == byteVal) {
                matchIdx++
                if (matchIdx == delimiter.size) {
                    return true
                }
            }
        }
    }

    private fun computeLps(pattern: ByteArray): IntArray {
        val lps = IntArray(pattern.size)
        var len = 0
        var i = 1
        while (i < pattern.size) {
            if (pattern[i] == pattern[len]) {
                len++
                lps[i] = len
                i++
            } else if (len != 0) {
                len = lps[len - 1]
            } else {
                lps[i] = 0
                i++
            }
        }
        return lps
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val v = delegate.read()
            if (v != -1) remaining--
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = delegate.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }
    }

    private fun writeHtml(out: BufferedOutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun writeNotFound(out: BufferedOutputStream) {
        val body = "404 File Not Found".toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8")
            } else null
        }.toMap()
    }

    private fun buildPinPage(groupName: String, wrongPin: Boolean): String {
        val err = if (wrongPin) "<p style='color:#ff5263;'>Incorrect PIN</p>" else ""
        return """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>NexoDrop — ${escapeHtml(groupName)}</title>
            <style>body{background:#090d16;color:#ebf2fa;font-family:system-ui,sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}
            .card{background:#101624;border:1px solid #2a3650;border-radius:20px;padding:28px;max-width:360px;width:90%}
            input{width:100%;padding:12px;border-radius:10px;border:1px solid #2a3650;background:#192235;color:#fff;box-sizing:border-box;margin:12px 0;font-size:16px}
            button{width:100%;padding:12px;border-radius:10px;border:0;background:#00e5d4;color:#00201e;font-weight:700;font-size:15px;cursor:pointer}</style>
            </head><body><div class="card"><h2>NexoDrop Web Share</h2><p>${escapeHtml(groupName)}</p>$err
            <form method="get" action="/"><input name="pin" type="password" placeholder="Group PIN" required autofocus>
            <button type="submit">Unlock Files</button></form></div></body></html>
        """.trimIndent()
    }

    private fun buildPortalPage(
        groupName: String,
        items: List<SharedGroupItem>,
        pin: String,
        uploadedCount: Int
    ): String {
        val pinParam = if (pin.isNotBlank()) "?pin=${URLEncoder.encode(pin, "UTF-8")}" else ""
        val uploadAction = "/upload$pinParam"
        val successNotice = if (uploadedCount > 0) {
            "<div class='notice'>Uploaded $uploadedCount file(s) to NexoDrop group!</div>"
        } else ""
        val rows = if (items.isEmpty()) {
            "<p style='color:#94a3b8;'>No files shared from Host yet.</p>"
        } else {
            items.joinToString("") { item ->
                val url = "/download/${URLEncoder.encode(item.itemId, "UTF-8")}$pinParam"
                """
                <div class="item">
                  <div>
                    <div class="name">${escapeHtml(item.fileName)}</div>
                    <div class="meta">${Formatters.formatBytes(item.fileSize)} • ${escapeHtml(item.ownerDeviceName)}</div>
                  </div>
                  <a class="btn" href="$url">Download</a>
                </div>
                """.trimIndent()
            }
        }
        return """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>NexoDrop — ${escapeHtml(groupName)}</title>
            <style>body{background:#090d16;color:#ebf2fa;font-family:system-ui,sans-serif;margin:0;padding:24px}
            .wrap{max-width:720px;margin:0 auto}h1{color:#00e5d4;margin-bottom:4px}.sub{color:#94a3b8;margin-bottom:20px}
            .upload{background:#101624;border:1.5px dashed #00e5d4;border-radius:16px;padding:18px;margin-bottom:20px}
            .upload-row{display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin-top:10px}
            input[type=file]{flex:1;color:#cbd5e1}
            .notice{background:#064e3b;color:#6ee7b7;border-radius:12px;padding:12px 16px;margin-bottom:16px;font-weight:600}
            .item{background:#101624;border:1px solid #2a3650;border-radius:16px;padding:16px;margin-bottom:12px;display:flex;justify-content:space-between;align-items:center;gap:12px}
            .name{font-weight:600;word-break:break-all}.meta{font-size:13px;color:#94a3b8;margin-top:4px}
            .btn{background:#00e5d4;color:#00201e;text-decoration:none;border:0;padding:10px 18px;border-radius:10px;font-weight:700;white-space:nowrap;cursor:pointer}</style>
            </head><body><div class="wrap"><h1>${escapeHtml(groupName)}</h1>
            <div class="sub">NexoDrop Two-Way Offline LAN Web Share • ${items.size} file(s)</div>$successNotice
            <div class="upload">
              <div style="font-weight:700">Send Files from Browser to Group</div>
              <form method="post" action="$uploadAction" enctype="multipart/form-data">
                <div class="upload-row">
                  <input type="file" name="files" multiple required>
                  <button class="btn" type="submit">Upload to Group</button>
                </div>
              </form>
            </div>
            $rows</div></body></html>
        """.trimIndent()
    }

    private fun escapeHtml(raw: String): String {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
