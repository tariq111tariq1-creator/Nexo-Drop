package com.example.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

data class PermissionSnapshot(
    val nearbyWifiGranted: Boolean,
    val locationGranted: Boolean,
    val bluetoothGranted: Boolean,
    val notificationsGranted: Boolean,
    val allFilesGranted: Boolean,
    val installPackagesGranted: Boolean,
    val cameraGranted: Boolean,
    val wifiEnabled: Boolean,
    val locationServiceEnabled: Boolean
) {
    val readyForDirectP2p: Boolean
        get() = nearbyWifiGranted && locationGranted && wifiEnabled && locationServiceEnabled
}

object PermissionsHelper {

    fun getRuntimeDiscoveryPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
            list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return list.distinct().toTypedArray()
    }

    fun getSnapshot(context: Context): PermissionSnapshot {
        val location = hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPerm(context, Manifest.permission.ACCESS_COARSE_LOCATION)

        val nearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            location
        }

        val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPerm(context, Manifest.permission.BLUETOOTH_SCAN) &&
                hasPerm(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }

        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            hasPerm(context, Manifest.permission.READ_EXTERNAL_STORAGE) &&
                hasPerm(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val installApk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

        val camera = hasPerm(context, Manifest.permission.CAMERA)

        val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = runCatching { wifiMgr?.isWifiEnabled == true }.getOrDefault(false)

        val locMgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationServiceEnabled = if (locMgr != null) {
            runCatching { LocationManagerCompat.isLocationEnabled(locMgr) }.getOrDefault(true)
        } else true

        return PermissionSnapshot(
            nearbyWifiGranted = nearby,
            locationGranted = location,
            bluetoothGranted = bluetooth,
            notificationsGranted = notifications,
            allFilesGranted = allFiles,
            installPackagesGranted = installApk,
            cameraGranted = camera,
            wifiEnabled = wifiEnabled,
            locationServiceEnabled = locationServiceEnabled
        )
    }

    private fun hasPerm(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun buildAllFilesSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildInstallUnknownAppsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildWifiSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildLocationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
