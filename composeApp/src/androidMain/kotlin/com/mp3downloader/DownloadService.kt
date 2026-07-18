package com.mp3downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Foreground Service que mantiene la app viva durante descargas largas.
 *
 * - Adquiere un WakeLock parcial para evitar que la CPU duerma.
 * - Muestra una notificación persistente "Descargando...".
 * - Se detiene solo cuando todas las descargas activas finalizan.
 *
 * Uso desde MainViewModel:
 *   context.startForegroundService(Intent(context, DownloadService::class.java))
 *   context.stopService(Intent(context, DownloadService::class.java))
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "download_channel"
        private const val CHANNEL_COMPLETE_ID = "download_complete_channel"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_COMPLETE_ID = 1002

        /** Contador de descargas activas en todo el proceso. */
        private var activeCount = 0

        /**
         * Llamar cuando una descarga EMPIEZA. Si es la primera,
         * retorna true (el llamante debe iniciar el servicio).
         */
        @Synchronized
        fun onDownloadStarted(): Boolean {
            activeCount++
            Log.d(TAG, "Descarga iniciada. Activas: $activeCount")
            return activeCount == 1
        }

        /**
         * Llamar cuando una descarga TERMINA (éxito o fallo).
         * Si ya no quedan activas, retorna true (el llamante debe
         * detener el servicio).
         */
        @Synchronized
        fun onDownloadFinished(): Boolean {
            if (activeCount > 0) activeCount--
            Log.d(TAG, "Descarga finalizada. Activas: $activeCount")
            return activeCount <= 0
        }

        /** Retorna la cantidad actual de descargas activas. */
        @Synchronized
        fun getActiveCount(): Int = activeCount

        /** Muestra una notificación de descarga completada en la barra de estado. */
        fun showCompleteNotification(context: Context, title: String) {
            val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETE_ID)
                .setContentTitle("Descarga completada")
                .setContentText(title)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_COMPLETE_ID + title.hashCode(),
                notification
            )
        }

        /** Muestra una notificación de descarga fallida en la barra de estado. */
        fun showFailedNotification(context: Context, title: String, error: String) {
            val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETE_ID)
                .setContentTitle("Descarga fallida")
                .setContentText("$title — $error")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_COMPLETE_ID + title.hashCode(),
                notification
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        acquireWakeLock()
        // startForeground puede lanzar SecurityException en Android 15+ si
        // el usuario no ha concedido FOREGROUND_SERVICE_DATA_SYNC. Lo
        // capturamos gracefulmente para que no derribe la app.
        try {
            startForeground(NOTIFICATION_ID, buildNotification(0))
        } catch (e: SecurityException) {
            Log.e(TAG, "No se pudo iniciar foreground: ${e.message}. " +
                "Las descargas continuarán sin notificación persistente.")
        } catch (e: Exception) {
            Log.e(TAG, "Error en startForeground: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: activeCount=$activeCount")
        // Actualizar notificación con conteo actual
        val notification = buildNotification(activeCount)
        val manager = NotificationManagerCompat.from(this)
        manager.notify(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — liberando WakeLock")
        releaseWakeLock()
        super.onDestroy()
    }

    // ── WakeLock ────────────────────────────────────────────
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Mp3Downloader:DownloadWakeLock"
        ).apply {
            // Sin timeout: el WakeLock se libera en onDestroy() cuando todas
            // las descargas terminan. Esto garantiza que mixes ultra-largos
            // (3h+) no pierdan el WakeLock a medio camino.
            acquire()
        }
        Log.d(TAG, "WakeLock adquirido")
    }

    private fun releaseWakeLock() {
        wakeLock?.apply {
            if (isHeld) {
                release()
                Log.d(TAG, "WakeLock liberado")
            }
        }
        wakeLock = null
    }

    // ── Notificaciones ──────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val downloadChannel = NotificationChannel(
                CHANNEL_ID,
                "Descargas",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación de descargas en curso"
                setShowBadge(false)
            }
            manager.createNotificationChannel(downloadChannel)

            val completeChannel = NotificationChannel(
                CHANNEL_COMPLETE_ID,
                "Descargas completadas",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificación cuando una descarga finaliza"
                setShowBadge(true)
            }
            manager.createNotificationChannel(completeChannel)
        }
    }

    private fun buildNotification(activeDownloads: Int): Notification {
        val text = if (activeDownloads <= 0) {
            "Iniciando descarga…"
        } else if (activeDownloads == 1) {
            "1 descarga en curso"
        } else {
            "$activeDownloads descargas en curso"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Descargas Mp3")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    /**
     * Permite que la UI actualice la notificación con el conteo
     * actual sin reiniciar el servicio. Llamar desde el ViewModel
     * o Activity cuando cambie el estado de descargas.
     */
    fun updateNotification(activeDownloads: Int) {
        val notification = buildNotification(activeDownloads)
        val manager = NotificationManagerCompat.from(this)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
