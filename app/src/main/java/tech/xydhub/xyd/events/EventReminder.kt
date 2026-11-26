package tech.xydhub.xyd.events

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.Notification
import tech.xydhub.xyd.MainActivity
import tech.xydhub.xyd.R
import tech.xydhub.xyd.data.CineEvent

/**
 * Rappels des soirées ciné, programmés par le téléphone lui-même au moment de l'inscription
 * (1 h avant, puis 5 min avant) : aucun service de notification serveur nécessaire, marche hors ligne.
 */
object EventReminder {
    private const val CHANNEL = "events"
    private val OFFSETS = longArrayOf(60 * 60_000L, 5 * 60_000L)

    fun schedule(context: Context, e: CineEvent) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        OFFSETS.forEachIndexed { i, before ->
            val at = e.startsAt - before
            if (at <= now) return@forEachIndexed
            val text = if (before >= 60 * 60_000L) "Ta soirée ciné commence dans 1 h" else "Ta soirée ciné commence dans 5 min"
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, e, i, text))
        }
    }

    fun cancel(context: Context, e: CineEvent) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        OFFSETS.indices.forEach { i -> am.cancel(pending(context, e, i, "")) }
    }

    /** Reprogramme les rappels des soirées auxquelles on est inscrit (après mise à jour de la liste). */
    fun sync(context: Context, events: List<CineEvent>) = events.filter { it.joined }.forEach { schedule(context, it) }

    private fun pending(context: Context, e: CineEvent, i: Int, text: String): PendingIntent {
        val intent = Intent(context, EventReminderReceiver::class.java)
            .putExtra("title", e.title).putExtra("text", text).putExtra("id", e.id.hashCode() * 4 + i)
        return PendingIntent.getBroadcast(
            context, e.id.hashCode() * 4 + i, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Soirées ciné", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xyd)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .build()
        runCatching { nm.notify(id, n) }
    }
}

class EventReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventReminder.notify(
            context,
            intent.getIntExtra("id", 7),
            intent.getStringExtra("title") ?: "Soirée ciné",
            intent.getStringExtra("text") ?: "Ta soirée ciné commence bientôt",
        )
    }
}
