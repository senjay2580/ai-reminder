package com.senjay.reminder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

sealed interface Action {
    data class Create(val task: Task) : Action
    data class Update(val task: Task) : Action
    data class Delete(val task: Task) : Action

    fun describe() = when (this) {
        is Create -> "新建  ${task.summary()}"
        is Update -> "修改  ${task.summary()}"
        is Delete -> "删除  ${task.summary()}"
    }
}

data class AiResult(val reply: String, val actions: List<Action>)

/** 新建的 id 在确认时才分配:AI 想的这段时间里用户可能手动加过提醒。 */
fun List<Task>.applyActions(actions: List<Action>): List<Task> = actions.fold(this) { acc, a ->
    when (a) {
        is Action.Create -> acc + a.task.copy(id = nextId(acc))
        is Action.Update -> acc.map { if (it.id == a.task.id) a.task else it }
        is Action.Delete -> acc.filter { it.id != a.task.id }
    }
}

object Ai {
    private const val TAG = "Ai"
    private val HM = DateTimeFormatter.ofPattern("H:mm")

    /** 模型按设置里的顺序逐个试,每个只试一次;全失败就把每个的原因一起抛出来。 */
    suspend fun ask(ctx: Context, text: String, images: List<Uri>, tasks: List<Task>): AiResult = withContext(Dispatchers.IO) {
        val base = Prefs.get(ctx, Prefs.AI_BASE).trimEnd('/')
        val key = Prefs.get(ctx, Prefs.AI_KEY)
        if (key.isBlank()) throw IOException("先在设置里填 AI 令牌")
        val models = Prefs.get(ctx, Prefs.AI_MODELS).split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        if (models.isEmpty()) throw IOException("先在设置里填模型")
        val pics = images.map { imageDataUrl(ctx, it) }
        val now = LocalDateTime.now()
        val errors = mutableListOf<String>()
        for (m in models) {
            try {
                return@withContext parse(respond(base, key, body(m, systemPrompt(now, tasks), text.ifBlank { "按图片内容建提醒" }, pics)), tasks, now)
            } catch (e: IOException) {
                errors += "$m：${e.message}"
            } catch (e: JSONException) {
                errors += "$m：${e.message}"
            }
            Log.w(TAG, "模型 $m 失败:${errors.last()}")
        }
        throw IOException("AI 都没答上来：\n" + errors.joinToString("\n"))
    }

    /** Responses 接口:agentrouter 的 gpt-6.x 只开了这个(chat/completions 回 400)。 */
    private fun respond(base: String, key: String, body: JSONObject): String {
        val c = URL("$base/responses").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 120_000 // 实测一句话 11s、一张课程表图 15s;effort 只能 high,留足余量
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("X-Arelay-Keep-Model", "1") // 不带的话 arelay 把模型钉成组设置,设置里的回退顺序就白填了
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("HTTP $code ${resp.take(200)}")
        return outputText(resp)
    }

