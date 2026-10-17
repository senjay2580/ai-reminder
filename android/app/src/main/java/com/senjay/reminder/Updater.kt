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

/** 查私有仓库最新 Release,下载 APK 交给系统安装器覆盖安装(同签名所以数据保留)。 */
object Updater {
    private const val API = "https://api.github.com/repos/senjay2580/ai-reminder/releases/latest"

    data class Release(val version: String, val notes: String, val apkUrl: String)

    suspend fun latest(ctx: Context): Release = withContext(Dispatchers.IO) {
        val o = JSONObject(open(ctx, API, "application/vnd.github+json").inputStream.bufferedReader().use { it.readText() })
        val assets = o.getJSONArray("assets")
        val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name").endsWith(".apk") }
            ?: throw IOException("最新 Release 里没有 APK")
        // 私有仓库只能用资产的 API 地址下载,browser_download_url 会 404
        Release(o.getString("tag_name").removePrefix("v"), o.optString("body"), apk.getString("url"))
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
        val c = open(ctx, r.apkUrl, "application/octet-stream")
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

    /** 资产地址会 302 到 S3 签名地址;S3 见到 token 会拒绝,所以手动跟随跳转、第二跳不带 token。 */
    private fun open(ctx: Context, url: String, accept: String): HttpURLConnection {
        val token = Prefs.get(ctx, Prefs.GH_TOKEN)
        if (token.isBlank()) throw IOException("先在设置里填 GitHub Token")
        var c = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", accept)
            setRequestProperty("Authorization", "Bearer $token")
        }
        if (c.responseCode in 300..399) {
            val loc = c.getHeaderField("Location") ?: throw IOException("跳转缺 Location")
            c.disconnect()
            c = (URL(loc).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
        }
        if (c.responseCode !in 200..299) {
            throw IOException("HTTP ${c.responseCode} ${c.errorStream?.bufferedReader()?.use { it.readText().take(200) }}")
        }
        return c
    }
}
