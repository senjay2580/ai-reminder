package com.senjay.reminder

import org.json.JSONObject

const val INBOX_KEEP_MS = 30 * 24 * 60 * 60_000L

/** 来源地址和推送 id 一起判重，切换 Cortex 服务器也不会串消息。 */
data class InboxMessage(
    val id: Int,
    val source: String,
    val title: String,
    val body: String,
    val url: String,
    val created: Long,
    val read: Boolean = false,
    val deleted: Boolean = false,
) {
    val key get() = "$source#$id"
    fun expired(now: Long) = created <= now - INBOX_KEEP_MS

    /** 删除正文，只留判重信息，防止服务端重投后又出现在信箱。 */
    fun delete() = copy(title = "", body = "", url = "", read = true, deleted = true)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("source", source).put("title", title).put("body", body)
        .put("url", url).put("created", created).put("read", read).put("deleted", deleted)

    companion object {
        fun fromJson(o: JSONObject) = InboxMessage(
            o.getInt("id"), o.getString("source"), o.getString("title"), o.getString("body"),
            o.getString("url"), o.getLong("created"), o.optBoolean("read"), o.optBoolean("deleted"),
        )

        fun fromPush(p: Push, source: String, now: Long) = InboxMessage(
            p.id, source.trimEnd('/'), p.title.ifBlank { "Cortex 通知" }, p.body, p.url,
            p.created?.times(1000) ?: now,
        )
    }
}
