package com.senjay.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 用 setAlarmClock:系统按「闹钟」对待——穿透 Doze、状态栏显示闹钟图标、
 * 允许从它的广播里起前台服务。国产 ROM 上也是最不容易被吞的定时方式。
 */
object Alarms {
    const val TEST_ID = 0 // 设置页「测试响铃」用,真实提醒 id 从 1 开始
    private const val SNOOZE = 1 shl 20 // 稍后提醒用独立的 PendingIntent,重排正常闹钟时不会把它覆盖掉

    private fun pi(ctx: Context, id: Int, snooze: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        ctx, if (snooze) id + SNOOZE else id,
        Intent(ctx, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun schedule(ctx: Context, t: Task) {
        val next = t.next(LocalDateTime.now())
        if (!t.enabled || next == null) return am(ctx).cancel(pi(ctx, t.id))
        at(ctx, t.id, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    fun at(ctx: Context, id: Int, millis: Long, snooze: Boolean = false) {
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), pi(ctx, id, snooze))
    }

    // ponytail: 只在删除时连带取消「稍后提醒」;关掉开关时已排的那次稍后仍会响一次
    fun cancel(ctx: Context, id: Int) {
        am(ctx).cancel(pi(ctx, id))
        am(ctx).cancel(pi(ctx, id, snooze = true))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (id != Alarms.TEST_ID) {
            val tasks = Store.load(ctx)
            if (tasks.none { it.id == id }) {
                Log.w("AlarmReceiver", "提醒 $id 已被删除,忽略这次闹钟")
                return
            }
            // 只响一次的提醒响过就关掉;重复提醒由 put 里的 schedule 排下一次
            Store.put(ctx, tasks.map { if (it.id == id && it.days.isEmpty()) it.copy(enabled = false) else it })
        }
        ctx.startForegroundService(Intent(ctx, AlarmService::class.java).putExtra("id", id))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Log.i("BootReceiver", "${intent.action}:重排全部闹钟")
        Store.load(ctx).forEach { Alarms.schedule(ctx, it) }
    }
}
