package com.senjay.reminder

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val WEEK = "一二三四五六日"

/**
 * 重复方式四选一:monthDay 非空 = 每月几号;否则 days 非空 = 按星期几(7 天全选即每天);
 * 都空 = 只响一次(看 date)。
 */
data class Task(
    val id: Int,
    val title: String,
    val note: String = "",
    val time: LocalTime,
    val date: LocalDate? = null,
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val sound: String? = null, // 铃声 uri;null = 用设置里的默认铃声
    val monthDay: Int? = null, // 每月几号;小月没有这天就在月末那天响
) {
    val once get() = monthDay == null && days.isEmpty()

    val kind get() = when {
        monthDay != null -> "每月"
        days.isEmpty() -> "一次"
        days.size == 7 -> "每天"
        else -> "每周"
    }

    fun next(now: LocalDateTime): LocalDateTime? = when {
        monthDay != null -> (0..12L).asSequence()
            .map { YearMonth.from(now).plusMonths(it) }
            .map { it.atDay(minOf(monthDay, it.lengthOfMonth())).atTime(time) }
            .firstOrNull { it.isAfter(now) }
        days.isEmpty() -> date?.atTime(time)?.takeIf { it.isAfter(now) }
        else -> (0..7L).asSequence()
            .map { now.toLocalDate().plusDays(it).atTime(time) }
            .firstOrNull { it.isAfter(now) && it.dayOfWeek.value in days }
    }

    /** 接下来 n 次响铃时间(详情页展示用)。 */
    fun upcoming(now: LocalDateTime, n: Int): List<LocalDateTime> = generateSequence(next(now)) { next(it) }.take(n).toList()

    fun repeatText(): String = when {
        monthDay != null -> "每月${monthDay}日"
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
        .put("monthDay", monthDay ?: JSONObject.NULL)
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
            monthDay = if (o.isNull("monthDay")) null else o.getInt("monthDay"), // 旧数据没有这个键 → 不是每月
        )
    }
}

fun nextId(tasks: List<Task>) = (tasks.maxOfOrNull { it.id } ?: 0) + 1

fun weekdayName(d: Int) = "星期" + WEEK[d - 1]

fun LocalDateTime.toMillis() = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** 「今天 08:00」「明天 08:00」「10月20日 周二 08:00」 */
fun whenText(at: LocalDateTime, now: LocalDateTime): String {
    val day = when (at.toLocalDate()) {
        now.toLocalDate() -> "今天"
        now.toLocalDate().plusDays(1) -> "明天"
        now.toLocalDate().plusDays(2) -> "后天"
        else -> "${at.monthValue}月${at.dayOfMonth}日 周${WEEK[at.dayOfWeek.value - 1]}"
    }
    return "$day ${at.format(HHMM)}"
}

/** 「3天4小时后」「2小时5分钟后」「8分钟后」 */
fun untilText(at: LocalDateTime, now: LocalDateTime): String {
    val m = Duration.between(now, at).toMinutes()
    return when {
        m < 1 -> "马上"
        m < 60 -> "${m}分钟后"
        m < 24 * 60 -> "${m / 60}小时" + (if (m % 60 > 0) "${m % 60}分钟" else "") + "后"
        else -> "${m / 1440}天" + (if (m % 1440 / 60 > 0) "${m % 1440 / 60}小时" else "") + "后"
    }
}

/** org.json 的 optString 遇到 JSON null 会返回字符串 "null",这里统一成 Kotlin null。 */
fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).ifBlank { null }
