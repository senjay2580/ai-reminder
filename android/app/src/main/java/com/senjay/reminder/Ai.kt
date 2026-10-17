package com.senjay.reminder

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
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
    suspend fun ask(ctx: Context, text: String, tasks: List<Task>): AiResult = withContext(Dispatchers.IO) {
        val base = Prefs.get(ctx, Prefs.AI_BASE).trimEnd('/')
        val key = Prefs.get(ctx, Prefs.AI_KEY)
        if (key.isBlank()) throw IOException("先在设置里填 AI Key")
        val models = Prefs.get(ctx, Prefs.AI_MODELS).split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        if (models.isEmpty()) throw IOException("先在设置里填模型")
        val now = LocalDateTime.now()
        val errors = mutableListOf<String>()
        for (m in models) {
            try {
                return@withContext parse(chat(base, key, m, systemPrompt(now, tasks), text), tasks, now)
            } catch (e: IOException) {
                errors += "$m：${e.message}"
            } catch (e: JSONException) {
                errors += "$m：${e.message}"
            }
            Log.w(TAG, "模型 $m 失败:${errors.last()}")
        }
        throw IOException("AI 都没答上来：\n" + errors.joinToString("\n"))
    }

    private fun chat(base: String, key: String, model: String, system: String, user: String): String {
        val c = URL("$base/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        val body = JSONObject().put("model", model).put("temperature", 0).put(
            "messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
        )
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("HTTP $code ${resp.take(200)}")
        return JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun systemPrompt(now: LocalDateTime, tasks: List<Task>) = """
你是手机提醒 app 的助手，把用户的话转成对提醒列表的操作。
现在时间：${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} ${weekdayName(now.dayOfWeek.value)}
现有提醒：${JSONArray(tasks.map { it.toJson().apply { remove("sound") } })}

只输出一个 JSON 对象，不要输出任何其他文字：
{"reply":"给用户的一句话中文确认","actions":[...]}

action 只有三种：
- 新建 {"op":"create","title":"简短标题","note":"补充说明，没有就空串","time":"HH:mm","date":"yyyy-MM-dd 或 null","days":[]}
- 修改 {"op":"update","id":现有提醒的id,"title":..,"note":..,"time":..,"date":..,"days":..}（写修改后的完整字段）
- 删除 {"op":"delete","id":现有提醒的id}

规则：
- days 是重复的星期几，1=周一 … 7=周日；每天=[1,2,3,4,5,6,7]，工作日=[1,2,3,4,5]。重复提醒 date 填 null。
- 不重复的提醒 days=[]、date 必填。只说了时间没说日期：今天这个时间还没过就用今天，已过就用明天。
- 「半小时后」「20分钟后」这类相对时间，从现在时间算出具体的 date 和 time。
- 早上/上午用原数字；下午/晚上的 1-11 点加 12；中午12点=12:00；晚上12点=次日 00:00。
- 修改、删除只能用现有提醒里的 id。找不到对应的提醒就不要输出这个 action，在 reply 里说清楚。
- 一句话里有多件事就输出多个 action。
- 和提醒无关或听不懂时，actions 为空数组，reply 说明原因。
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
        val days = a.optJSONArray("days")?.let { d -> (0 until d.length()).map(d::getInt).toSet() } ?: emptySet()
        require(days.all { it in 1..7 }) { "星期几不对：$days" }
        val t = Task(
            id = id,
            title = (a.optStringOrNull("title") ?: error("缺标题")).trim(),
            note = a.optStringOrNull("note")?.trim() ?: "",
            time = LocalTime.parse(a.getString("time").trim(), HM),
            date = if (days.isEmpty()) LocalDate.parse(a.optStringOrNull("date") ?: error("只响一次的提醒缺日期")) else null,
            days = days,
            enabled = true,
            sound = old?.sound,
        )
        if (t.next(now) == null) error("${t.summary()} 时间已经过了")
        return t
    }
}
