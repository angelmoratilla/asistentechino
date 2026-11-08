package com.babelong.asistentechino

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Servicio en primer plano de tipo micrófono: su función es declarar al sistema
 * que la app está usando el micrófono "en uso", de modo que la escucha siga
 * con la pantalla apagada o la app en segundo plano (Android 10+ lo exige).
 * Muestra una notificación permanente con acceso directo a Parar.
 *
 * El motor y la grabación viven en [ClassController]; este servicio solo
 * mantiene el estado "en primer plano" y ofrece el botón Parar.
 */
class ClassService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppGraph.init(applicationContext)
        if (intent?.action == ACTION_STOP) {
            ClassController.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        createChannel()
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ClassService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Clase en curso", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_STOP = "com.babelong.asistentechino.action.STOP"
        private const val CHANNEL_ID = "clase"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ClassService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClassService::class.java))
        }
    }
}
