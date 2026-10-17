@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.senjay.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App(resumes.intValue) } }
    }

    // 回到前台时界面重读:响过的一次性提醒已被接收器关掉,权限也可能刚在系统设置里改过
    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

private fun packageUri(ctx: Context) = Uri.parse("package:${ctx.packageName}")

@Composable
fun App(resumes: Int) {
    val ctx = LocalContext.current
    var tasks by remember { mutableStateOf(Store.load(ctx)) }
    LaunchedEffect(resumes) { tasks = Store.load(ctx) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }

    LaunchedEffect(Unit) {
        if (Prefs.get(ctx, Prefs.GH_TOKEN).isBlank()) return@LaunchedEffect
        try {
            Updater.latest(ctx).takeIf { Updater.isNewer(it.version, BuildConfig.VERSION_NAME) }?.let { update = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Updater", "启动时检查更新失败", e) // 自动检查失败不打扰;设置页手动检查会把原因弹出来
        }
    }

    val commit: (List<Task>) -> Unit = { Store.put(ctx, it); tasks = it }
    if (settings) {
        BackHandler { settings = false }
        SettingsScreen(onBack = { settings = false }, onUpdate = { update = it })
    } else {
        MainScreen(tasks, resumes, commit, onSettings = { settings = true })
    }
    update?.let { UpdateDialog(it) { update = null } }
}

@Composable
fun MainScreen(tasks: List<Task>, resumes: Int, commit: (List<Task>) -> Unit, onSettings: () -> Unit) {
    var editing by remember { mutableStateOf<Task?>(null) }
    val now = remember(tasks, resumes) { LocalDateTime.now() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI提醒") },
                actions = { IconButton(onSettings) { Icon(Icons.Default.Settings, "设置") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton({
                val at = LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
                editing = Task(id = 0, title = "", time = at.toLocalTime(), date = at.toLocalDate())
            }) { Icon(Icons.Default.Add, "新建") }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PermissionCard() }
            item { AiBox(tasks, commit) }
            items(tasks.sortedWith(compareBy({ !it.enabled }, { it.next(now) ?: LocalDateTime.MAX })), key = { it.id }) { t ->
                TaskRow(t, now, onClick = { editing = t }) { on ->
                    commit(tasks.map { if (it.id == t.id) it.copy(enabled = on) else it })
                }
            }
            if (tasks.isEmpty()) item {
                Text(
                    "还没有提醒。点右下角 + 手动添加，或在上面说一句话让 AI 建。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    editing?.let { t ->
        TaskDialog(
            t,
            onDismiss = { editing = null },
            onSave = { saved ->
                commit(if (t.id == 0) tasks + saved.copy(id = nextId(tasks)) else tasks.map { if (it.id == saved.id) saved else it })
                editing = null
            },
            onDelete = if (t.id == 0) null else ({
                commit(tasks.filter { it.id != t.id })
                editing = null
            }),
        )
    }
}

/** 每次重组都重新查:用户从系统设置页回来时 onResume 会触发重组。 */
@Composable
fun PermissionCard() {
    val ctx = LocalContext.current
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val open = { action: String -> ctx.startActivity(Intent(action, packageUri(ctx))) }
    val issues = buildList<Pair<String, () -> Unit>> {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) add("通知权限没开，提醒弹不出来" to { notif.launch(Manifest.permission.POST_NOTIFICATIONS) })
        if (Build.VERSION.SDK_INT >= 31 && !ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            add("精确闹钟权限没开，提醒会不准时" to { open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) })
        if (Build.VERSION.SDK_INT >= 34 && !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
            add("全屏通知没开，锁屏时不会弹出" to { open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) })
        if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName))
            add("电池优化没关，后台可能被系统杀掉" to { open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) })
        if (!Settings.canDrawOverlays(ctx))
            add("悬浮窗没开（可选：开了用手机时也全屏弹出）" to { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) })
    }
    if (issues.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            issues.forEach { (label, fix) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(fix) { Text("去开启") }
                }
            }
        }
    }
}

