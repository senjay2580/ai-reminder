package com.senjay.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
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
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

// ---------- 纯逻辑(单测覆盖) ----------

/**
 * Cortex 推来的一条:notify 立即通知;alarm 到 at(epoch 秒)响闹钟;
 * reminder_upsert / reminder_delete = 网页上改了手机里的提醒,data 是 JSON 字符串。
 */
data class Push(
    val id: Int,
    val kind: String,
    val title: String,
    val body: String,
    val at: Long?,
    val url: String,
    val acked: Boolean,
    val data: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject) = Push(
            id = o.getInt("id"),
            kind = o.optString("kind"),
            title = o.optStringOrNull("title") ?: "",
            body = o.optStringOrNull("body") ?: "",
            at = if (o.isNull("at")) null else o.getDouble("at").toLong(), // 服务端给的是浮点秒
            url = o.optStringOrNull("url") ?: "",
            acked = !o.isNull("acked"),
            data = o.optStringOrNull("data") ?: "",
        )
    }
}

sealed interface Plan {
    /** 处理过(ack 没送到又被推回来)、已被确认过(重装后从 0 拉会拉回旧的)、或不认识的类型:不落地,只 ack。 */
    data object Skip : Plan
    data object Notify : Plan
    /** at = 截到整分的 epoch 秒(提醒只存到分钟);ringNow = 已到点但晚得不多,马上响。 */
    data class Alarm(val at: Long, val ringNow: Boolean) : Plan
    /** 晚太多了:只发通知说错过了,不建提醒、不补响。 */
    data class Missed(val at: Long) : Plan
    data object Upsert : Plan
    data object Delete : Plan
}

const val LATE_OK_S = 10 * 60L

fun plan(p: Push, done: Set<Int>, now: Long): Plan {
    if (p.id in done || p.acked) return Plan.Skip
    return when (p.kind) {
        "notify" -> Plan.Notify
        "reminder_upsert" -> Plan.Upsert
        "reminder_delete" -> Plan.Delete
        "alarm" -> {
            val at = p.at ?: now // 服务端保证 alarm 带 at;万一没有就当「现在」
            val minute = at / 60 * 60
            when {
                minute > now -> Plan.Alarm(minute, ringNow = false)
                now - at <= LATE_OK_S -> Plan.Alarm(minute, ringNow = true)
                else -> Plan.Missed(at)
            }
        }
        else -> Plan.Skip // 新版服务端加的类型,老 App 不认识:ack 掉,别卡住后面的
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
                .put("id", t.key)
                .put("title", t.title)
                .put("time", (t.next(now) ?: t.date?.atTime(t.time))?.let { it.toMillis() / 1000 } ?: JSONObject.NULL)
                .put("repeat", t.repeatCode())
                .put("enabled", t.enabled)
                .put("note", t.note)
                .put("from_push", t.push ?: JSONObject.NULL)
        },
    ),
)

/** 指令要改的是哪条:带 push 的先找「由推送 #push 建的那条」,找不到再按 id(网页 id / 本机数字 id)找。 */
private fun List<Task>.target(d: JSONObject, key: String): Task? =
    (if (d.isNull("push")) null else d.getInt("push"))?.let { n -> find { it.push == n } } ?: find { key.isNotEmpty() && it.key == key }

/**
 * reminder_upsert:找到目标(见 target)就原地改,本机 id / 网页 id / 铃声 / 来源推送都不变;
 * 找不到就按这个 id 新建(数字 id 且没被占用就直接用它当本机 id,否则记进 cid)。内容不对抛 IllegalArgumentException。
 */
