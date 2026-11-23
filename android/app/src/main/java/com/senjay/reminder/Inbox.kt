package com.senjay.reminder

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.mutableIntStateOf
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** 信箱独立于提醒表。所有读改写加锁并原子落盘，后台收信和页面管理不会互相覆盖。 */
object Inbox {
    const val OPEN = "com.senjay.reminder.OPEN_INBOX"
    const val KEY = "inbox_key"
    val rev = mutableIntStateOf(0)

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "inbox.json"))

    private fun all(ctx: Context): List<InboxMessage> {
        val f = file(ctx)
        if (!f.baseFile.exists()) return emptyList()
        val a = JSONArray(String(f.readFully(), Charsets.UTF_8))
        return (0 until a.length()).map { InboxMessage.fromJson(a.getJSONObject(it)) }
    }

    private fun save(ctx: Context, messages: List<InboxMessage>) {
        val f = file(ctx)
        val out = f.startWrite()
        try {
            out.write(JSONArray(messages.map { it.toJson() }).toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
        rev.intValue++
    }

    @Synchronized
    fun clean(ctx: Context, now: Long = System.currentTimeMillis()) {
        val old = all(ctx)
        val kept = old.filterNot { it.expired(now) }
        if (old.size != kept.size) {
            save(ctx, kept)
            old.filter { it.expired(now) }.forEach { Notices.cancelCortex(ctx, it) }
        }
    }

    @Synchronized
    fun load(ctx: Context): List<InboxMessage> {
        clean(ctx)
        return all(ctx).filterNot { it.deleted }.sortedByDescending { it.created }
    }

    /** 先保存再弹系统通知。重复投递保留原有已读/删除状态，已过期消息不再收录。 */
    @Synchronized
    fun receive(ctx: Context, p: Push): Boolean {
        val now = System.currentTimeMillis()
        clean(ctx, now)
        val message = InboxMessage.fromPush(p, Cortex.base(ctx), now)
        val old = all(ctx)
        if (message.expired(now) || old.any { it.key == message.key }) return false
        save(ctx, old + message)
        return true
    }

    @Synchronized
    fun markRead(ctx: Context, keys: Set<String>, read: Boolean = true) {
        val old = all(ctx)
        val updated = old.map { if (it.key in keys && !it.deleted) it.copy(read = read) else it }
        if (updated != old) save(ctx, updated)
    }

    @Synchronized
    fun delete(ctx: Context, keys: Set<String>) {
        val old = all(ctx)
        val updated = old.map { if (it.key in keys) it.delete() else it }
        if (updated != old) {
            save(ctx, updated)
            old.filter { it.key in keys }.forEach { Notices.cancelCortex(ctx, it) }
        }
    }

    /** 独立离线清理任务，关闭 Cortex 联动后仍然清理信箱。 */
    fun start(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "inbox-clean", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<InboxCleanupWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}

class InboxCleanupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Inbox.clean(applicationContext)
        Result.success()
    }
}
