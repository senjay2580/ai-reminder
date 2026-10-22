package com.senjay.reminder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CortexTest {
    private val now = 1_792_677_000L // epoch 秒,整分
    private fun push(id: Int = 1, kind: String = "alarm", at: Long? = null, acked: Boolean = false) =
        Push(id, kind, "交作业", "带上报告", at, "", acked)

    @Test
    fun parsesServerItem() {
        // 照抄 /api/device/pull 的真实返回:时间是浮点秒,acked 为 null
        val o = JSONObject(
            """{"id": 2, "kind": "alarm", "title": "提醒交作业", "body": "b", "at": 1792910400.0, "url": "",
               "event_id": "efe68c26c1a", "note": "", "created": 1792674012.4967556, "acked": null}""",
        )
        assertEquals(Push(2, "alarm", "提醒交作业", "b", 1_792_910_400L, "", false), Push.fromJson(o))
        val n = Push.fromJson(JSONObject("""{"id": 3, "kind": "notify", "title": "t", "body": null, "at": null, "url": "/#/feed?item=x", "acked": 1792674300.6}"""))
        assertNull(n.at)
        assertEquals("", n.body) // JSON null 不能变成字符串 "null"
        assertTrue(n.acked)
    }

    @Test
    fun dedupeSkipsHandledAndAlreadyAcked() {
        assertEquals(Plan.Skip, plan(push(id = 7, kind = "notify"), done = setOf(7), now = now)) // ack 没送到又被推回来
        assertEquals(Plan.Skip, plan(push(id = 8, at = now + 600, acked = true), done = emptySet(), now = now)) // 重装后从 0 拉回旧的
        assertEquals(Plan.Notify, plan(push(id = 9, kind = "notify"), done = setOf(7), now = now))
        assertEquals(Plan.Notify, plan(push(kind = "weird"), emptySet(), now)) // 不认识的类型弹通知,不吞
    }

    @Test
    fun lateAlarmRule() {
        fun p(at: Long?) = plan(push(at = at), emptySet(), now)
        assertEquals(Plan.Alarm(now + 3600, ringNow = false), p(now + 3600))
        assertEquals(Plan.Alarm(now + 3600, ringNow = false), p(now + 3600 + 59)) // 提醒只存到分钟:截掉秒
        assertEquals(Plan.Alarm(now, ringNow = true), p(now + 30)) // 同一分钟里、截到整分已过 → 马上响,不能建个永远不响的
        assertEquals(Plan.Alarm(now - 540, ringNow = true), p(now - 540)) // 晚 9 分钟
        assertEquals(Plan.Alarm(now - 600, ringNow = true), p(now - 600)) // 正好晚 10 分钟还算
        assertEquals(Plan.Missed(now - 601), p(now - 601)) // 再晚 1 秒:只通知错过
        assertEquals(Plan.Missed(now - 86_400), p(now - 86_400))
        assertEquals(Plan.Alarm(now, ringNow = true), p(null)) // 没带时间当「现在」
    }

    @Test
    fun repeatCodes() {
        val t = Task(1, "a", time = LocalTime.NOON)
        assertEquals("once", t.copy(date = LocalDate.of(2026, 10, 25)).repeatCode())
        assertEquals("daily", t.copy(days = (1..7).toSet()).repeatCode())
        assertEquals("weekly:1,3,7", t.copy(days = setOf(7, 3, 1)).repeatCode()) // ISO:7 = 周日,排好序
        assertEquals("monthly:15", t.copy(monthDay = 15).repeatCode())
    }

    @Test
    fun reminderPayloadIsFullListWithNextTrigger() {
        val n = LocalDateTime.of(2026, 10, 17, 15, 20) // 周六
        val tasks = listOf(
            Task(1, "起床", time = LocalTime.of(7, 0), days = setOf(1, 2, 3, 4, 5)),
            Task(2, "交作业", "带报告", LocalTime.of(15, 0), LocalDate.of(2026, 10, 17), enabled = false, push = 2), // 已响过
        )
        val a = reminderPayload(tasks, n).getJSONArray("reminders")
        assertEquals(2, a.length())
        val w = a.getJSONObject(0)
        assertEquals("1", w.getString("id"))
        assertEquals("weekly:1,2,3,4,5", w.getString("repeat"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 7, 0).toMillis() / 1000, w.getLong("time")) // 下周一
        assertTrue(w.isNull("from_push"))
        val o = a.getJSONObject(1)
        assertEquals(LocalDateTime.of(2026, 10, 17, 15, 0).toMillis() / 1000, o.getLong("time")) // 过期的一次性给原定时间
        assertEquals("once", o.getString("repeat"))
        assertFalse(o.getBoolean("enabled"))
        assertEquals("带报告", o.getString("note"))
        assertEquals(2, o.getInt("from_push"))
    }

    @Test
    fun taskKeepsPushIdThroughJson() {
        val t = Task(5, "交作业", time = LocalTime.of(15, 0), date = LocalDate.of(2026, 10, 25), push = 2)
        assertEquals(t, Task.fromJson(JSONObject(t.toJson().toString())))
        val old = JSONObject(t.copy(push = null).toJson().toString()).apply { remove("push") } // 旧版存的没有这个键
        assertNull(Task.fromJson(old).push)
    }

    @Test
    fun links() {
        assertEquals("https://zk.example/#/feed?item=i1", pushLink("/#/feed?item=i1", "https://zk.example/"))
        assertEquals("https://www.v2ex.com/t/1", pushLink("https://www.v2ex.com/t/1", "https://zk.example"))
        assertNull(pushLink("", "https://zk.example"))
    }

    @Test
    fun backoffDoublesAndCapsAtFiveMinutes() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L), (1..4).map(::backoffMs))
        assertEquals(300_000L, backoffMs(7))
        assertEquals(300_000L, backoffMs(1000))
    }

    @Test
    fun rememberedIdsAreBounded() {
        assertEquals(listOf(1, 2, 3), rememberIds(listOf(1, 2), listOf(2, 3)))
        val many = rememberIds((1..250).toList(), listOf(251))
        assertEquals(200, many.size)
        assertEquals(251, many.last())
        assertFalse(1 in many)
    }
}
