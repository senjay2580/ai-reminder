package com.senjay.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

// ---------- 纯逻辑(单测覆盖) ----------

/** Cortex 推来的一条:notify 立即通知;alarm 到 at(epoch 秒)响闹钟。 */
data class Push(val id: Int, val kind: String, val title: String, val body: String, val at: Long?, val url: String, val acked: Boolean) {
    companion object {
        fun fromJson(o: JSONObject) = Push(
            id = o.getInt("id"),
            kind = o.optString("kind"),
            title = o.optStringOrNull("title") ?: "",
            body = o.optStringOrNull("body") ?: "",
            at = if (o.isNull("at")) null else o.getDouble("at").toLong(), // 服务端给的是浮点秒
            url = o.optStringOrNull("url") ?: "",
            acked = !o.isNull("acked"),
        )
    }
}

sealed interface Plan {
    /** 处理过(ack 没送到又被推回来),或已被确认过(重装后从 0 拉会拉回旧的):不再落地,只补 ack。 */
    data object Skip : Plan
    data object Notify : Plan
    /** at = 截到整分的 epoch 秒(提醒只存到分钟);ringNow = 已到点但晚得不多,马上响。 */
    data class Alarm(val at: Long, val ringNow: Boolean) : Plan
    /** 晚太多了:只发通知说错过了,不建提醒、不补响。 */
    data class Missed(val at: Long) : Plan
}

const val LATE_OK_S = 10 * 60L

fun plan(p: Push, done: Set<Int>, now: Long): Plan {
    if (p.id in done || p.acked) return Plan.Skip
    if (p.kind != "alarm") return Plan.Notify // 不认识的类型也当通知弹出来,不吞
    val at = p.at ?: now // 服务端保证 alarm 带 at;万一没有就当「现在」
    val minute = at / 60 * 60
    return when {
        minute > now -> Plan.Alarm(minute, ringNow = false)
        now - at <= LATE_OK_S -> Plan.Alarm(minute, ringNow = true)
        else -> Plan.Missed(at)
    }
}

/** 同步给 Cortex 的重复格式;星期是 ISO 1=周一..7=周日,和 Task.days 一致。 */
fun Task.repeatCode(): String = when {
    monthDay != null -> "monthly:$monthDay"
    days.isEmpty() -> "once"
    days.size == 7 -> "daily"
    else -> "weekly:" + days.sorted().joinToString(",")
}

/** POST /api/device/reminders 的整表。time = 下次响铃;一次性的过期了就给它原定那一刻。 */
fun reminderPayload(tasks: List<Task>, now: LocalDateTime): JSONObject = JSONObject().put(
    "reminders",
    JSONArray(
        tasks.map { t ->
            JSONObject()
                .put("id", t.id.toString())
                .put("title", t.title)
                .put("time", (t.next(now) ?: t.date?.atTime(t.time))?.let { it.toMillis() / 1000 } ?: JSONObject.NULL)
                .put("repeat", t.repeatCode())
                .put("enabled", t.enabled)
                .put("note", t.note)
                .put("from_push", t.push ?: JSONObject.NULL)
        },
    ),
)

/** 推送里的站内链接(/#/feed?…)补上服务器地址;空 = 点通知只打开本应用。 */
fun pushLink(url: String, base: String): String? = when {
    url.isBlank() -> null
    url.startsWith("/") -> base.trimEnd('/') + url
    else -> url
}

/** 连续失败第 n 次后等多久:5 秒起翻倍,封顶 5 分钟。 */
fun backoffMs(fails: Int): Long = minOf(5 * 60_000L, 5_000L shl (fails - 1).coerceIn(0, 6))

/** 处理过的推送 id 只留最近 200 个:够判重(ack 没送到的 24 小时内会被推回来),又不无限长。 */
fun rememberIds(done: List<Int>, ids: List<Int>): List<Int> = (done + ids).distinct().takeLast(200)

// ---------- 和 Cortex 通信 ----------

/**
 * 「Cortex 联动」:实时通道 = 前台服务挂长轮询(CortexService);兜底 = WorkManager 每 15 分钟拉一次 + 整表上传;
 * 提醒一改(Store.put)3 秒防抖后整表上传。失败只记下来显示在设置页,不弹、不崩。
 */
