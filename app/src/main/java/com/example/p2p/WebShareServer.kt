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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Built-in offline LAN HTTP/1.1 server on the Host device.
 * Allows any laptop, iPhone, or browser on the same Wi-Fi / Hotspot / Wi-Fi Direct network
 * to browse and download shared group files without installing the app.
 */
class WebShareServer(
    private val scope: CoroutineScope,
    private val storageRepository: StorageRepository,
    private val groupNameProvider: () -> String,
    private val groupPinProvider: () -> String,
    private val sharedItemsProvider: () -> List<SharedGroupItem>,
    private val localSourceLookup: (String) -> LocalSourceDescriptor?
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
            sock.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // Drain headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) return
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
                stream.use { input ->
                    val safeName = item.fileName.replace("\"", "_")
                    val header = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: ${item.mimeType}\r\n")
                        append("Content-Length: ${item.fileSize}\r\n")
                        append("Content-Disposition: attachment; filename=\"$safeName\"\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(header.toByteArray(Charsets.UTF_8))
                    BufferedInputStream(input).copyTo(out, 64 * 1024)
                    out.flush()
                }
            } else {
                val html = buildPortalPage(groupNameProvider(), sharedItemsProvider(), requiredPin)
                writeHtml(out, html)
            }
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

    private fun buildPortalPage(groupName: String, items: List<SharedGroupItem>, pin: String): String {
        val pinParam = if (pin.isNotBlank()) "?pin=${java.net.URLEncoder.encode(pin, "UTF-8")}" else ""
        val rows = if (items.isEmpty()) {
            "<p style='color:#94a3b8;'>No files shared from Host yet.</p>"
        } else {
            items.joinToString("") { item ->
                val url = "/download/${java.net.URLEncoder.encode(item.itemId, "UTF-8")}$pinParam"
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
            .wrap{max-width:720px;margin:0 auto}h1{color:#00e5d4;margin-bottom:4px}.sub{color:#94a3b8;margin-bottom:24px}
            .item{background:#101624;border:1px solid #2a3650;border-radius:16px;padding:16px;margin-bottom:12px;display:flex;justify-content:space-between;align-items:center;gap:12px}
            .name{font-weight:600;word-break:break-all}.meta{font-size:13px;color:#94a3b8;margin-top:4px}
            .btn{background:#00e5d4;color:#00201e;text-decoration:none;padding:10px 16px;border-radius:10px;font-weight:700;white-space:nowrap}</style>
            </head><body><div class="wrap"><h1>${escapeHtml(groupName)}</h1>
            <div class="sub">NexoDrop Offline LAN Web Share • ${items.size} file(s)</div>$rows</div></body></html>
        """.trimIndent()
    }

    private fun escapeHtml(raw: String): String {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
