package com.senjay.reminder

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class TaskTest {
    private val now = LocalDateTime.of(2026, 10, 17, 15, 20) // 周六
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m)

    @Test
    fun onceFiresOnlyIfInFuture() {
        val t = Task(1, "a", time = LocalTime.of(16, 0), date = LocalDate.of(2026, 10, 17))
        assertEquals(at(17, 16), t.next(now))
        assertNull(t.copy(time = LocalTime.of(15, 0)).next(now))
        assertNull(t.copy(time = LocalTime.of(15, 20)).next(now)) // 正好此刻不算未来
    }

    @Test
    fun repeatingPicksNextMatchingDay() {
        val all = (1..7).toSet()
        assertEquals(at(17, 16), Task(1, "a", time = LocalTime.of(16, 0), days = all).next(now))
        assertEquals(at(18, 15), Task(1, "a", time = LocalTime.of(15, 0), days = all).next(now))
        assertEquals(at(20, 20), Task(1, "健身", time = LocalTime.of(20, 0), days = setOf(2, 4)).next(now)) // 下周二
        assertEquals(at(24, 15), Task(1, "a", time = LocalTime.of(15, 0), days = setOf(6)).next(now)) // 本周六已过 → 下周六
    }

    @Test
    fun repeatText() {
        val t = Task(1, "a", time = LocalTime.NOON)
        assertEquals("工作日", t.copy(days = setOf(1, 2, 3, 4, 5)).repeatText())
        assertEquals("每周二、四", t.copy(days = setOf(4, 2)).repeatText())
        assertEquals("10月18日", t.copy(date = LocalDate.of(2026, 10, 18)).repeatText())
    }

    @Test
    fun jsonRoundTrip() {
        val t = Task(3, "x", "n", LocalTime.of(7, 30), null, setOf(1, 2, 3, 4, 5), false, "content://a")
        assertEquals(t, Task.fromJson(JSONObject(t.toJson().toString())))
        val once = Task(4, "y", time = LocalTime.of(8, 0), date = LocalDate.of(2026, 10, 18))
        assertEquals(once, Task.fromJson(JSONObject(once.toJson().toString())))
    }

    @Test
    fun parseValidatesEachActionAndKeepsGoodOnes() {
        val tasks = listOf(
            Task(1, "开会", time = LocalTime.of(9, 0), date = LocalDate.of(2026, 10, 18)),
            Task(2, "起床", time = LocalTime.of(7, 0), days = setOf(1, 2, 3, 4, 5), sound = "s"),
        )
        val content = """```json
{"reply":"好的","actions":[
 {"op":"create","title":"关火","note":null,"time":"15:50","date":"2026-10-17","days":[]},
 {"op":"update","id":2,"title":"起床","note":"","time":"7:30","date":null,"days":[1,2,3,4,5]},
 {"op":"delete","id":1},
 {"op":"delete","id":9},
 {"op":"create","title":"过去的","note":"","time":"08:00","date":"2026-10-17","days":[]},
 {"op":"create","title":"坏星期","note":"","time":"08:00","date":null,"days":[0]}
]}
```"""
        val r = Ai.parse(content, tasks, now)
        assertEquals(3, r.actions.size)
        val created = (r.actions[0] as Action.Create).task
        assertEquals("", created.note) // JSON null 不能变成字符串 "null"
        assertEquals(LocalTime.of(15, 50), created.time)
        val updated = (r.actions[1] as Action.Update).task
        assertEquals(LocalTime.of(7, 30), updated.time) // "7:30" 也能解析
        assertEquals("s", updated.sound) // 修改不丢用户选的铃声
        assertEquals(4, r.reply.lines().size) // 回复 + 三条「忽略了一条」
        assertTrue(r.reply.lines().drop(1).all { it.startsWith("忽略了一条") })

        val after = tasks.applyActions(r.actions)
        assertEquals(listOf(2, 3), after.map { it.id }) // 删 1、新建拿到 3
        assertEquals("关火", after.last().title)
    }

    @Test
    fun parseRejectsNonJson() {
        assertThrows(JSONException::class.java) { Ai.parse("抱歉我没听懂", emptyList(), now) }
    }

    @Test
    fun monthlyFallsBackToMonthEnd() {
        val t = Task(1, "还款", time = LocalTime.of(9, 0), monthDay = 15)
        assertEquals(LocalDateTime.of(2026, 11, 15, 9, 0), t.next(now)) // 本月 15 号已过
        assertEquals(at(17, 16), t.copy(monthDay = 17, time = LocalTime.of(16, 0)).next(now)) // 今天还没到点
        val end = t.copy(monthDay = 31)
        assertEquals(
            listOf(at(31, 9), LocalDateTime.of(2026, 11, 30, 9, 0), LocalDateTime.of(2026, 12, 31, 9, 0)), // 11 月没有 31 号 → 30 号
            end.upcoming(now, 3),
        )
        assertEquals("每月", end.kind)
        assertFalse(end.once) // 响过不能被当成一次性关掉
        assertEquals(end, Task.fromJson(JSONObject(end.toJson().toString())))
    }

    @Test
    fun parseMonthly() {
        val content = """{"reply":"好","actions":[
 {"op":"create","title":"还信用卡","note":"","time":"09:00","date":null,"days":[],"monthDay":15},
 {"op":"create","title":"坏日子","note":"","time":"09:00","date":null,"days":[],"monthDay":40}
]}"""
        val r = Ai.parse(content, emptyList(), now)
        val t = (r.actions.single() as Action.Create).task
        assertEquals(15, t.monthDay)
        assertTrue(t.days.isEmpty() && t.date == null)
        assertTrue(r.reply.contains("忽略了一条"))
    }

    @Test
    fun relativeTexts() {
        assertEquals("今天 16:00", whenText(at(17, 16), now))
        assertEquals("明天 08:00", whenText(at(18, 8), now))
        assertEquals("10月20日 周二 20:00", whenText(at(20, 20), now))
        assertEquals("40分钟后", untilText(at(17, 16), now))
        assertEquals("16小时40分钟后", untilText(at(18, 8), now))
        assertEquals("3天4小时后", untilText(at(20, 19, 20), now))
    }

    @Test
    fun calendarListsWhatRingsThatDay() {
        val today = now.toLocalDate() // 10-17 周六;10-19、11-30 是周一,11-5 是周四
        val exam = Task(1, "考试", time = LocalTime.of(9, 0), date = LocalDate.of(2026, 11, 5), enabled = false)
        val math = Task(2, "高数", time = LocalTime.of(8, 0), days = setOf(1))
        val rent = Task(3, "还款", time = LocalTime.of(10, 0), monthDay = 31)
        val off = math.copy(id = 4, enabled = false)
        val all = listOf(exam, rent, math, off)
        fun ids(m: Int, d: Int) = all.on(LocalDate.of(2026, m, d), today).map { it.id }
        assertEquals(listOf(2), ids(10, 19)) // 关掉的重复提醒不列
        assertEquals(emptyList<Int>(), ids(10, 12)) // 过去的周一不倒推重复提醒
        assertEquals(listOf(1), ids(11, 5)) // 一次性的关掉了(响过)也留在那天
        assertEquals(listOf(2, 3), ids(11, 30)) // 11 月没有 31 号 → 30 号;同一天按时间排
    }

    @Test
    fun responsesBodyCarriesImagesAndHighEffort() {
        val b = Ai.body("gpt-6.1-sol", "sys", "看图", listOf("data:image/jpeg;base64,AAA", "data:image/jpeg;base64,BBB"))
        assertEquals("sys", b.getString("instructions"))
        assertEquals("high", b.getJSONObject("reasoning").getString("effort")) // agentrouter 只认 high
        val content = b.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals(listOf("input_text", "input_image", "input_image"), (0 until content.length()).map { content.getJSONObject(it).getString("type") })
        assertEquals("data:image/jpeg;base64,BBB", content.getJSONObject(2).getString("image_url"))
    }

    @Test
    fun responsesOutputSkipsReasoning() {
        val resp = """{"output":[{"type":"reasoning","summary":[]},
 {"type":"message","content":[{"type":"output_text","text":"{\"reply\":"},{"type":"output_text","text":"\"好\",\"actions\":[]}"}]}]}"""
        assertEquals("""{"reply":"好","actions":[]}""", Ai.outputText(resp))
        assertThrows(IOException::class.java) { Ai.outputText("""{"output":[{"type":"reasoning","summary":[]}]}""") }
    }

    @Test
    fun missedMeansExpectedTimeLongPast() {
        val a = Task(1, "a", time = LocalTime.NOON, days = setOf(1))
        val tasks = listOf(a, a.copy(id = 2), a.copy(id = 3, enabled = false), a.copy(id = 4))
        val expected = mapOf(1 to 1_000L, 2 to 100_000L, 3 to 1_000L) // 4 没有记录:旧版本装上来、还没排过
        // 1 过点将近 2 分钟没响;2 过点不到 1 分钟,可能正在响;3 已关掉
        assertEquals(listOf(1), Alarms.missed(tasks, expected::get, 120_000L).map { it.id })
    }

    @Test
    fun versionCompare() {
        assertTrue(Updater.isNewer("1.0.10", "1.0.9"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("0.9.9", "1.0.0"))
    }
}
