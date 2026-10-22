package com.senjay.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 响铃之外的通知:提前 10 分钟的「即将提醒」(常驻、倒计时)、「已新建」、Cortex 推送。 */
object Notices {
    const val WINDOW_MS = 10 * 60_000L
    private const val CH_UPCOMING = "upcoming"
    private const val CH_CREATED = "created"
    private const val CREATED_ID = 2
    private const val UPCOMING_BASE = 100_000 // 每个提醒一条,id = 基数 + 提醒 id
    private const val CH_CORTEX = "cortex"
    private const val CORTEX_BASE = 200_000 // 每条推送一条,id = 基数 + 推送 id

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun open(ctx: Context) =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun upcoming(ctx: Context, t: Task, at: Long) {
        nm(ctx).createNotificationChannel(NotificationChannel(CH_UPCOMING, "即将提醒（提前10分钟）", NotificationManager.IMPORTANCE_LOW))
        nm(ctx).notify(
            UPCOMING_BASE + t.id,
            Notification.Builder(ctx, CH_UPCOMING)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle("即将提醒：${t.title}")
                .setContentText(listOf("${t.time.format(HHMM)} 响铃", t.note).filter { it.isNotBlank() }.joinToString(" · "))
                .setWhen(at)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true) // 通知栏里直接倒数
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open(ctx))
                .build(),
        )
    }

    fun cancelUpcoming(ctx: Context, id: Int) = nm(ctx).cancel(UPCOMING_BASE + id)

    fun created(ctx: Context, tasks: List<Task>) {
        nm(ctx).createNotificationChannel(NotificationChannel(CH_CREATED, "新建提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val text = tasks.joinToString("\n") { it.summary() }
        nm(ctx).notify(
            CREATED_ID,
            Notification.Builder(ctx, CH_CREATED)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle(if (tasks.size == 1) "已新建提醒" else "已新建 ${tasks.size} 个提醒")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open(ctx))
                .build(),
        )
    }

    /** Cortex 推来的通知(或错过的闹钟);点开跳 link,没有就打开本应用。 */
    fun cortex(ctx: Context, pushId: Int, title: String, text: String, link: String?) {
        nm(ctx).createNotificationChannel(NotificationChannel(CH_CORTEX, "Cortex 推送", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = if (link == null) open(ctx) else PendingIntent.getActivity(
            ctx, CORTEX_BASE + pushId,
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm(ctx).notify(
            CORTEX_BASE + pushId,
            Notification.Builder(ctx, CH_CORTEX)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle(title.ifBlank { "Cortex" })
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build(),
        )
    }
}
