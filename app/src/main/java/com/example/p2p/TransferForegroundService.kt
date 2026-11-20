package com.example.p2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.util.Formatters

class TransferForegroundService : Service() {

    companion object {
        private const val CHANNEL_TRANSFER = "nexodrop_transfer_channel"
        private const val CHANNEL_ALERTS = "nexodrop_alerts_channel"
        private const val ONGOING_ID = 1001

        private const val EXTRA_GROUP_NAME = "group_name"
        private const val EXTRA_PORT = "port"
        private const val ACTION_PROGRESS = "com.example.ACTION_PROGRESS"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_PERCENT = "percent"
        private const val EXTRA_SPEED = "speed"

        fun start(context: Context, groupName: String, port: Int) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                putExtra(EXTRA_GROUP_NAME, groupName)
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, fileName: String, percent: Int, speedBytesPerSec: Long) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannels(context, nm)
            val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFER)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.notif_transfer_title, fileName))
                .setContentText("${percent}% • ${Formatters.formatSpeed(speedBytesPerSec)}")
                .setProgress(100, percent.coerceIn(0, 100), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(ONGOING_ID, notification)
        }

        fun notifyAlert(context: Context, title: String, body: String, silent: Boolean) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannels(context, nm)
            val openIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .setSilent(silent)
            nm.notify((System.currentTimeMillis() and 0xFFFF).toInt() + 2000, builder.build())
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferForegroundService::class.java))
        }

        fun ensureChannels(context: Context, nm: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val transferChannel = NotificationChannel(
                    CHANNEL_TRANSFER,
                    context.getString(R.string.notif_channel_transfer),
                    NotificationManager.IMPORTANCE_LOW
                )
                val alertChannel = NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.notif_channel_alerts),
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(transferChannel)
                nm.createNotificationChannel(alertChannel)
            }
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun acquirePerformanceLocks() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = pm?.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "NexoDrop::TransferWakeLock"
                )?.apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L)
            }
        }
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "NexoDrop::TransferWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releasePerformanceLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquirePerformanceLocks()
        val groupName = intent?.getStringExtra(EXTRA_GROUP_NAME) ?: "NexoDrop"
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(this, nm)

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notif_hosting_title, groupName))
            .setContentText(getString(R.string.notif_hosting_body, port, 1))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(ONGOING_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releasePerformanceLocks()
        super.onDestroy()
    }
}