@Composable
fun AiBox(tasks: List<Task>, commit: (List<Task>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun send(q: String) {
        if (q.isBlank() || busy) return
        busy = true
        error = null
        result = null
        scope.launch {
            try {
                result = Ai.ask(ctx, q.trim(), tasks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    // 说完直接交给 AI,不用再点一次
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            text = it
            send(it)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("说一句，比如：每个工作日早上7点半叫我起床") },
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        try {
                            speech.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要提醒的事")
                            )
                        } catch (e: ActivityNotFoundException) {
                            toast(ctx, "这台手机没有系统语音识别，用输入法的语音键说也一样")
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("🎤 语音") }
                Button({ send(text) }, Modifier.weight(1f), enabled = !busy && text.isNotBlank()) { Text("交给 AI") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            result?.let { r ->
                if (r.reply.isNotBlank()) Text(r.reply, style = MaterialTheme.typography.bodyMedium)
                r.actions.forEach { Text("• " + it.describe(), fontWeight = FontWeight.Medium) }
                Row(Modifier.align(Alignment.End)) {
                    TextButton({ result = null }) { Text(if (r.actions.isEmpty()) "知道了" else "取消") }
                    if (r.actions.isNotEmpty()) Button({
                        commit(tasks.applyActions(r.actions))
                        result = null
                        text = ""
                    }) { Text("确认") }
                }
            }
        }
    }
}

@Composable
fun TaskRow(t: Task, now: LocalDateTime, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val expired = t.enabled && t.next(now) == null
    val dim = !t.enabled || expired
    Card(onClick, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    t.time.format(HHMM), fontSize = 34.sp, fontWeight = FontWeight.Light,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(t.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(t.repeatText(), "已过期".takeIf { expired }, t.note.ifBlank { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(t.enabled, onToggle)
        }
    }
}

@Composable
fun TaskDialog(initial: Task, onDismiss: () -> Unit, onSave: (Task) -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0) "新建提醒" else "编辑提醒") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(t.title, { t = t.copy(title = it) }, label = { Text("标题") }, singleLine = true)
                OutlinedTextField(t.note, { t = t.copy(note = it) }, label = { Text("备注") })
                OutlinedButton({
                    TimePickerDialog(ctx, { _, h, m -> t = t.copy(time = t.time.withHour(h).withMinute(m)) }, t.time.hour, t.time.minute, true).show()
                }) { Text("时间  ${t.time.format(HHMM)}") }
                Text("重复（都不选 = 只响一次）", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { d ->
                        FilterChip(
                            selected = d in t.days,
                            onClick = { t = t.copy(days = if (d in t.days) t.days - d else t.days + d) },
                            label = { Text(weekdayName(d).takeLast(1)) },
                        )
                    }
                }
                if (t.days.isEmpty()) {
                    val d = t.date ?: LocalDate.now()
                    OutlinedButton({
                        DatePickerDialog(ctx, { _, y, mo, day -> t = t.copy(date = LocalDate.of(y, mo + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    }) { Text("日期  $d") }
                }
                SoundPicker(t.sound, "跟随默认铃声") { t = t.copy(sound = it) }
            }
        },
        confirmButton = {
            TextButton({
                val fixed = t.copy(
                    title = t.title.trim(),
                    enabled = true,
                    date = if (t.days.isEmpty()) t.date ?: LocalDate.now() else null,
                )
                when {
                    fixed.title.isEmpty() -> toast(ctx, "填个标题")
                    fixed.next(LocalDateTime.now()) == null -> toast(ctx, "这个时间已经过了")
                    else -> onSave(fixed)
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(it) { Text("删除", color = MaterialTheme.colorScheme.error) } }
                TextButton(onDismiss) { Text("取消") }
            }
        },
    )
}

/** sound = null 表示用上一级的默认(提醒→设置里的默认铃声→系统闹钟铃声)。 */
@Composable
fun SoundPicker(sound: String?, defaultLabel: String, onPick: (String?) -> Unit) {
    val ctx = LocalContext.current
    val ringtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION")
        val uri = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        uri?.let { onPick(it.toString()) }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 持久授权,否则重启后闹钟服务读不到这个文件
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            onPick(uri.toString())
        }
    }
    Text("铃声：" + (sound?.let { soundName(ctx, it) } ?: defaultLabel), style = MaterialTheme.typography.bodyMedium)
    FlowRow {
        TextButton({
            ringtone.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            )
        }) { Text("系统铃声") }
        TextButton({ file.launch(arrayOf("audio/*")) }) { Text("音频文件") }
        if (sound != null) TextButton({ onPick(null) }) { Text("恢复默认") }
    }
}

private fun soundName(ctx: Context, s: String): String {
    val uri = Uri.parse(s)
    // ponytail: 只是显示名,查不到就显示 uri 本身,不影响响铃
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
    }
    return RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) ?: s
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onUpdate: (Updater.Release) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(Prefs.get(ctx, Prefs.SOUND).ifBlank { null }) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AI", style = MaterialTheme.typography.titleSmall)
            PrefField(Prefs.AI_BASE, "接口地址（OpenAI 兼容，写到 /v1）")
            PrefField(Prefs.AI_KEY, "API Key", secret = true)
            PrefField(Prefs.AI_MODELS, "模型（逗号分隔，前一个失败自动换下一个）")

            Text("提醒", style = MaterialTheme.typography.titleSmall)
            SoundPicker(sound, "系统闹钟铃声") {
                sound = it
                Prefs.set(ctx, Prefs.SOUND, it ?: "")
            }
            OutlinedButton({
                Alarms.at(ctx, Alarms.TEST_ID, System.currentTimeMillis() + 5_000)
                toast(ctx, "5 秒后响铃，可以先锁屏试试")
            }) { Text("测试响铃") }
            Text(
                "小米、华为、OPPO、vivo 等手机还要在系统设置里给本应用打开「自启动」「后台弹出界面」「锁屏显示」，否则可能不响或不弹。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("更新", style = MaterialTheme.typography.titleSmall)
            PrefField(Prefs.GH_TOKEN, "GitHub Token（只读本仓库）", secret = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}", Modifier.weight(1f))
                Button(enabled = !checking, onClick = {
                    checking = true
                    scope.launch {
                        try {
                            val r = Updater.latest(ctx)
                            if (Updater.isNewer(r.version, BuildConfig.VERSION_NAME)) onUpdate(r) else toast(ctx, "已经是最新版")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            toast(ctx, "检查失败：${e.message}")
                        } finally {
                            checking = false
                        }
                    }
                }) { Text(if (checking) "检查中…" else "检查更新") }
            }
        }
    }
}

@Composable
fun PrefField(key: String, label: String, secret: Boolean = false) {
    val ctx = LocalContext.current
    var v by remember { mutableStateOf(Prefs.get(ctx, key)) }
    OutlinedTextField(
        v, { v = it; Prefs.set(ctx, key, it) }, Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
fun UpdateDialog(r: Updater.Release, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("新版本 v${r.version}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.notes.ifBlank { "（没有更新说明）" })
                progress?.let {
                    LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("下载中 $it%")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null, onClick = {
                progress = 0
                error = null
                scope.launch {
                    try {
                        val f = Updater.download(ctx, r) { progress = it }
                        if (Updater.install(ctx, f)) onDismiss() else error = "先允许本应用「安装未知应用」，回来再点一次更新"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "下载失败：${e.message}"
                    } finally {
                        progress = null
                    }
                }
            }) { Text("更新") }
        },
        dismissButton = { TextButton(onDismiss, enabled = progress == null) { Text("以后") } },
    )
}
