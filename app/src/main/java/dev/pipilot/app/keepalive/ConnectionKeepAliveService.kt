package dev.pipilot.app.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.pipilot.app.MainActivity
import dev.pipilot.app.R
import dev.pipilot.app.log.AppLog

/**
 * Raise process priority in the background and hold PARTIAL_WAKE_LOCK + WifiLock.
 * Does not own the connection itself (PiViewModel still does); stops on expiry or return to foreground.
 *
 * A notification alone is not enough: on many OEM builds the FGS notification stays up
 * while CPU/radio still doze. WakeLock keeps the CPU; WifiLock tries to keep Wi‑Fi
 * (cellular still depends on OEM / SSH keepalives).
 */
class ConnectionKeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val stopRunnable = Runnable {
        AppLog.i(TAG, "keep-alive window elapsed (${KEEP_ALIVE_MS / 60_000}min); stopping FGS")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // START_STICKY would restart the service with a null intent after process death; SSH lives in the ViewModel, so this would be an empty notification
            AppLog.w(TAG, "null intent sticky restart; no SSH in service, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            AppLog.i(TAG, "stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType(),
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        acquireWifiLock()
        handler.removeCallbacks(stopRunnable)
        handler.postAtTime(stopRunnable, SystemClock.uptimeMillis() + KEEP_ALIVE_MS)
        AppLog.i(
            TAG,
            "FGS started; wakeLock=${wakeLock?.isHeld == true}; wifiLock=${wifiLock?.isHeld == true}; auto-stop in ${KEEP_ALIVE_MS / 60_000}min",
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        releaseWifiLock()
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        AppLog.i(TAG, "FGS destroyed")
        super.onDestroy()
    }

    private fun foregroundServiceType(): Int {
        return when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pipilot:ssh_keepalive").apply {
            setReferenceCounted(false)
            acquire(KEEP_ALIVE_MS + 30_000L)
        }
        AppLog.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        runCatching {
            if (wl.isHeld) wl.release()
        }
        wakeLock = null
        AppLog.i(TAG, "WakeLock released")
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
        // Always FULL_HIGH_PERF (keep long-lived SSH up); LOW_LATENCY is for realtime and is deprecated from API 34
        val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "pipilot:ssh_wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
        AppLog.i(TAG, "WifiLock acquired mode=$mode")
    }

    private fun releaseWifiLock() {
        val wl = wifiLock ?: return
        runCatching {
            if (wl.isHeld) wl.release()
        }
        wifiLock = null
        AppLog.i(TAG, "WifiLock released")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.keepalive_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keepalive)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "KeepAlive"
        const val CHANNEL_ID = "connection_keepalive"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "dev.pipilot.app.keepalive.STOP"
        /** Short background keep-alive window: 10 minutes. */
        const val KEEP_ALIVE_MS = 10 * 60_000L

        fun start(context: Context) {
            val i = Intent(context, ConnectionKeepAliveService::class.java)
            try {
                ContextCompat.startForegroundService(context, i)
                AppLog.i(TAG, "startForegroundService requested")
            } catch (e: Exception) {
                AppLog.e(TAG, "startForegroundService failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, ConnectionKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(i) }
                .onFailure {
                    runCatching { context.stopService(Intent(context, ConnectionKeepAliveService::class.java)) }
                }
            AppLog.i(TAG, "stop requested from client")
        }

        fun canPostNotifications(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 33) return true
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
