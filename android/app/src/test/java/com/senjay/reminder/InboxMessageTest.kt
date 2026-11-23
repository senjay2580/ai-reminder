package com.senjay.reminder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxMessageTest {
    private val now = 1_795_400_000_000L
    private val message = InboxMessage(17, "https://cortex.example", "今日资讯", "正文\n第二行", "/#/feed", now)

    @Test
    fun retentionDeletesAtThirtyDaysIncludingUnread() {
        assertFalse(message.expired(now + INBOX_KEEP_MS - 1))
        assertTrue(message.expired(now + INBOX_KEEP_MS))
        assertTrue(message.expired(now + INBOX_KEEP_MS + 1))
        assertFalse(message.read)
        assertTrue(message.copy(read = true).expired(now + INBOX_KEEP_MS))
    }

    @Test
    fun deleteClearsContentButKeepsIdentityUntilExpiry() {
        val deleted = message.delete()
        assertTrue(deleted.deleted)
        assertTrue(deleted.read)
        assertEquals("", deleted.title)
        assertEquals("", deleted.body)
        assertEquals("", deleted.url)
        assertEquals(message.key, deleted.key)
        assertEquals(message.created, deleted.created)
        assertEquals(deleted, deleted.delete())
    }

    @Test
    fun jsonKeepsReadDeletedAndFullMultilineText() {
        for (m in listOf(message, message.copy(read = true), message.delete())) {
            assertEquals(m, InboxMessage.fromJson(JSONObject(m.toJson().toString())))
        }
    }

    @Test
    fun pushUsesServerCreationTimeAndResolvesLinksAgainstItsSource() {
        val p = Push.fromJson(JSONObject("""{"id":17,"kind":"notify","title":"今日资讯","body":"正文","at":null,"url":"/#/feed","acked":null,"created":1795400000.9}"""))
        val m = InboxMessage.fromPush(p, "https://cortex.example/", now + 60_000)
        assertEquals(now, m.created)
        assertEquals(message.key, m.key)
        assertEquals("https://cortex.example/#/feed", pushLink(m.url, m.source))
        assertFalse(m.read)
        assertFalse(m.deleted)
    }

    @Test
    fun legacyPushWithoutTimestampUsesReceiptTime() {
        val p = Push(17, "notify", "", "正文", null, "", false)
        val m = InboxMessage.fromPush(p, "https://cortex.example", now)
        assertEquals(now, m.created)
        assertEquals("Cortex 通知", m.title)
    }

    @Test
    fun samePushIdOnDifferentServersHasDifferentIdentity() {
        assertNotEquals(message.key, message.copy(source = "https://other.example").key)
    }
}
