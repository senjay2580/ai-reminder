package com.senjay.reminder

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
import java.time.ZoneId

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
        val e = Push.fromJson(JSONObject("""{"id": 9, "kind": "reminder_delete", "title": "t", "body": "", "at": null, "url": "", "acked": null, "data": "{\"id\": \"cx1\"}"}"""))
        assertEquals("""{"id": "cx1"}""", e.data)
    }

    @Test
    fun dedupeSkipsHandledAndAlreadyAcked() {
        assertEquals(Plan.Skip, plan(push(id = 7, kind = "notify"), done = setOf(7), now = now)) // ack 没送到又被推回来
        assertEquals(Plan.Skip, plan(push(id = 8, at = now + 600, acked = true), done = emptySet(), now = now)) // 重装后从 0 拉回旧的
        assertEquals(Plan.Notify, plan(push(id = 9, kind = "notify"), done = setOf(7), now = now))
        assertEquals(Plan.Skip, plan(push(kind = "weird"), emptySet(), now)) // 不认识的类型:ack 掉,不卡住后面的
        assertEquals(Plan.Upsert, plan(push(kind = "reminder_upsert"), emptySet(), now))
        assertEquals(Plan.Delete, plan(push(kind = "reminder_delete"), emptySet(), now))
        assertEquals(Plan.Skip, plan(push(id = 7, kind = "reminder_upsert"), setOf(7), now))
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

    // ---------- 网页改手机提醒 ----------
    private val zone = ZoneId.systemDefault()
    private fun epoch(t: LocalDateTime) = t.atZone(zone).toEpochSecond()
    private fun edit(id: String, title: String, at: LocalDateTime, repeat: String, enabled: Boolean = true, note: String = "") =
        JSONObject().put("id", id).put("title", title).put("time", epoch(at).toDouble()).put("repeat", repeat).put("enabled", enabled).put("note", note)

    private val mine = listOf(
        Task(1, "吃药", time = LocalTime.of(8, 0), days = (1..7).toSet()),
        Task(3, "起床", time = LocalTime.of(7, 0), days = setOf(1, 2, 3, 4, 5), sound = "s", push = 9),
    )

    @Test
    fun upsertUpdatesInPlaceKeepingSoundAndSource() {
        val after = mine.upsert(edit("3", "早起", LocalDateTime.of(2026, 10, 20, 6, 30), "weekly:6,7", note = "n"), zone)
        assertEquals(listOf(1, 3), after.map { it.id }) // 没多也没少
        val t = after[1]
        assertEquals("早起", t.title)
        assertEquals(LocalTime.of(6, 30), t.time)
        assertEquals(setOf(6, 7), t.days)
        assertNull(t.date)
        assertEquals("n", t.note)
        assertEquals("s", t.sound) // 网页改不丢本机选的铃声
        assertEquals(9, t.push)
        assertNull(t.cid)
        assertEquals("3", t.key)
    }

    @Test
    fun upsertCreatesWithWebIdAndKeepsMapping() {
        val created = mine.upsert(edit("cxab12", "开会", LocalDateTime.of(2026, 10, 25, 9, 0), "once"), zone)
        assertEquals(3, created.size)
        val t = created.last()
        assertEquals(4, t.id) // 本机 id 照常往后排
        assertEquals("cxab12", t.cid)
        assertEquals("cxab12", t.key)
        assertEquals(LocalDate.of(2026, 10, 25), t.date)
        assertEquals(LocalTime.of(9, 0), t.time)
        assertTrue(t.days.isEmpty() && t.monthDay == null)
        // 存盘再读回来,同一个网页 id 还指向同一条
        val reloaded = created.map { Task.fromJson(JSONObject(it.toJson().toString())) }
        val again = reloaded.upsert(edit("cxab12", "开会改时间", LocalDateTime.of(2026, 10, 25, 10, 0), "monthly:25", enabled = false), zone)
        assertEquals(3, again.size)
        val u = again.single { it.key == "cxab12" }
        assertEquals(4, u.id)
        assertEquals(25, u.monthDay)
        assertNull(u.date)
        assertFalse(u.enabled)
        // 网页上改的是本机已经删掉的数字 id:按这个 id 重建
        val revived = mine.upsert(edit("7", "喝水", LocalDateTime.of(2026, 10, 25, 15, 0), "daily"), zone).last()
        assertEquals(7, revived.id)
        assertNull(revived.cid)
        assertEquals((1..7).toSet(), revived.days)
    }

    @Test
    fun upsertRejectsBadContent() {
        val at = LocalDateTime.of(2026, 10, 25, 9, 0)
        assertThrows(IllegalArgumentException::class.java) { mine.upsert(edit("cx1", "a", at, "yearly"), zone) }
        assertThrows(IllegalArgumentException::class.java) { mine.upsert(edit("cx1", "a", at, "weekly:0"), zone) }
        assertThrows(IllegalArgumentException::class.java) { mine.upsert(edit("cx1", "a", at, "monthly:32"), zone) }
        assertThrows(IllegalArgumentException::class.java) { mine.upsert(edit("cx1", " ", at, "once"), zone) }
        assertThrows(IllegalArgumentException::class.java) { mine.upsert(edit("", "a", at, "once"), zone) }
    }

    @Test
    fun deleteByKey() {
        val web = mine + Task(4, "开会", time = LocalTime.of(9, 0), date = LocalDate.of(2026, 10, 25), cid = "cxab12")
        assertEquals(listOf(1, 4), web.removeKey("3").map { it.id })
        assertEquals(listOf(1, 3), web.removeKey("cxab12").map { it.id })
        assertEquals(web, web.removeKey("cx-gone")) // 本机已经没有了:原样不动
        assertEquals(web, web.removeKey("4")) // 网页建的那条只认 cx id,不会被数字 id 误删
    }

    @Test
    fun uploadUsesWebIdForWebCreated() {
        val t = Task(4, "开会", time = LocalTime.of(9, 0), date = LocalDate.of(2026, 10, 25), cid = "cxab12")
        assertEquals(t, Task.fromJson(JSONObject(t.toJson().toString())))
        val a = reminderPayload(listOf(t), LocalDateTime.of(2026, 10, 17, 15, 20)).getJSONArray("reminders")
        assertEquals("cxab12", a.getJSONObject(0).getString("id"))
    }

    @Test
    fun pairLink() {
        // 照抄网页拼的链接:server 用 encodeURIComponent 编过
        assertEquals(
            "https://cortex.104.238.160.199.nip.io" to "a-b_c",
            parsePairLink("aireminder://cortex?server=https%3A%2F%2Fcortex.104.238.160.199.nip.io&token=a-b_c"),
        )
        assertEquals("https://x.io" to "t", parsePairLink("aireminder://cortex?token=t&server=https%3A%2F%2Fx.io%2F"))
        assertThrows(IllegalArgumentException::class.java) { parsePairLink("aireminder://cortex?server=http%3A%2F%2Fx.io&token=t") }
        assertThrows(IllegalArgumentException::class.java) { parsePairLink("aireminder://cortex?server=https%3A%2F%2Fx.io&token=") }
        assertThrows(IllegalArgumentException::class.java) { parsePairLink("aireminder://other?server=https%3A%2F%2Fx.io&token=t") }
        assertThrows(IllegalArgumentException::class.java) { parsePairLink("aireminder://cortex?server=https%3A%2F%2Fx.io&token=%ZZ") }
    }

    @Test
    fun serverErrorShowsServersMessage() {
        assertEquals("用户名或密码错误（HTTP 401）", serverError(401, """{"error": "用户名或密码错误"}"""))
        assertEquals("HTTP 502 <html>bad gateway</html>", serverError(502, "<html>bad gateway</html>"))
        assertEquals("HTTP 500 ", serverError(500, ""))
    }
}
