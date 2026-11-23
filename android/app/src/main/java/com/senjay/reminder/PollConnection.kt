package com.senjay.reminder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection

/** 协调收信请求与网络事件，关闭旧请求并唤醒重试等待。 */
class PollConnection {
    private var active: HttpURLConnection? = null
    private var stopped = false
    private val changes = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun opened(connection: HttpURLConnection) {
        if (stopped) {
            connection.disconnect()
            throw CancellationException("收信服务已停止")
        }
        active = connection
    }

    @Synchronized
    fun finished(connection: HttpURLConnection) {
        if (active === connection) active = null
    }

    fun reconnect() {
        val old = synchronized(this) { active.also { active = null } }
        changes.trySend(Unit)
        old?.disconnect()
    }

    fun changed() = changes.tryReceive().isSuccess

    suspend fun pause(ms: Long): Boolean = withTimeoutOrNull(ms) {
        changes.receive()
        true
    } ?: false

    fun close() {
        val old = synchronized(this) {
            stopped = true
            active.also { active = null }
        }
        old?.disconnect()
        changes.close()
    }
}
