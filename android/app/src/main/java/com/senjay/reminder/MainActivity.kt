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
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

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
    var missed by remember { mutableStateOf(emptyList<Task>()) }
    LaunchedEffect(resumes) {
        val all = Store.load(ctx)
        Alarms.missed(all, Alarms.expected(ctx), System.currentTimeMillis()).takeIf { it.isNotEmpty() }?.let { missed = it }
        all.forEach { Alarms.schedule(ctx, it) } // 清理后台 / 强行停止会连带取消系统闹钟,回到前台就全部重排
        tasks = all
        Cortex.start(ctx) // 实时通道被杀过的话,回到前台拉起来
    }
    // 后台改了列表(Cortex 推来闹钟、一次性提醒响过被关掉),界面跟着重读,免得下一次保存把它覆盖掉
    val rev = Store.rev.intValue
    LaunchedEffect(rev) { tasks = Store.load(ctx) }
    var settings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings) { if (!settings) Cortex.start(ctx) } // 设置里改了 Cortex 地址 / 令牌,回到主页时按新的生效
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
        MainScreen(tasks, resumes, commit, missed, onMissedSeen = { missed = emptyList() }, onSettings = { settings = true })
    }
    update?.let { UpdateDialog(it) { update = null } }
}

@Composable
fun MainScreen(
    tasks: List<Task>,
    resumes: Int,
    commit: (List<Task>) -> Unit,
    missed: List<Task>,
    onMissedSeen: () -> Unit,
    onSettings: () -> Unit,
) {
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
    var calendar by rememberSaveable { mutableStateOf(false) }
    val today = now.toLocalDate()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    val ai = remember { AiState() }
    val voice = remember { VoiceState() }
    val scope = rememberCoroutineScope()
    val ask: (String, List<Uri>) -> Unit = { q, imgs ->
        if ((q.isNotBlank() || imgs.isNotEmpty()) && !ai.busy) {
            ai.asked = q.trim()
            ai.images = imgs
            ai.picked = emptyList()
            ai.busy = true
            ai.error = null
            ai.result = null
            scope.launch {
                try {
                    ai.result = Ai.ask(ctx, ai.asked, ai.images, tasks)
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
                actions = {
                    IconButton({ calendar = !calendar }) {
                        if (calendar) Icon(Icons.AutoMirrored.Filled.List, "列表") else Icon(Icons.Default.DateRange, "日历")
                    }
                    IconButton(onSettings) { Icon(Icons.Default.Settings, "设置") }
                },
            )
        },
        bottomBar = { InputBar(voice, ai) { ask(it, ai.picked) } },
        floatingActionButton = {
            FloatingActionButton({
                val at = LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
                // 日历里选中了以后的某天,就新建在那天
                editing = Task(id = 0, title = "", time = at.toLocalTime(), date = if (calendar && day.isAfter(today)) day else at.toLocalDate())
            }) { Icon(Icons.Default.Add, "新建") }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PermissionCard() }
            if (missed.isNotEmpty()) item { MissedCard(missed, onFix = onSettings, onDismiss = onMissedSeen) }
            if (ai.active) item {
                AiCard(ai, onRetry = { ask(ai.asked, ai.images) }) { actions ->
                    commit(tasks.applyActions(actions))
                    ai.clear()
                }
            }
            if (calendar) {
                item {
                    MonthCalendar(tasks, month, day, today, onMonth = {
                        month = it
                        day = if (it == YearMonth.from(today)) today else it.atDay(1)
                    }) { day = it }
                }
                val list = tasks.on(day, today)
                item {
                    Text(
                        "${day.monthValue}月${day.dayOfMonth}日 ${weekdayName(day.dayOfWeek.value)} · " + if (list.isEmpty()) "这天没有提醒" else "${list.size} 个提醒",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(list, key = { "day${it.id}" }) { t -> DayRow(t, day.atTime(t.time), now) { detailId = t.id } }
            } else {
                items(tasks.sortedWith(compareBy({ !it.enabled }, { it.next(now) ?: LocalDateTime.MAX })), key = { it.id }) { t ->
                    TaskRow(t, now, onClick = { detailId = t.id }) { on ->
                        commit(tasks.map { if (it.id == t.id) it.copy(enabled = on) else it })
                    }
                }
                if (tasks.isEmpty()) item {
                    Text(
                        "还没有提醒。按住底部按钮说一句话，或点 🖼️ 发课程表、通知截图让 AI 建；也可以点 + 手动添加。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (voice.listening) VoiceOverlay(voice)
    }
    editing?.let { t ->
        TaskDialog(
            t,
            datePicked = t.id != 0 || calendar,
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

/** 有提醒到点没响:几乎都是清理后台时被系统连带杀掉了。摆出来并给修法,不让它悄悄发生。 */
@Composable
fun MissedCard(missed: List<Task>, onFix: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${missed.size} 个提醒到点没响", fontWeight = FontWeight.Medium)
            Text(missed.joinToString("、") { "${it.time.format(HHMM)} ${it.title}" }, style = MaterialTheme.typography.bodySmall)
            Text(
                "多半是清理后台时被系统连带杀掉了。之后的提醒已经重新排好；要防止再发生，去设置里开「自启动」并在多任务里锁定本应用。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.align(Alignment.End)) {
                TextButton(onDismiss) { Text("知道了") }
                Button(onFix) { Text("去设置") }
            }
        }
    }
}

// 各家「自启动」管理页:没有公开 API,只能按包名逐个试
private val AUTOSTART = listOf(
    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity", // 小米 / 红米
    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity", // 华为
    "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity", // 荣耀
    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity", // OPPO / 一加
    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
    "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity", // vivo / iQOO
    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
    "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity", // 魅族
)

/** 跳到本机的自启动管理页;都打不开就退到应用详情页,让用户自己找。 */
fun openAutostart(ctx: Context) {
    for ((pkg, cls) in AUTOSTART) {
        try {
            ctx.startActivity(Intent().setClassName(pkg, cls))
            return
        } catch (e: ActivityNotFoundException) {
            // 不是这个牌子,试下一个
        } catch (e: SecurityException) {
            Log.w("Autostart", "$cls 不让第三方打开", e)
        }
    }
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(ctx)))
    toast(ctx, "没找到自启动页，在这里找「自启动 / 后台运行 / 耗电管理」")
}

class AiState {
    var asked by mutableStateOf("")
    var images by mutableStateOf<List<Uri>>(emptyList()) // 这次一起发出去的图,重试时再发一遍
    var picked by mutableStateOf<List<Uri>>(emptyList()) // 选好了、还没发的图
    var busy by mutableStateOf(false)
    var result by mutableStateOf<AiResult?>(null)
    var error by mutableStateOf<String?>(null)

    val active get() = asked.isNotEmpty() || images.isNotEmpty()

    fun clear() {
        asked = ""
        images = emptyList()
        result = null
        error = null
    }
}

/** 列表顶部:你说了什么(发了几张图)→ AI 打算怎么改 → 确认才生效。 */
@Composable
fun AiCard(ai: AiState, onRetry: () -> Unit, onConfirm: (List<Action>) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                listOfNotNull(ai.asked.ifBlank { null }?.let { "你说：$it" }, ai.images.size.takeIf { it > 0 }?.let { "🖼️ $it 张图" }).joinToString("  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** 仿微信底部输入栏:默认按住说话,左边切换打字、选图;选了图再说一句或打几个字一起发。 */
@Composable
fun InputBar(voice: VoiceState, ai: AiState, onSend: (String) -> Unit) {
    var voiceMode by rememberSaveable { mutableStateOf(true) }
    var text by rememberSaveable { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { if (it.isNotEmpty()) ai.picked = it }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp)) {
            if (ai.picked.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "🖼️ 已选 ${ai.picked.size} 张图，可以再说一句或打字补充",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton({ ai.picked = emptyList() }) { Text("移除") }
                Button({ onSend(text); text = "" }, enabled = !ai.busy) { Text("识别") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton({ voiceMode = !voiceMode }) { Text(if (voiceMode) "⌨️" else "🎤", fontSize = 22.sp) }
                IconButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !ai.busy) {
                    Text("🖼️", fontSize = 22.sp)
                }
                if (voiceMode) {
                    HoldToTalk(voice, enabled = !ai.busy, onText = onSend, modifier = Modifier.weight(1f))
                } else {
                    OutlinedTextField(
                        text, { text = it }, Modifier.weight(1f),
                        placeholder = { Text(if (ai.picked.isEmpty()) "比如：工作日早上7点半叫我起床" else "比如：只要考试那几条，提前半小时") },
                        maxLines = 4,
                    )
                    Button({ onSend(text); text = "" }, enabled = !ai.busy && (text.isNotBlank() || ai.picked.isNotEmpty())) { Text("发送") }
                }
            }
        }
    }
}

/** 月历:每天下面的点 = 那天会响几个(最多画 3 个);点哪天,下面就列哪天。 */
@Composable
fun MonthCalendar(tasks: List<Task>, month: YearMonth, selected: LocalDate, today: LocalDate, onMonth: (YearMonth) -> Unit, onPick: (LocalDate) -> Unit) {
    val c = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ onMonth(month.minusMonths(1)) }) { Text("‹", fontSize = 22.sp) }
                Text("${month.year}年${month.monthValue}月", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                TextButton({ onMonth(month.plusMonths(1)) }) { Text("›", fontSize = 22.sp) }
            }
            Row {
                "一二三四五六日".forEach {
                    Text(it.toString(), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
            }
            val cells = List<LocalDate?>(month.atDay(1).dayOfWeek.value - 1) { null } + (1..month.lengthOfMonth()).map(month::atDay)
            cells.chunked(7).forEach { week ->
                Row {
                    (0 until 7).forEach { i ->
                        val d = week.getOrNull(i)
                        Box(
                            Modifier.weight(1f).height(52.dp).padding(2.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (d == selected) c.primaryContainer else Color.Transparent)
                                .then(if (d == null) Modifier else Modifier.clickable { onPick(d) }),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (d != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${d.dayOfMonth}",
                                    fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                    color = if (d == today) c.primary else c.onSurface,
                                )
                                Text("•".repeat(minOf(tasks.on(d, today).size, 3)), color = c.primary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 日历下面的一行:这天几点响什么;已过或已关的变灰。 */
@Composable
fun DayRow(t: Task, at: LocalDateTime, now: LocalDateTime, onClick: () -> Unit) {
    val color = if (!t.enabled || !at.isAfter(now)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Card(onClick, Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(t.time.format(HHMM), fontSize = 22.sp, fontWeight = FontWeight.Light, color = color)
            KindBadge(t)
            Text(
                t.title + if (t.note.isBlank()) "" else " · ${t.note}",
                Modifier.weight(1f),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
fun TaskDialog(initial: Task, datePicked: Boolean, onDismiss: () -> Unit, onSave: (Task) -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    var t by remember { mutableStateOf(initial) }
    var monthDayText by remember { mutableStateOf((initial.monthDay ?: LocalDate.now().dayOfMonth).toString()) }
    var pickedDate by remember { mutableStateOf(datePicked) } // 手动挑过日期(或在日历里点了那天)就不再自动顺延
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
            PrefField(Prefs.AI_BASE, "接口地址（Responses 接口，写到 /v1）")
            PrefField(Prefs.AI_KEY, "AI 令牌", secret = true)
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

            Text("清理后台也要响", style = MaterialTheme.typography.titleSmall)
            Text(
                "小米、华为、OPPO、vivo 等手机「一键清理」会把本应用连同排好的闹钟一起杀掉。照这三步做一次：\n" +
                    "1. 点「去开自启动」把本应用打开；找不到就在应用详情里找「自启动 / 后台运行 / 耗电管理 → 无限制」，顺手打开「后台弹出界面」「锁屏显示」\n" +
                    "2. 多任务界面把「AI提醒」卡片下拉或长按 → 锁定，一键清理就不会杀它\n" +
                    "3. 点「清后台测试」，马上去多任务一键清理再锁屏，1 分钟后响了才算真的好了",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ openAutostart(ctx) }) { Text("去开自启动") }
                OutlinedButton({
                    Alarms.at(ctx, Alarms.TEST_ID, System.currentTimeMillis() + 60_000)
                    toast(ctx, "1 分钟后响：现在去多任务一键清理，再锁屏等着")
                }) { Text("清后台测试") }
            }

            Text("Cortex 联动", style = MaterialTheme.typography.titleSmall)
            CortexSettings()

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

/** 收 Cortex 的推送 / 闹钟,并把本机提醒整表同步上去。 */
@Composable
fun CortexSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var on by remember { mutableStateOf(Cortex.on(ctx)) }
    var syncing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(Cortex.status(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            status = Cortex.status(ctx)
            delay(2_000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "实时收 Cortex 的推送和闹钟（闹钟会加进本机提醒），并把本机提醒同步给 Cortex",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(on, { v ->
            on = v
            Prefs.set(ctx, Prefs.CORTEX_ON, if (v) "1" else "")
            if (v && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
            Cortex.start(ctx)
        })
    }
    PrefField(Prefs.CORTEX_BASE, "服务器地址")
    PrefField(Prefs.CORTEX_TOKEN, "设备令牌（Cortex 自动化 → 推送 里复制）", secret = true)
    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(enabled = !syncing, onClick = {
        syncing = true
        scope.launch {
            try {
                Cortex.syncNow(ctx)
                Cortex.start(ctx)
                toast(ctx, "同步完成")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(ctx, "同步失败：${e.message}")
            } finally {
                syncing = false
                status = Cortex.status(ctx)
            }
        }
    }) { Text(if (syncing) "同步中…" else "立即同步") }
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
