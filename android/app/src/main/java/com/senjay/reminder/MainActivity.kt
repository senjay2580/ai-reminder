@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.senjay.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
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
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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
        try {
            Updater.latest().takeIf { Updater.isNewer(it.version, BuildConfig.VERSION_NAME) }?.let { update = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Updater", "启动时检查更新失败", e) // 自动检查失败不打扰;设置页手动检查会把原因弹出来
        }
    }

    val commit: (List<Task>) -> Unit = { new ->
        val old = tasks.map { it.id }.toSet()
        Store.put(ctx, new)
        tasks = new
        new.filter { it.id !in old }.takeIf { it.isNotEmpty() }?.let { Notices.created(ctx, it) }
    }
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
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Task?>(null) }
    var detailId by remember { mutableStateOf<Int?>(null) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(tasks, resumes) {
        while (true) { // 列表里的「还有多久」跟着走
            now = LocalDateTime.now()
            delay(30_000)
        }
    }
    val ai = remember { AiState() }
    val voice = remember { VoiceState() }
    val scope = rememberCoroutineScope()
    val ask: (String) -> Unit = { q ->
        if (q.isNotBlank() && !ai.busy) {
            ai.asked = q.trim()
            ai.busy = true
            ai.error = null
            ai.result = null
            scope.launch {
                try {
                    ai.result = Ai.ask(ctx, ai.asked, tasks)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ai.error = e.message ?: e.toString()
                } finally {
                    ai.busy = false
                }
            }
        }
    }
    Box {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI提醒") },
                actions = { IconButton(onSettings) { Icon(Icons.Default.Settings, "设置") } },
            )
        },
        bottomBar = { InputBar(voice, ai.busy, ask) },
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
            if (ai.asked.isNotEmpty()) item {
                AiCard(ai, onRetry = { ask(ai.asked) }) { actions ->
                    commit(tasks.applyActions(actions))
                    ai.clear()
                }
            }
            items(tasks.sortedWith(compareBy({ !it.enabled }, { it.next(now) ?: LocalDateTime.MAX })), key = { it.id }) { t ->
                TaskRow(t, now, onClick = { detailId = t.id }) { on ->
                    commit(tasks.map { if (it.id == t.id) it.copy(enabled = on) else it })
                }
            }
            if (tasks.isEmpty()) item {
                Text(
                    "还没有提醒。按住底部按钮说一句话让 AI 建，或点 + 手动添加。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (voice.listening) VoiceOverlay(voice)
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
    tasks.find { it.id == detailId }?.let { t ->
        TaskDetail(
            t, now,
            onDismiss = { detailId = null },
            onEdit = {
                detailId = null
                editing = t
            },
            onDelete = {
                commit(tasks.filter { it.id != t.id })
                detailId = null
            },
        )
    }
}

/** 一条提醒的全部信息:规则、接下来几次、还有多久、铃声。 */
@Composable
fun TaskDetail(t: Task, now: LocalDateTime, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val next = t.upcoming(now, 3)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KindBadge(t)
                DetailLine("规则", "${t.repeatText()} ${t.time.format(HHMM)}")
                DetailLine(
                    "下次",
                    when {
                        !t.enabled -> "已关闭"
                        next.isEmpty() -> "已过期，不会再响"
                        else -> "${whenText(next[0], now)}（${untilText(next[0], now)}）"
                    },
                )
                if (t.enabled && next.size > 1) DetailLine("之后", next.drop(1).joinToString("\n") { whenText(it, now) })
                if (t.note.isNotBlank()) DetailLine("备注", t.note)
                DetailLine("铃声", t.sound?.let { soundName(ctx, it) } ?: "跟随默认铃声")
            }
        },
        confirmButton = { TextButton(onEdit) { Text("编辑") } },
        dismissButton = {
            Row {
                TextButton(onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, Modifier.width(48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

/** 一眼区分:一次 / 每天 / 每周 / 每月。 */
@Composable
fun KindBadge(t: Task) {
    Text(
        t.kind,
        Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.labelSmall,
    )
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

class AiState {
    var asked by mutableStateOf("")
    var busy by mutableStateOf(false)
    var result by mutableStateOf<AiResult?>(null)
    var error by mutableStateOf<String?>(null)

    fun clear() {
        asked = ""
        result = null
        error = null
    }
}

/** 列表顶部:你说了什么 → AI 打算怎么改 → 确认才生效。 */
@Composable
fun AiCard(ai: AiState, onRetry: () -> Unit, onConfirm: (List<Action>) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("你说：${ai.asked}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ai.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ai.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            ai.result?.let { r ->
                if (r.reply.isNotBlank()) Text(r.reply, style = MaterialTheme.typography.bodyMedium)
                r.actions.forEach { Text("• " + it.describe(), fontWeight = FontWeight.Medium) }
            }
            if (!ai.busy) Row(Modifier.align(Alignment.End)) {
                val actions = ai.result?.actions.orEmpty()
                TextButton(ai::clear) { Text(if (actions.isEmpty()) "关闭" else "取消") }
                if (ai.error != null) Button(onRetry) { Text("重试") }
                if (actions.isNotEmpty()) Button({ onConfirm(actions) }) { Text("确认") }
            }
        }
    }
}

/** 仿微信底部输入栏:默认按住说话,左边切换打字。 */
@Composable
fun InputBar(voice: VoiceState, busy: Boolean, onSend: (String) -> Unit) {
    var voiceMode by rememberSaveable { mutableStateOf(true) }
    var text by rememberSaveable { mutableStateOf("") }
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton({ voiceMode = !voiceMode }) { Text(if (voiceMode) "⌨️" else "🎤", fontSize = 22.sp) }
            if (voiceMode) {
                HoldToTalk(voice, enabled = !busy, onText = onSend, modifier = Modifier.weight(1f))
            } else {
                OutlinedTextField(
                    text, { text = it }, Modifier.weight(1f),
                    placeholder = { Text("比如：工作日早上7点半叫我起床") },
                    maxLines = 4,
                )
                Button({ onSend(text); text = "" }, enabled = !busy && text.isNotBlank()) { Text("发送") }
            }
        }
    }
}

@Composable
fun TaskRow(t: Task, now: LocalDateTime, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val next = t.next(now)
    val expired = t.enabled && next == null
    val dim = !t.enabled || expired
    Card(onClick, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    t.time.format(HHMM), fontSize = 34.sp, fontWeight = FontWeight.Light,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KindBadge(t)
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    listOfNotNull(
                        t.repeatText(),
                        when {
                            !t.enabled -> null
                            next == null -> "已过期"
                            else -> "下次 ${whenText(next, now)} · ${untilText(next, now)}"
                        },
                        t.note.ifBlank { null },
                    ).joinToString(" · "),
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
    var monthDayText by remember { mutableStateOf((initial.monthDay ?: LocalDate.now().dayOfMonth).toString()) }
    var pickedDate by remember { mutableStateOf(initial.id != 0) } // 手动挑过日期就不再自动顺延
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 和系统闹钟一样:大号时间点一下就改
                Text(
                    t.time.format(HHMM),
                    Modifier.align(Alignment.CenterHorizontally).clickable {
                        TimePickerDialog(ctx, { _, h, m ->
                            val nt = LocalTime.of(h, m)
                            val today = LocalDate.now()
                            // 只响一次、没手动挑日期:今天这个点已过就顺延到明天
                            val date = if (t.once && !pickedDate) (if (today.atTime(nt).isAfter(LocalDateTime.now())) today else today.plusDays(1)) else t.date
                            t = t.copy(time = nt, date = date)
                        }, t.time.hour, t.time.minute, true).show()
                    },
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light,
                )
                // 一周七天随便勾:一个不勾 = 只响一次,全勾 = 每天
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    (1..7).forEach { d ->
                        DayCircle(weekdayName(d).takeLast(1), selected = t.monthDay == null && d in t.days) {
                            t = t.copy(monthDay = null, days = if (d in t.days) t.days - d else t.days + d)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (t.once) "只响一次" else t.repeatText(),
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilterChip(
                        selected = t.monthDay != null,
                        onClick = {
                            t = if (t.monthDay != null) t.copy(monthDay = null)
                            else t.copy(monthDay = monthDayText.toIntOrNull() ?: LocalDate.now().dayOfMonth, days = emptySet())
                        },
                        label = { Text("每月") },
                    )
                }
                if (t.monthDay != null) {
                    OutlinedTextField(
                        monthDayText,
                        { s ->
                            monthDayText = s.filter(Char::isDigit).take(2)
                            monthDayText.toIntOrNull()?.takeIf { it in 1..31 }?.let { t = t.copy(monthDay = it) }
                        },
                        Modifier.fillMaxWidth(),
                        label = { Text("每月几号（小月没有这天就在月末响）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                } else if (t.once) {
                    val d = t.date ?: LocalDate.now()
                    TextButton({
                        DatePickerDialog(ctx, { _, y, mo, day ->
                            t = t.copy(date = LocalDate.of(y, mo + 1, day))
                            pickedDate = true
                        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    }) { Text("日期：${d.monthValue}月${d.dayOfMonth}日 ${weekdayName(d.dayOfWeek.value)}  ›") }
                }
                OutlinedTextField(t.title, { t = t.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("提醒什么") }, singleLine = true)
                OutlinedTextField(t.note, { t = t.copy(note = it) }, Modifier.fillMaxWidth(), label = { Text("备注（可不填）") })
                SoundPicker(t.sound, "跟随默认铃声") { t = t.copy(sound = it) }
            }
        },
        confirmButton = {
            TextButton({
                val fixed = t.copy(
                    title = t.title.trim(),
                    enabled = true,
                    date = if (t.once) t.date ?: LocalDate.now() else null,
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

@Composable
private fun DayCircle(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(if (selected) c.primary else c.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) c.onPrimary else c.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}", Modifier.weight(1f))
                Button(enabled = !checking, onClick = {
                    checking = true
                    scope.launch {
                        try {
                            val r = Updater.latest()
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
