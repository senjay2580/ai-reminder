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

/** 查公开仓库最新 Release(免 token),下载 APK 交给系统安装器覆盖安装(同签名所以数据保留)。 */
object Updater {
    private const val API = "https://api.github.com/repos/senjay2580/ai-reminder/releases/latest"

    data class Release(val version: String, val notes: String, val apkUrl: String)

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val o = JSONObject(open(API, "application/vnd.github+json").inputStream.bufferedReader().use { it.readText() })
        val assets = o.getJSONArray("assets")
        val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name").endsWith(".apk") }
            ?: throw IOException("最新 Release 里没有 APK")
        Release(o.getString("tag_name").removePrefix("v"), o.optString("body"), apk.getString("browser_download_url"))
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
        }
        if (c.responseCode !in 200..299) {
            // 未登录访问有每小时 60 次的限额,超了会回 403
            throw IOException("HTTP ${c.responseCode} ${c.errorStream?.bufferedReader()?.use { it.readText().take(200) }}")
        }
        return c
    }
}