object Cortex {
    private const val TAG = "Cortex"
    private const val PERIODIC = "cortex-sync"
    private const val UPLOAD = "cortex-upload"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("cortex", Context.MODE_PRIVATE)
    private fun base(ctx: Context) = Prefs.get(ctx, Prefs.CORTEX_BASE).trimEnd('/')
    private val net get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun on(ctx: Context) = Prefs.get(ctx, Prefs.CORTEX_ON) == "1"
    fun ready(ctx: Context) = on(ctx) && base(ctx).isNotEmpty() && Prefs.get(ctx, Prefs.CORTEX_TOKEN).isNotEmpty()

    /** 开着就拉起实时通道 + 15 分钟兜底(首次入队会立刻跑一次:拉取 + 整表上传);关了就都停。重复调用无害。 */
    fun start(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!ready(ctx)) {
            ctx.stopService(Intent(ctx, CortexService::class.java))
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CortexWorker>(15, TimeUnit.MINUTES).setConstraints(net).build(),
        )
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CortexService::class.java))
        } catch (e: IllegalStateException) { // Android 12+ 不许后台起前台服务:记下来,等下次回到前台再起
            fail(ctx, e)
        }
    }

    /** 提醒改了:3 秒内连续几次改动合成一次整表上传。 */
    fun changed(ctx: Context) {
        if (!ready(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            UPLOAD, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CortexWorker>()
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setConstraints(net)
                .setInputData(workDataOf(CortexWorker.PULL to false))
                .build(),
        )
    }

    /** wait = 长轮询:有新推送立刻返回,没有就挂 ~50 秒。 */
    fun fetch(ctx: Context, wait: Boolean): List<Push> {
        val after = sp(ctx).getInt("last", 0)
        val path = if (wait) "/api/device/wait?after=$after&timeout=50" else "/api/device/pull?after=$after"
        val a = call(ctx, path, readMs = if (wait) 75_000 else 30_000).getJSONArray("items")
        return (0 until a.length()).map { Push.fromJson(a.getJSONObject(it)) }
    }

    fun upload(ctx: Context) {
        call(ctx, "/api/device/reminders", reminderPayload(Store.load(ctx), LocalDateTime.now()))
    }

    /** 逐条落地再 ack。服务和定时任务同进程、可能同时拉到同一条,所以判重 + 落地整段加锁。 */
    fun handle(ctx: Context, items: List<Push>) {
        if (items.isEmpty()) return
        synchronized(this) {
            val sp = sp(ctx)
            val old = sp.getString("done", "")!!.split(',').mapNotNull(String::toIntOrNull)
            val handled = mutableListOf<Int>()
            val now = System.currentTimeMillis() / 1000
            try {
                for (p in items) {
                    act(ctx, p, plan(p, (old + handled).toSet(), now), now)
                    handled += p.id // 落地一条记一条:中途出错,已落地的下次不会再来一遍
                }
            } finally {
                sp.edit()
                    .putString("done", rememberIds(old, handled).joinToString(","))
                    .putInt("last", maxOf(sp.getInt("last", 0), handled.maxOrNull() ?: 0))
                    .apply()
            }
        }
        call(ctx, "/api/device/ack", JSONObject().put("ids", JSONArray(items.map { it.id })))
    }

    private fun act(ctx: Context, p: Push, plan: Plan, now: Long) {
        Log.i(TAG, "推送 #${p.id} ${p.kind} → $plan")
        when (plan) {
            Plan.Skip -> Unit
            Plan.Notify -> Notices.cortex(ctx, p.id, p.title, p.body, pushLink(p.url, base(ctx)))
            is Plan.Missed -> Notices.cortex(
                ctx, p.id, "错过的闹钟：${p.title}",
                "原定 ${whenText(local(plan.at), local(now))}，收到时已晚 ${(now - plan.at) / 60} 分钟，没有补响" +
                    if (p.body.isBlank()) "" else "\n${p.body}",
                null,
            )
            is Plan.Alarm -> {
                val at = local(plan.at)
                val tasks = Store.load(ctx)
                val t = Task(nextId(tasks), p.title.ifBlank { "Cortex 提醒" }, p.body, at.toLocalTime(), at.toLocalDate(), push = p.id)
                Store.put(ctx, tasks + t)
                // 已到点:借「稍后提醒」那个闹钟槽 1 秒后响,走正常的全屏响铃;重排闹钟时不会把它取消
                if (plan.ringNow) Alarms.at(ctx, t.id, System.currentTimeMillis() + 1_000, snooze = true)
                else Notices.created(ctx, listOf(t))
            }
        }
    }

    /** 设置页「立即同步」:拉一次 + 整表上传,失败抛给界面。 */
    suspend fun syncNow(ctx: Context) = withContext(Dispatchers.IO) {
        if (!ready(ctx)) throw IOException("先打开开关，填好服务器地址和设备令牌")
        try {
            handle(ctx, fetch(ctx, wait = false))
            upload(ctx)
            ok(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(ctx, e)
            throw e
        }
    }

    fun ok(ctx: Context) = sp(ctx).edit().putLong("ok_at", System.currentTimeMillis()).apply()

    fun fail(ctx: Context, e: Exception) {
        Log.w(TAG, "同步失败", e)
        sp(ctx).edit().putLong("err_at", System.currentTimeMillis()).putString("err", "${e.javaClass.simpleName}: ${e.message}").apply()
    }

    fun status(ctx: Context): String {
        val sp = sp(ctx)
        val now = LocalDateTime.now()
        val okAt = sp.getLong("ok_at", 0)
        val errAt = sp.getLong("err_at", 0)
        fun t(ms: Long) = whenText(local(ms / 1000), now) + ":%02d".format(local(ms / 1000).second)
        return listOfNotNull(
            "实时通道：" + if (CortexService.running) "在线" else "未运行",
            "上次同步：" + if (okAt > 0) t(okAt) else "还没成功过",
            if (errAt > okAt) "上次出错（${t(errAt)}）：${sp.getString("err", "")}" else null,
        ).joinToString("\n")
    }

    private fun local(s: Long) = LocalDateTime.ofInstant(Instant.ofEpochSecond(s), ZoneId.systemDefault())

    private fun call(ctx: Context, path: String, body: JSONObject? = null, readMs: Int = 30_000): JSONObject {
        val c = URL(base(ctx) + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = readMs
            c.setRequestProperty("Authorization", "Bearer " + Prefs.get(ctx, Prefs.CORTEX_TOKEN))
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(200)}")
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}

/**
 * 实时通道:前台服务里一直挂着长轮询,推送一到就落地。
 * 类型用 specialUse:dataSync 在 Android 15 每天只给 6 小时,这里要全天挂着;本应用侧载,不走 Play 审核。
 */
class CortexService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification()) // 先上前台:startForegroundService 后不调就崩,哪怕马上要停
        if (!Cortex.ready(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (loop?.isActive != true) loop = scope.launch { poll() }
        return START_STICKY // 被系统杀了让它自己拉起来
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    // ponytail: 不持 WakeLock(全天持有太耗电)。深度休眠时 CPU 停了,长轮询可能晚到;靠 15 分钟兜底和回到前台补拉
    private suspend fun poll() {
        var fails = 0
        while (scope.isActive) { // 请求是阻塞的、不响应取消,服务停了要靠这里退出
            if (!Cortex.ready(this)) return stopSelf()
            try {
                val started = System.currentTimeMillis()
                val items = Cortex.fetch(this, wait = true)
                Cortex.handle(this, items)
                Cortex.ok(this)
                fails = 0
                // 服务端没挂住就空手返回(地址填错成别的服务之类):别变成每秒打一次的死循环
                if (items.isEmpty() && System.currentTimeMillis() - started < 5_000) delay(30_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Cortex.fail(this, e)
                delay(backoffMs(++fails))
            }
        }
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Cortex 联动（常驻）", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Cortex 联动中")
            .setContentText("Cortex 的推送和闹钟会实时送到")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    companion object {
        private const val CHANNEL = "cortex_link"
        private const val NOTIF_ID = 3
        @Volatile var running = false
    }
}

/** 兜底:定时拉取 + 整表上传(PULL=true);或只上传(提醒改动后的防抖任务)。失败最多重试 2 次,原因记到设置页。 */
class CortexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Cortex.ready(ctx)) return Result.success()
        return try {
            withContext(Dispatchers.IO) {
                if (inputData.getBoolean(PULL, true)) Cortex.handle(ctx, Cortex.fetch(ctx, wait = false))
                Cortex.upload(ctx)
            }
            Cortex.ok(ctx)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Cortex.fail(ctx, e)
            if (runAttemptCount < 2) Result.retry() else Result.success() // 定时那个 15 分钟后还会再来
        }
    }

    companion object {
        const val PULL = "pull"
    }
}
