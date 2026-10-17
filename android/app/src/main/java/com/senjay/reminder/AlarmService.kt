package com.senjay.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.time.LocalTime

/**
 * 响铃放在前台服务而不是弹窗页里:手机正在用时全屏通知只会变成顶部横幅,
 * 页面根本不启动,铃声若放在页面里就不响了。
 */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SNOOZE -> {
                val at = System.currentTimeMillis() + SNOOZE_MS
                ringing.forEach {
                    Alarms.at(this, it.id, at, snooze = true)
                    if (it.id != Alarms.TEST_ID) Notices.upcoming(this, it, at)
                }
                stopSelf()
            }
            ACTION_STOP -> stopSelf()
            else -> {
                val id = intent?.getIntExtra("id", -1) ?: -1
                // ponytail: 接收器刚确认过任务存在,这里为空只可能是毫秒级的并发删除
                val t = taskFor(this, id) ?: Task(id, "提醒", time = LocalTime.now())
                ringing.removeAll { it.id == t.id }
                ringing += t // 同一时间响的几个都留着,不互相顶掉
                startForeground(NOTIF_ID, notification())
                ring(t)
                // 有悬浮窗权限时,用着手机也直接全屏弹出,而不只是顶部横幅
                if (Settings.canDrawOverlays(this)) {
                    startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ stopSelf() }, RING_MS)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ringing.clear() // 弹窗看到列表空了会自己关掉
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        getSystemService(Vibrator::class.java).cancel()
        super.onDestroy()
    }

    private fun ring(t: Task) {
        player?.release()
        val chosen = (t.sound ?: Prefs.get(this, Prefs.SOUND).ifBlank { null })?.let(Uri::parse)
        val fallbacks = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .mapNotNull(RingtoneManager::getDefaultUri)
        // 自定义音频被删了也要响:逐个试,失败的都记日志
        player = (listOfNotNull(chosen) + fallbacks).firstNotNullOfOrNull { uri ->
            runCatching { play(uri) }.onFailure { Log.e(TAG, "铃声 $uri 播放失败,换下一个", it) }.getOrNull()
        }
        if (player == null) Log.e(TAG, "所有铃声都放不出来,只剩震动和通知")
        getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0), ATTRS)
    }

    private fun play(uri: Uri): MediaPlayer {
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(ATTRS)
            p.setDataSource(this, uri)
            p.isLooping = true
            p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            p.prepare()
            p.start()
            return p
        } catch (e: Exception) {
            p.release()
            throw e
        }
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "提醒响铃", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // 声音和震动由服务自己放,通知只负责弹出
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val full = PendingIntent.getActivity(
            this, 0, Intent(this, AlarmActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = ringing.joinToString("\n") { listOf(it.time.format(HHMM), it.title, it.note).filter(String::isNotBlank).joinToString(" · ") }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(if (ringing.size == 1) ringing[0].title else "${ringing.size} 个提醒在响")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .addAction(action("稍后10分钟", ACTION_SNOOZE))
            .addAction(action("关闭", ACTION_STOP))
            .build()
    }

    private fun action(label: String, act: String): Notification.Action {
        val intent = Intent(this, AlarmService::class.java).setAction(act)
        val pi = PendingIntent.getService(this, act.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null as Icon?, label, pi).build()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_SNOOZE = "snooze"
        private const val CHANNEL = "ring"
        private const val NOTIF_ID = 1
        private const val TAG = "AlarmService"
        private const val SNOOZE_MS = 10 * 60_000L
        private const val RING_MS = 5 * 60_000L // 没人理就响 5 分钟后停
        private val ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** 正在响的提醒;服务和弹窗同进程,弹窗直接观察它。 */
        val ringing = mutableStateListOf<Task>()

        private fun taskFor(ctx: Context, id: Int): Task? =
            if (id == Alarms.TEST_ID) Task(id, "测试响铃", "能看到弹窗、听到铃声，说明权限都正常", LocalTime.now())
            else Store.load(ctx).find { it.id == id }
    }
}
