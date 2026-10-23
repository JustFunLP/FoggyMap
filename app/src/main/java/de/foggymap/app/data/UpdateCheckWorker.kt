package de.foggymap.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.foggymap.app.App
import de.foggymap.app.MainActivity
import de.foggymap.app.R
import java.util.concurrent.TimeUnit

/**
 * Looks for a new FoggyMap every few hours in the background (also when the app is closed) and
 * shows a notification once per new version. Tapping it opens FoggyMap, which offers the update.
 * Only reads the public version.json from GitHub; nothing is sent.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updater = App.updater
        if (!updater.enabled) return Result.success()
        if (!updater.check(force = true, remember = false)) return Result.success()
        val available = updater.state.value as? Updater.State.Available ?: return Result.success()
        notifyOnce(applicationContext, available.versionName, available.versionCode)
        return Result.success()
    }

    companion object {
        const val CHANNEL = "updates"
        const val EXTRA_UPDATE = "de.foggymap.app.UPDATE"
        private const val NOTIFICATION_ID = 21

        /** Called on every app start; keeps one periodic check (every 3 h, only with internet). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        @android.annotation.SuppressLint("MissingPermission")
        fun notifyOnce(context: Context, versionName: String, versionCode: Int) {
            val prefs = context.getSharedPreferences("foggymap_update", Context.MODE_PRIVATE)
            if (prefs.getInt("notified_code", 0) >= versionCode) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Meldet, wenn eine neue FoggyMap-Version da ist"
                })
            val open = PendingIntent.getActivity(context, 21,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_UPDATE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_fog)
                .setContentTitle("FoggyMap $versionName ist da")
                .setContentText("Tippen zum Aktualisieren – deine Erkundung bleibt erhalten.")
                .setContentIntent(open).setAutoCancel(true).build()
            val shown = runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }.isSuccess
            if (shown) prefs.edit().putInt("notified_code", versionCode).apply()
        }
    }
}