fun List<Task>.upsert(d: JSONObject, zone: ZoneId = ZoneId.systemDefault()): List<Task> {
    val key = d.optString("id").trim()
    require(key.isNotEmpty()) { "缺 id" }
    val title = d.optString("title").trim()
    require(title.isNotEmpty()) { "缺标题" }
    require(!d.isNull("time")) { "缺时间" }
    val at = LocalDateTime.ofInstant(Instant.ofEpochSecond(d.getDouble("time").toLong()), zone) // 重复提醒只取时分
    val rep = d.optString("repeat").trim().ifEmpty { "once" }
    val old = target(d, key)
    val id = old?.id ?: key.toIntOrNull()?.takeIf { n -> n > 0 && none { it.id == n } } ?: nextId(this)
    val base = Task(
        id = id,
        title = title,
        note = d.optStringOrNull("note") ?: "",
        time = LocalTime.of(at.hour, at.minute),
        enabled = d.optBoolean("enabled", true),
        sound = old?.sound,
        push = old?.push,
        cid = if (old != null) old.cid else if (id.toString() == key) null else key,
    )
    val t = when {
        rep == "once" -> base.copy(date = at.toLocalDate())
        rep == "daily" -> base.copy(days = (1..7).toSet())
        rep.startsWith("weekly:") -> base.copy(
            days = rep.removePrefix("weekly:").split(',').map { it.trim().toInt() }.toSet()
                .also { ds -> require(ds.isNotEmpty() && ds.all { it in 1..7 }) { "星期不对：$rep" } },
        )
        rep.startsWith("monthly:") -> base.copy(
            monthDay = rep.removePrefix("monthly:").trim().toInt().also { require(it in 1..31) { "每月几号不对：$rep" } },
        )
        else -> throw IllegalArgumentException("不认识的重复方式：$rep")
    }
    return if (old != null) map { if (it.id == old.id) t else it } else this + t
}

/** reminder_delete:按 push / id 找到就删(网页 cx id、本机数字 id 都认);本机已经没有了就什么都不变。 */
fun List<Task>.delete(d: JSONObject): List<Task> {
    val key = d.optString("id").trim()
    require(key.isNotEmpty() || !d.isNull("push")) { "缺 id" }
    val t = target(d, key) ?: return this
    return filter { it.id != t.id }
}

/** 推送里的站内链接(/#/feed?…)补上服务器地址;空 = 点通知只打开本应用。 */
fun pushLink(url: String, base: String): String? = when {
    url.isBlank() -> null
    url.startsWith("/") -> base.trimEnd('/') + url
    else -> url
}

/** Cortex 网页「在手机上一键连接」:aireminder://cortex?server=<编码过的地址>&token=<设备令牌> → (地址, 令牌)。 */
fun parsePairLink(link: String): Pair<String, String> {
    val u = URI.create(link) // 格式坏了抛 IllegalArgumentException
    require(u.scheme == "aireminder" && u.host == "cortex") { "不是 Cortex 连接链接" }
    val q = (u.rawQuery ?: "").split('&').filter { '=' in it }
        .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    val server = q["server"].orEmpty().trim().trimEnd('/')
    val token = q["token"].orEmpty().trim()
    require(server.startsWith("https://")) { "服务器地址必须是 https:// 开头" }
    require(token.isNotEmpty()) { "缺设备令牌" }
    return server to token
}

/** 服务端出错回 {"error": "…"}:把这句话给用户看;不是 JSON 就带上原文前 200 字。 */
fun serverError(code: Int, body: String): String {
    val msg = try {
        JSONObject(body).optString("error")
    } catch (e: JSONException) {
        ""
    }
    return if (msg.isNotBlank()) "$msg（HTTP $code）" else "HTTP $code ${body.take(200)}"
}

/** 连续失败第 n 次后等多久:5 秒起翻倍,封顶 5 分钟。 */
fun backoffMs(fails: Int): Long = minOf(5 * 60_000L, 5_000L shl (fails - 1).coerceIn(0, 6))

/** 处理过的推送 id 只留最近 200 个:够判重(ack 没送到的 24 小时内会被推回来),又不无限长。 */
fun rememberIds(done: List<Int>, ids: List<Int>): List<Int> = (done + ids).distinct().takeLast(200)

/**
 * 兜底定时任务眼里的实时通道。since = 这一轮从何时(epoch 毫秒)起发现没在跑,0 = 正常;
 * misses = 连续几次定时检查发现没在跑;why = 最近一次从后台拉不起来的原因。
 */
