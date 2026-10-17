package com.senjay.reminder

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val WEEK = "一二三四五六日"

/** days 为空 = 只响一次(看 date);否则按星期几重复,1=周一 … 7=周日。 */
data class Task(
    val id: Int,
    val title: String,
    val note: String = "",
    val time: LocalTime,
    val date: LocalDate? = null,
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val sound: String? = null, // 铃声 uri;null = 用设置里的默认铃声
) {
    fun next(now: LocalDateTime): LocalDateTime? {
        if (days.isEmpty()) return date?.atTime(time)?.takeIf { it.isAfter(now) }
        return (0..7L).asSequence()
            .map { now.toLocalDate().plusDays(it).atTime(time) }
            .firstOrNull { it.isAfter(now) && it.dayOfWeek.value in days }
    }

    fun repeatText(): String = when {
        days.isEmpty() -> date?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: "未设日期"
        days.size == 7 -> "每天"
        days == setOf(1, 2, 3, 4, 5) -> "工作日"
        days == setOf(6, 7) -> "周末"
        else -> days.sorted().joinToString("、", prefix = "每周") { WEEK[it - 1].toString() }
    }

    fun summary() = "${time.format(HHMM)} ${repeatText()} $title" + if (note.isBlank()) "" else "（$note）"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("note", note)
        .put("time", time.format(HHMM))
        .put("date", date?.toString() ?: JSONObject.NULL)
        .put("days", JSONArray(days.sorted()))
        .put("enabled", enabled)
        .put("sound", sound ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Task(
            id = o.getInt("id"),
            title = o.getString("title"),
            note = o.optStringOrNull("note") ?: "",
            time = LocalTime.parse(o.getString("time")),
            date = o.optStringOrNull("date")?.let(LocalDate::parse),
            days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map(a::getInt).toSet() } ?: emptySet(),
            enabled = o.optBoolean("enabled", true),
            sound = o.optStringOrNull("sound"),
        )
    }
}

fun nextId(tasks: List<Task>) = (tasks.maxOfOrNull { it.id } ?: 0) + 1

fun weekdayName(d: Int) = "星期" + WEEK[d - 1]

/** org.json 的 optString 遇到 JSON null 会返回字符串 "null",这里统一成 Kotlin null。 */
fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).ifBlank { null }
