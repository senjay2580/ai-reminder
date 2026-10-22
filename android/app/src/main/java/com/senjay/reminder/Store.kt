package com.senjay.reminder

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import java.io.File

// ponytail: 提醒就几十条,整表一个 JSON 文件;上千条或要查询时再换 Room
object Store {
    /** 每次写入 +1。后台(Cortex 推来的闹钟、响过的一次性提醒)改了列表,界面看着它重读。 */
    val rev = mutableIntStateOf(0)

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "tasks.json"))

    fun load(ctx: Context): List<Task> {
        val f = file(ctx)
        if (!f.baseFile.exists()) return emptyList()
        val a = JSONArray(String(f.readFully()))
        return (0 until a.length()).map { Task.fromJson(a.getJSONObject(it)) }
    }

    /** 保存并同步系统闹钟。所有改动都走这里,系统闹钟才不会和列表对不上。 */
    fun put(ctx: Context, tasks: List<Task>) {
        val gone = load(ctx).map { it.id } - tasks.map { it.id }.toSet()
        val f = file(ctx)
        val out = f.startWrite()
        try {
            out.write(JSONArray(tasks.map { it.toJson() }).toString().toByteArray())
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
        gone.forEach { Alarms.cancel(ctx, it) }
        tasks.forEach { Alarms.schedule(ctx, it) }
        rev.intValue++
        Cortex.changed(ctx)
    }
}

object Prefs {
    const val AI_BASE = "ai_base"
    const val AI_KEY = "ai_key"
    const val AI_MODELS = "ai_models"
    const val SOUND = "sound"
    const val CORTEX_ON = "cortex_on" // "1" = 开
    const val CORTEX_BASE = "cortex_base"
    const val CORTEX_TOKEN = "cortex_token"

    private val DEFAULTS = mapOf(
        // VPS 上的 arelay(agentrouter 多 key 网关):Caddy 只放行 /v1/responses,且要带专用令牌
        AI_BASE to "https://arelay.104.238.160.199.nip.io/v1",
        AI_MODELS to "gpt-6.1-sol,gpt-6-astra",
        CORTEX_BASE to "https://cortex.104.238.160.199.nip.io",
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    fun get(ctx: Context, k: String): String = sp(ctx).getString(k, null) ?: DEFAULTS[k] ?: ""
    fun set(ctx: Context, k: String, v: String) = sp(ctx).edit().putString(k, v.trim()).apply()
}