data class Link(val since: Long = 0, val misses: Int = 0, val why: String = "") {
    /** 首页亮卡片:从后台拉不起来,或拉起来了却没撑到下一次检查(连续两次发现没在跑)。被杀一次、马上拉回来不打扰。 */
    val broken get() = why.isNotEmpty() || misses >= 2

    /** 定时检查一次:服务在跑就算恢复;没在跑就累计,err = 这次从后台拉起的报错(null = 拉起没报错)。 */
    fun next(running: Boolean, err: String?, now: Long) =
        if (running) Link() else Link(if (since > 0) since else now, misses + 1, err.orEmpty())
}

// ---------- 和 Cortex 通信 ----------

/**
 * 「Cortex 联动」:实时通道 = 前台服务挂长轮询(CortexService);兜底 = WorkManager 每 15 分钟拉一次 + 整表上传;
 * 提醒一改(Store.put)1 秒防抖后整表上传。失败只记下来显示在设置页,不弹、不崩。
 */
object Cortex {
    private const val TAG = "Cortex"
    private const val PERIODIC = "cortex-sync"
    private const val UPLOAD = "cortex-upload"

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Any()
    private var pending: Job? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences("cortex", Context.MODE_PRIVATE)
    private val net get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun base(ctx: Context) = Prefs.get(ctx, Prefs.CORTEX_BASE).trimEnd('/')
    fun on(ctx: Context) = Prefs.get(ctx, Prefs.CORTEX_ON) == "1"
    fun ready(ctx: Context) = on(ctx) && base(ctx).isNotEmpty() && Prefs.get(ctx, Prefs.CORTEX_TOKEN).isNotEmpty()