    fun body(model: String, system: String, text: String, images: List<String>): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", text))
        images.forEach { content.put(JSONObject().put("type", "input_image").put("image_url", it)) }
        return JSONObject()
            .put("model", model)
            .put("instructions", system)
            .put("reasoning", JSONObject().put("effort", "high")) // agentrouter 只认 high,传别的反而按 max 跑、更慢
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
    }

    /** 拼出 output 里 message 的文字;推理块(type=reasoning)跳过。 */
    fun outputText(resp: String): String {
        val out = JSONObject(resp).getJSONArray("output")
        val text = buildString {
            for (i in 0 until out.length()) {
                val o = out.getJSONObject(i)
                if (o.optString("type") != "message") continue
                val cs = o.getJSONArray("content")
                for (j in 0 until cs.length()) cs.getJSONObject(j).takeIf { it.optString("type") == "output_text" }?.let { append(it.getString("text")) }
            }
        }
        if (text.isBlank()) throw IOException("没有返回文字：${resp.take(200)}")
        return text
    }

    private fun systemPrompt(now: LocalDateTime, tasks: List<Task>) = """
你是手机提醒 app 的助手，把用户的话和发来的图片转成对提醒列表的操作。
现在时间：${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} ${weekdayName(now.dayOfWeek.value)}
现有提醒：${JSONArray(tasks.map { it.toJson().apply { remove("sound") } })}

只输出一个 JSON 对象，不要输出任何其他文字：
{"reply":"给用户的一句话中文确认","actions":[...]}

action 只有三种：
- 新建 {"op":"create","title":"简短标题","note":"补充说明，没有就空串","time":"HH:mm","date":"yyyy-MM-dd 或 null","days":[],"monthDay":null}
- 修改 {"op":"update","id":现有提醒的id,"title":..,"note":..,"time":..,"date":..,"days":..,"monthDay":..}（写修改后的完整字段）
- 删除 {"op":"delete","id":现有提醒的id}

新建还是修改：
- 默认一律新建。哪怕现有提醒里有同名、同类的，也新建一条，不要动原来的。
- 只有用户明确说了「改」「换成」「推迟」「提前」「挪到」这类意思才用修改；明确说「删」「取消」「不要了」才用删除。

重复方式（四选一）：
- 只响一次：days=[]、monthDay=null、date 必填。只说了时间没说日期：今天这个时间还没过就用今天，已过就用明天。
- 每天：days=[1,2,3,4,5,6,7]；工作日：days=[1,2,3,4,5]；每周几：days 填星期几，1=周一 … 7=周日。date=null、monthDay=null。
- 每月几号：monthDay 填 1-31，days=[]、date=null。

一次还是重复（每件事单独判断）：
- 重复的信号：「每天」「每周」「每月」「工作日」「周末」「每逢」；课程表、值班表、作息表这类按星期排的表格；「第1-16周」「单周/双周」；例会、吃药、打卡、健身、还款日、交房租。→ 按星期用 days，按几号用 monthDay。
- 一次的信号：写了具体几月几号；「今天」「明天」「后天」「下周三」「这周五」；考试、面试、会议通知、活动报名、航班火车票、电影票、预约挂号、快递取件、截止日期。→ 用 date。
- 「下周三开会」是一次；「每周三开会」是每周。同一张图里可以既有重复又有一次（比如课程表加考试通知），分开处理。
- 单双周、「第1-16周」这类只能先按每周建，在 reply 里提醒用户本 app 不支持单双周和结束日期。
- 生日、纪念日这类每年一次的：建成最近一次的那天，在 reply 里说明本 app 不支持每年重复。
- 两种都说得通又看不出来时，按一次建，在 reply 里问一句要不要改成重复。

图片（截图、拍照、课程表、通知、聊天记录、票据等）：
- 先读懂图里每一件要做的事和它的时间，再逐件转成提醒；用户附的话优先（比如只要其中几件、要提前多久）。
- 一件事一条提醒：课程表里每门课每个上课日各是一条（同一门课同一时间的多个星期几可以合成一条，days 填多个）。
- 时间段取开始时间（08:00-09:40 → 08:00）；用户说「提前 N 分钟」就减去 N 分钟。地点、教室、座位、车次、联系人等写进 note。
- 只写了月日没写年份：用最近的将来那个日期。
- 图里已经过去的事、看不清或时间不明确的不要建，也不要猜，在 reply 里说明是哪几件。
- 现有提醒里已经有一模一样的（标题、时间、重复方式都相同）就不要再建，在 reply 里说已经有了。

其他规则：
- 「半小时后」「20分钟后」这类相对时间，从现在时间算出具体的 date 和 time。
- 只说了哪天、没说几点（「每月15号还信用卡」「11月10日截止」）：用 09:00，不要反问，在 reply 里说默认了 9 点、可以改。
- 早上/上午用原数字；下午/晚上的 1-11 点加 12；中午12点=12:00；晚上12点=次日 00:00。
- 修改、删除只能用现有提醒里的 id。找不到对应的提醒就不要输出这个 action，在 reply 里说清楚。
- 一句话里有多件事就输出多个 action。
- 和提醒无关或听不懂时，actions 为空数组，reply 说明原因。
- reply 一两句话说清：建了几条，哪些是重复的、哪些是一次的。
""".trim()

    /** AI 输出是不可信输入:逐条校验,不合格的那条丢弃并在回复里写明原因,不静默吞掉。 */
    fun parse(content: String, tasks: List<Task>, now: LocalDateTime): AiResult {
        val s = content.indexOf('{')
        val e = content.lastIndexOf('}')
        if (s < 0 || e < s) throw JSONException("没有返回 JSON：${content.take(200)}")
        val o = JSONObject(content.substring(s, e + 1))
        val arr = o.optJSONArray("actions") ?: JSONArray()
        val actions = mutableListOf<Action>()
        val problems = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            try {
                val a = arr.getJSONObject(i)
                fun existing() = a.getInt("id").let { id -> tasks.find { it.id == id } ?: error("没有 id=$id 的提醒") }
                actions += when (val op = a.getString("op")) {
                    "create" -> Action.Create(taskFrom(a, 0, null, now))
                    "update" -> existing().let { old -> Action.Update(taskFrom(a, old.id, old, now)) }
                    "delete" -> Action.Delete(existing())
                    else -> error("不认识的操作 $op")
                }
            } catch (ex: Exception) {
                problems += "忽略了一条：${ex.message}"
            }
        }
        val reply = (listOfNotNull(o.optStringOrNull("reply")) + problems).joinToString("\n")
        return AiResult(reply, actions)
    }

    private fun taskFrom(a: JSONObject, id: Int, old: Task?, now: LocalDateTime): Task {
        val monthDay = if (a.isNull("monthDay")) null else a.getInt("monthDay")
        require(monthDay == null || monthDay in 1..31) { "每月几号不对：$monthDay" }
        val days = if (monthDay != null) emptySet() else a.optJSONArray("days")?.let { d -> (0 until d.length()).map(d::getInt).toSet() } ?: emptySet()
        require(days.all { it in 1..7 }) { "星期几不对：$days" }
        val once = monthDay == null && days.isEmpty()
        val t = Task(
            id = id,
            title = (a.optStringOrNull("title") ?: error("缺标题")).trim(),
            note = a.optStringOrNull("note")?.trim() ?: "",
            time = LocalTime.parse(a.getString("time").trim(), HM),
            date = if (once) LocalDate.parse(a.optStringOrNull("date") ?: error("只响一次的提醒缺日期")) else null,
            days = days,
            enabled = true,
            sound = old?.sound,
            monthDay = monthDay,
            push = old?.push,
        )
        if (t.next(now) == null) error("${t.summary()} 时间已经过了")
        return t
    }
}

private const val MAX_SIDE = 2048 // OpenAI 系视觉模型自己也先缩进 2048 见方,再大只是白传

/**
 * 相册图 → 按 EXIF 摆正 → 长边缩到 2048 → 白底 JPEG data URL。
 * 手机拍的照片像素常是横的、靠 EXIF 标方向,不摆正模型读字会差;透明 PNG 直接转 JPEG 会变黑底。
 */
fun imageDataUrl(ctx: Context, uri: Uri): String {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
    val raw = cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: throw IOException("这张图读不出来：$uri")
    val deg = runCatching {
        cr.openInputStream(uri)!!.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
    }.onFailure { Log.w("Ai", "读不到 EXIF 方向,按原样发:$uri", it) }.getOrDefault(0f)
    val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(raw.width, raw.height))
    val m = Matrix().apply { postScale(scale, scale); postRotate(deg) }
    val turned = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    val flat = Bitmap.createBitmap(turned.width, turned.height, Bitmap.Config.ARGB_8888)
    Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(turned, 0f, 0f, null) }
    val buf = ByteArrayOutputStream()
    flat.compress(Bitmap.CompressFormat.JPEG, 85, buf)
    return "data:image/jpeg;base64," + Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
}
