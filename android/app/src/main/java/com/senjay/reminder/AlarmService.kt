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
        val id = intent?.getIntExtra("id", -1) ?: -1
        when (intent?.action) {
            ACTION_SNOOZE -> {
                Alarms.at(this, id, System.currentTimeMillis() + SNOOZE_MS, snooze = true)
                stopSelf()
            }
            ACTION_STOP -> stopSelf()
            else -> {
                // ponytail: 接收器刚确认过任务存在,这里为空只可能是毫秒级的并发删除
                val t = taskFor(this, id) ?: Task(id, "提醒", time = LocalTime.now())
                startForeground(NOTIF_ID, notification(t))
                ring(t)
                // 有悬浮窗权限时,用着手机也直接全屏弹出,而不只是顶部横幅
                if (Settings.canDrawOverlays(this)) startActivity(alarmIntent(this, t.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ stopSelf() }, RING_MS)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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

    private fun notification(t: Task): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "提醒响铃", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // 声音和震动由服务自己放,通知只负责弹出
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val full = PendingIntent.getActivity(
            this, t.id, alarmIntent(this, t.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(t.title)
            .setContentText(listOf(t.time.format(HHMM), t.note).filter { it.isNotBlank() }.joinToString(" · "))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .addAction(action("稍后10分钟", ACTION_SNOOZE, t.id))
            .addAction(action("关闭", ACTION_STOP, t.id))
            .build()
    }

    private fun action(label: String, act: String, id: Int): Notification.Action {
        val intent = Intent(this, AlarmService::class.java).setAction(act).putExtra("id", id)
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

        fun taskFor(ctx: Context, id: Int): Task? =
            if (id == Alarms.TEST_ID) Task(id, "测试响铃", "能看到弹窗、听到铃声，说明权限都正常", LocalTime.now())
            else Store.load(ctx).find { it.id == id }

        fun alarmIntent(ctx: Context, id: Int) = Intent(ctx, AlarmActivity::class.java).putExtra("id", id)
    }
}
