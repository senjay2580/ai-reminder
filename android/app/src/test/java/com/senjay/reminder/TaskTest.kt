package com.senjay.reminder

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
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
    fun versionCompare() {
        assertTrue(Updater.isNewer("1.0.10", "1.0.9"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("0.9.9", "1.0.0"))
    }
}