    /**
     * 开着就拉起实时通道 + 15 分钟兜底(首次入队会立刻跑一次:拉取 + 整表上传);关了就都停。重复调用无害。
     * 返回实时通道拉不起来的原因,null = 没问题。
     */
    fun start(ctx: Context): String? {
        val wm = WorkManager.getInstance(ctx)
        if (!ready(ctx)) {
            ctx.stopService(Intent(ctx, CortexService::class.java))
            wm.cancelUniqueWork(PERIODIC)
            sp(ctx).edit().remove("down_since").remove("down_misses").remove("down_why").apply() // 关了联动,旧的断开记录作废
            return null
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CortexWorker>(15, TimeUnit.MINUTES).setConstraints(net).build(),
        )
        return try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CortexService::class.java))
            null
        } catch (e: IllegalStateException) { // Android 12+ 后台起前台服务要豁免(ForegroundServiceStartNotAllowedException):记下来
            fail(ctx, e)
            e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * 兜底定时任务每次调:实时通道没在跑(进程被系统杀过、服务被停)就从后台拉起。Android 12+ 后台起前台服务
     * 要豁免,最常见的是关了电池优化;不许就不崩,记下原因,这次照旧由定时任务拉取。结果存成 Link,首页据此亮卡片。
     */
    fun revive(ctx: Context) {
        // ponytail: 只看服务在不在,不看长轮询是否卡住;请求都有超时,卡住会自己出来
        val running = CortexService.running
        val err = if (running) null else start(ctx)?.let { e ->
            if (exempt(ctx)) "系统不许后台拉起（$e）" else "本应用没关电池优化，Android 12 起系统不许它在后台自己重连"
        }
        val l = link(ctx).next(running, err, System.currentTimeMillis())
        Log.i(TAG, "兜底检查实时通道:${if (running) "在跑" else "没在跑,已尝试拉起"} → $l")
        sp(ctx).edit().putLong("down_since", l.since).putInt("down_misses", l.misses).putString("down_why", l.why).apply()
    }

    fun link(ctx: Context) = sp(ctx).let { Link(it.getLong("down_since", 0), it.getInt("down_misses", 0), it.getString("down_why", "")!!) }

    /** 是否已「允许后台运行」(不受电池优化):Android 12+ 有它才能从后台拉起实时通道。 */
    fun exempt(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /**
     * 几秒后用精确闹钟拉起实时通道:交给 BootReceiver 走 start。精确闹钟触发时系统允许起前台服务;
     * 服务还活着的话再 start 一次是空操作。
     */
    fun restartSoon(ctx: Context) {
        if (!ready(ctx)) return
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, BootReceiver::class.java).setAction(RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            ctx.getSystemService(AlarmManager::class.java)
                .setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 5_000, pi)
        } catch (e: SecurityException) { // 精确闹钟权限被收回(首页权限卡片会提示):只能等兜底任务
            fail(ctx, e)
        }
    }

    const val RESTART = "com.senjay.reminder.CORTEX_RESTART"

    /** 一键连接 / 账号登录拿到了地址和令牌:存下来、打开联动、拉起通道。 */
    fun connect(ctx: Context, server: String, token: String) {
        Prefs.set(ctx, Prefs.CORTEX_BASE, server)
        Prefs.set(ctx, Prefs.CORTEX_TOKEN, token)
        Prefs.set(ctx, Prefs.CORTEX_ON, "1")
        start(ctx)
    }

    /** 用 Cortex 账号换设备令牌:登录拿会话 cookie → 取令牌 → connect。密码只在这次请求里用,不存。 */
    suspend fun login(ctx: Context, user: String, password: String) = withContext(Dispatchers.IO) {
        val base = base(ctx)
        if (!base.startsWith("https://")) throw IOException("服务器地址要以 https:// 开头，密码不能明文发")
        val session = http("$base/api/login", JSONObject().put("user", user).put("password", password), emptyMap()).cookies
            .map { it.substringBefore(';').trim() }
            .firstOrNull { it.startsWith("s=") } ?: throw IOException("登录成功了，但服务器没给会话")
        val token = http("$base/api/device", null, mapOf("Cookie" to session)).json.optString("token")
        if (token.isBlank()) throw IOException("服务器没给设备令牌")
        connect(ctx, base, token)
    }

    /** 提醒改了:1 秒内连续几次改动合成一次,马上整表上传;传不上交给 WorkManager 等有网再传。 */
    fun changed(ctx: Context) {
        if (!ready(ctx)) return
        val app = ctx.applicationContext
        synchronized(uploadLock) {
            pending?.cancel()
            pending = bg.launch {
                delay(1_000)
                try {
                    upload(app)
                    ok(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(app, e)
                    WorkManager.getInstance(app).enqueueUniqueWork(
                        UPLOAD, ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<CortexWorker>().setConstraints(net).setInputData(workDataOf(CortexWorker.PULL to false)).build(),
                    )
                }
            }
        }
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

    /**
     * 按 id 顺序逐条落地 → ack → 网页改过提醒的话马上整表上传(网页的「等手机确认」靠它清掉)。
     * 服务和定时任务同进程、可能同时拉到同一条,所以判重 + 落地整段加锁。
     */
    fun handle(ctx: Context, items: List<Push>) {
        if (items.isEmpty()) return
        val sorted = items.sortedBy { it.id }
        var edited = false
        synchronized(this) {
            val sp = sp(ctx)
            val old = sp.getString("done", "")!!.split(',').mapNotNull(String::toIntOrNull)
            val handled = mutableListOf<Int>()
            val now = System.currentTimeMillis() / 1000
            try {
                for (p in sorted) {
                    val pl = plan(p, (old + handled).toSet(), now)
                    act(ctx, p, pl, now)
                    if (pl == Plan.Upsert || pl == Plan.Delete) edited = true
                    handled += p.id // 落地一条记一条:中途出错,已落地的下次不会再来一遍
                }
            } finally {
                sp.edit()
                    .putString("done", rememberIds(old, handled).joinToString(","))
                    .putInt("last", maxOf(sp.getInt("last", 0), handled.maxOrNull() ?: 0))
                    .apply()
            }
        }
        call(ctx, "/api/device/ack", JSONObject().put("ids", JSONArray(sorted.map { it.id })))
        if (edited) {
            synchronized(uploadLock) { pending?.cancel() } // 防抖那次不用再传了
            upload(ctx)
        }
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
            Plan.Upsert, Plan.Delete -> try {
                val d = JSONObject(p.data.ifBlank { "{}" })
                val tasks = Store.load(ctx)
                val after = if (plan == Plan.Upsert) tasks.upsert(d) else tasks.delete(d)
                Store.put(ctx, after)
                // Cortex 新建的(Agent 设的、到点提醒、网页新建)告诉一声;改 / 删不打扰
                after.filter { a -> tasks.none { it.id == a.id } }.takeIf { it.isNotEmpty() }?.let { Notices.created(ctx, it) }
            } catch (e: IllegalArgumentException) {
                badEdit(ctx, p, e)
            } catch (e: JSONException) {
                badEdit(ctx, p, e)
            }
        }
    }

    // 内容坏了重试也没用:弹通知说清楚,照常 ack,别卡住后面的推送
    private fun badEdit(ctx: Context, p: Push, e: Exception) {
        Log.w(TAG, "推送 #${p.id} ${p.kind} 内容不对:${p.data}", e)
        Notices.cortex(ctx, p.id, "Cortex 改提醒没成功", "「${p.title}」：${e.message}", null)
    }

    /** 立即同步(设置页按钮、一键连接、回到前台):拉一次 + 整表上传,失败记下来再抛给调用方。 */
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
        val down = link(ctx)
        fun t(ms: Long) = whenText(local(ms / 1000), now) + ":%02d".format(local(ms / 1000).second)
        return listOfNotNull(
            "实时通道：" + if (CortexService.running) "在线" else "未运行",
            if (down.broken) "后台检查：${t(down.since)} 起断开，" + down.why.ifEmpty { "拉起后又被系统停掉" } else null,
            "上次同步：" + if (okAt > 0) t(okAt) else "还没成功过",
            if (errAt > okAt) "上次出错（${t(errAt)}）：${sp.getString("err", "")}" else null,
        ).joinToString("\n")
    }

    private fun local(s: Long) = LocalDateTime.ofInstant(Instant.ofEpochSecond(s), ZoneId.systemDefault())

    private fun call(ctx: Context, path: String, body: JSONObject? = null, readMs: Int = 30_000): JSONObject =
        http(base(ctx) + path, body, mapOf("Authorization" to "Bearer " + Prefs.get(ctx, Prefs.CORTEX_TOKEN)), readMs).json

    private class Resp(val json: JSONObject, val cookies: List<String>)

    private fun http(url: String, body: JSONObject?, headers: Map<String, String>, readMs: Int = 30_000): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = readMs
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException(serverError(code, text))
            val cookies = c.headerFields.filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }.values.flatten()
            return Resp(JSONObject(text), cookies)
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
        try {
            startForeground(NOTIF_ID, notification()) // 先上前台:startForegroundService 后不调就崩,哪怕马上要停
        } catch (e: IllegalStateException) {
            // Android 12+:系统按 START_STICKY 重启本服务时也可能不许上前台。崩了会带走整个进程,
            // 所以记下来、停掉,交给兜底任务 / 回到前台再拉起
            Cortex.fail(this, e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Cortex.ready(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (loop?.isActive != true) loop = scope.launch { poll() }
        return START_STICKY // 被系统杀了让它自己拉起来
    }

    // 划掉最近任务:原生系统里服务照常活着,不少国产 ROM 随后会把整个进程杀掉。先排好几秒后再拉起一次
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("Cortex", "任务被划掉,5 秒后再拉起实时通道")
        Cortex.restartSoon(this)
        super.onTaskRemoved(rootIntent)
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
            .setContentText("Cortex 的推送、闹钟和提醒改动会实时送到")
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

/**
 * 兜底:定时拉取 + 整表上传(PULL=true),顺带检查实时通道、没在跑就拉起;或只上传(马上上传失败后等有网再传)。
 * 失败最多重试 2 次,原因记到设置页。
 */
class CortexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Cortex.ready(ctx)) return Result.success()
        val pull = inputData.getBoolean(PULL, true)
        return try {
            withContext(Dispatchers.IO) {
                // 只在定时那次的首跑检查:重试几十秒后又来,会把一次断开记成「连续两次没在跑」
                if (pull && runAttemptCount == 0) Cortex.revive(ctx)
                if (pull) Cortex.handle(ctx, Cortex.fetch(ctx, wait = false))
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
