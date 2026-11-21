package com.example.p2p

import com.example.data.FileCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON serialization helpers for NexoDrop's AES-256-GCM encrypted P2P wire protocol.
 */
object P2pProtocol {

    const val TYPE_JOIN_REQUEST = "JOIN_REQUEST"
    const val TYPE_JOIN_RESULT = "JOIN_RESULT"
    const val TYPE_PEER_LIST = "PEER_LIST"
    const val TYPE_MANIFEST_ADD = "MANIFEST_ADD"
    const val TYPE_MANIFEST_SYNC = "MANIFEST_SYNC"
    const val TYPE_PERMISSION_UPDATE = "PERMISSION_UPDATE"
    const val TYPE_KICK = "KICK"
    const val TYPE_CHAT = "CHAT"
    const val TYPE_FILE_PULL = "FILE_PULL"
    const val TYPE_FILE_PUSH_OFFER = "FILE_PUSH_OFFER"
    const val TYPE_FILE_META = "FILE_META"
    const val TYPE_FILE_CHUNK = "FILE_CHUNK"
    const val TYPE_FILE_DONE = "FILE_DONE"
    const val TYPE_FILE_ERROR = "FILE_ERROR"
    const val TYPE_DELIVERY_ACK = "DELIVERY_ACK"
    const val TYPE_PING = "PING"

    fun msg(type: String, block: JSONObject.() -> Unit = {}): ByteArray {
        return JSONObject().apply {
            put("type", type)
            block()
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun parse(bytes: ByteArray): JSONObject {
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun encodePeers(peers: List<GroupPeer>): JSONArray {
        return JSONArray().apply {
            peers.forEach { p ->
                put(JSONObject().apply {
                    put("deviceId", p.deviceId)
                    put("deviceName", p.deviceName)
                    put("ipAddress", p.ipAddress)
                    put("permission", p.permission.name)
                    put("keyFingerprint", p.keyFingerprint)
                    put("isHost", p.isHost)
                })
            }
        }
    }

    fun decodePeers(arr: JSONArray): List<GroupPeer> {
        val list = mutableListOf<GroupPeer>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list += GroupPeer(
                deviceId = o.getString("deviceId"),
                deviceName = o.getString("deviceName"),
                ipAddress = o.optString("ipAddress", ""),
                permission = runCatching { PeerPermission.valueOf(o.getString("permission")) }
                    .getOrDefault(PeerPermission.FULL_ACCESS),
                keyFingerprint = o.optString("keyFingerprint", ""),
                isHost = o.optBoolean("isHost", false)
            )
        }
        return list
    }

    fun encodeItems(items: List<SharedGroupItem>): JSONArray {
        return JSONArray().apply {
            items.forEach { item ->
                put(JSONObject().apply {
                    put("itemId", item.itemId)
                    put("fileName", item.fileName)
                    put("fileSize", item.fileSize)
                    put("mimeType", item.mimeType)
                    put("category", item.category.name)
                    put("ownerDeviceId", item.ownerDeviceId)
                    put("ownerDeviceName", item.ownerDeviceName)
                    put("sha256", item.sha256)
                    put("addedAt", item.addedAt)
                    put("isAppPackage", item.isAppPackage)
                })
            }
        }
    }

    fun decodeItems(arr: JSONArray): List<SharedGroupItem> {
        val list = mutableListOf<SharedGroupItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list += SharedGroupItem(
                itemId = o.getString("itemId"),
                fileName = o.getString("fileName"),
                fileSize = o.getLong("fileSize"),
                mimeType = o.optString("mimeType", "application/octet-stream"),
                category = runCatching { FileCategory.valueOf(o.optString("category", "OTHER")) }
                    .getOrDefault(FileCategory.OTHER),
                ownerDeviceId = o.getString("ownerDeviceId"),
                ownerDeviceName = o.getString("ownerDeviceName"),
                sha256 = o.optString("sha256", ""),
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                isAppPackage = o.optBoolean("isAppPackage", false)
            )
        }
        return list
    }
}
