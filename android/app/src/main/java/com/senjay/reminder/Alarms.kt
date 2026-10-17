package com.senjay.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDateTime

/**
 * 用 setAlarmClock:系统按「闹钟」对待——穿透 Doze、状态栏显示闹钟图标、
 * 允许从它的广播里起前台服务。国产 ROM 上也是最不容易被吞的定时方式。
 * 每个提醒最多三个 PendingIntent:正点、提前 10 分钟(挂「即将提醒」通知)、稍后提醒。
 */
object Alarms {
    const val TEST_ID = 0 // 设置页「测试响铃」用,真实提醒 id 从 1 开始
    private const val SNOOZE = 1 shl 20 // 稍后提醒独立一个,重排正常闹钟时不会把它覆盖掉
    private const val PRE = 2 shl 20

    private fun pi(ctx: Context, id: Int, offset: Int = 0): PendingIntent = PendingIntent.getBroadcast(
        ctx, id + offset,
        Intent(ctx, AlarmReceiver::class.java).putExtra("id", id).putExtra("pre", offset == PRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun schedule(ctx: Context, t: Task) {
        val next = t.next(LocalDateTime.now())?.takeIf { t.enabled }
        if (next == null) {
            am(ctx).cancel(pi(ctx, t.id))
            am(ctx).cancel(pi(ctx, t.id, PRE))
            Notices.cancelUpcoming(ctx, t.id)
            return
        }
        val at = next.toMillis()
        at(ctx, t.id, at)
        val preAt = at - Notices.WINDOW_MS
        if (preAt <= System.currentTimeMillis()) {
            Notices.upcoming(ctx, t, at) // 已在 10 分钟窗口内,直接挂上
        } else {
            Notices.cancelUpcoming(ctx, t.id) // 改了时间之后,旧的「即将提醒」不能留着
            am(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preAt, pi(ctx, t.id, PRE))
        }
    }

    fun at(ctx: Context, id: Int, millis: Long, snooze: Boolean = false) {
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), pi(ctx, id, if (snooze) SNOOZE else 0))
    }

    // ponytail: 只在删除时连带取消「稍后提醒」;关掉开关时已排的那次稍后仍会响一次
    fun cancel(ctx: Context, id: Int) {
        listOf(0, SNOOZE, PRE).forEach { am(ctx).cancel(pi(ctx, id, it)) }
        Notices.cancelUpcoming(ctx, id)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (intent.getBooleanExtra("pre", false)) {
            val t = Store.load(ctx).find { it.id == id && it.enabled }
            if (t != null) t.next(LocalDateTime.now())?.let { Notices.upcoming(ctx, t, it.toMillis()) }
            return
        }
        if (id != Alarms.TEST_ID) {
            val tasks = Store.load(ctx)
            if (tasks.none { it.id == id }) {
                Log.w("AlarmReceiver", "提醒 $id 已被删除,忽略这次闹钟")
                return
            }
            // 只响一次的提醒响过就关掉;重复提醒由 put 里的 schedule 排下一次(并换掉「即将提醒」)
            Store.put(ctx, tasks.map { if (it.id == id && it.once) it.copy(enabled = false) else it })
            Notices.cancelUpcoming(ctx, id)
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
