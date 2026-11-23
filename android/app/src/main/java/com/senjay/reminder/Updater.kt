package com.senjay.reminder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 读取 Release 的静态更新文件，下载 APK 交给系统安装器覆盖安装。 */
object Updater {
    // 公开 API 按出口 IP 限制每小时 60 次，手机代理的共享出口容易耗尽；静态 Release 资产没有这项 API 限额。
    private const val MANIFEST = "https://github.com/senjay2580/ai-reminder/releases/latest/download/update.json"

    data class Release(val version: String, val notes: String, val apkUrl: String)

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val c = open(MANIFEST, "application/json")
        try {
            parseRelease(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    fun parseRelease(text: String): Release = JSONObject(text).let { o ->
        Release(o.getString("version").removePrefix("v"), o.optStringOrNull("notes") ?: "", o.getString("apkUrl"))
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    suspend fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val c = open(r.apkUrl, "application/octet-stream")
        val total = c.contentLengthLong
        val f = File(ctx.cacheDir, "update.apk")
        c.inputStream.use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0 && (done * 100 / total).toInt() != last) {
                        last = (done * 100 / total).toInt()
                        onProgress(last)
                    }
                }
            }
        }
        if (total > 0 && f.length() != total) throw IOException("下载不完整：${f.length()}/$total 字节")
        f
    }

    /** 返回 false = 还没允许「安装未知应用」,已跳到系统设置页。 */
    fun install(ctx: Context, f: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    // 下载地址会 302 到 release-assets.githubusercontent.com,HttpURLConnection 默认会跟随
    private fun open(url: String, accept: String): HttpURLConnection {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "AiReminder/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (c.responseCode !in 200..299) {
                val text = c.errorStream?.bufferedReader()?.use { it.readText().take(200) }.orEmpty()
                throw IOException("HTTP ${c.responseCode} $text")
            }
            return c
        } catch (e: Exception) {
            c.disconnect()
            throw e
        }
    }
}
