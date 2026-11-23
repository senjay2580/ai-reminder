@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.senjay.reminder

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
fun InboxScreen(
    messages: List<InboxMessage>,
    resumes: Int,
    requestedKey: String?,
    onKeyHandled: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val visible = messages.filter { !unreadOnly || !it.read }
    val detail = messages.find { it.key == detailKey }
    val unread = messages.count { !it.read }
    val leaveSelection = { selecting = false; selected = emptySet<String>() }
    val open: (InboxMessage) -> Unit = { m ->
        detailKey = m.key
        Inbox.markRead(ctx, setOf(m.key))
        Notices.cancelCortex(ctx, m)
    }
    val back: () -> Unit = {
        when {
            selecting -> leaveSelection()
            detailKey != null -> detailKey = null
            else -> onBack()
        }
    }
    BackHandler(selecting || detailKey != null, back)
    LaunchedEffect(messages, unreadOnly) {
        selected = selected.intersect(visible.map { it.key }.toSet())
        if (detailKey != null && detail == null) detailKey = null
    }
    LaunchedEffect(requestedKey, resumes) {
        if (requestedKey != null) {
            Inbox.load(ctx).find { it.key == requestedKey }?.let(open)
                ?: toast(ctx, "这条通知已删除或已超过 30 天")
            leaveSelection()
            onKeyHandled()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            Inbox.clean(ctx)
            now = LocalDateTime.now()
            delay(60_000)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (selecting) "已选 ${selected.size} 条" else if (detail != null) "通知详情" else "通知信箱") },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                if (selecting) {
                    TextButton({ selected = visible.map { it.key }.toSet() }) { Text("全选") }
                } else {
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "管理通知") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("批量管理") }, enabled = messages.isNotEmpty(), onClick = {
                                menu = false
                                detailKey = null
                                selecting = true
                            })
                            DropdownMenuItem(text = { Text("全部标为已读") }, enabled = unread > 0, onClick = {
                                menu = false
                                Inbox.markRead(ctx, messages.map { it.key }.toSet())
                            })
                            DropdownMenuItem(text = { Text("清理已读通知") }, enabled = messages.any { it.read }, onClick = {
                                menu = false
                                deleting = messages.filter { it.read }.map { it.key }.toSet()
                            })
                            DropdownMenuItem(text = { Text("清空信箱") }, enabled = messages.isNotEmpty(), onClick = {
                                menu = false
                                deleting = messages.map { it.key }.toSet()
                            })
                        }
                    }
                }
            },
        )
    }) { pad ->
        if (detail != null) {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    Text(detail.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                item {
                    Text("Cortex · ${inboxTime(detail, now)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (detail.body.isNotBlank()) item {
                    SelectionContainer { Text(detail.body, style = MaterialTheme.typography.bodyLarge) }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pushLink(detail.url, detail.source)?.let { link ->
                            OutlinedButton(onClick = {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                } catch (e: ActivityNotFoundException) {
                                    toast(ctx, "没有可打开这条链接的应用")
                                }
                            }) { Text("查看原文") }
                        }
                        Row {
                            TextButton(onClick = {
                                Inbox.markRead(ctx, setOf(detail.key), read = false)
                                detailKey = null
                            }) { Text("标为未读") }
                            TextButton({ deleting = setOf(detail.key) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!unreadOnly, { unreadOnly = false }, label = { Text("全部 ${messages.size}") })
                            FilterChip(unreadOnly, { unreadOnly = true }, label = { Text("未读 $unread") })
                        }
                        TextButton(enabled = !syncing && Cortex.ready(ctx), onClick = {
                            syncing = true
                            scope.launch {
                                try {
                                    Cortex.syncNow(ctx)
                                    toast(ctx, "同步完成")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    toast(ctx, "同步失败：${e.message}")
                                } finally {
                                    syncing = false
                                }
                            }
                        }) { Text(if (syncing) "同步中…" else "同步") }
                    }
                }
                item {
                    Text("保留最近 30 天的通知，可在右上角手动清理", Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selecting) item {
                    Row {
                        TextButton(enabled = selected.isNotEmpty(), onClick = {
                            Inbox.markRead(ctx, selected)
                            leaveSelection()
                        }) { Text("标为已读") }
                        TextButton(enabled = selected.isNotEmpty(), onClick = { deleting = selected }) {
                            Text("删除所选", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (visible.isEmpty()) item {
                    Column(Modifier.padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (unreadOnly) "没有未读通知" else "信箱为空", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (unreadOnly) "可以切换到全部通知。" else if (Cortex.ready(ctx)) "新收到的 Cortex 通知会保存在这里。" else "在设置中连接 Cortex 后，服务端通知会保存在这里。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                visible.groupBy { Instant.ofEpochMilli(it.created).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, list) ->
                    item(key = day.toString()) {
                        Text(
                            if (day == now.toLocalDate()) "今天" else if (day == now.toLocalDate().minusDays(1)) "昨天" else "${day.year}年${day.monthValue}月${day.dayOfMonth}日",
                            Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(list, key = { it.key }) { m ->
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(
                                onClick = {
                                    if (selecting) selected = if (m.key in selected) selected - m.key else selected + m.key else open(m)
                                },
                                onLongClick = { selecting = true; selected = selected + m.key },
                            ).padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (selecting) Checkbox(m.key in selected, { checked -> selected = if (checked) selected + m.key else selected - m.key })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (!m.read) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                    Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = if (m.read) FontWeight.Medium else FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                if (m.body.isNotBlank()) Text(m.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Cortex · ${inboxTime(m, now)} · ${if (m.read) "已读" else "未读"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    deleting?.let { keys ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 ${keys.size} 条通知？") },
            text = { Text("删除后将从手机信箱和系统通知栏清除，无法撤销。") },
            confirmButton = {
                TextButton({
                    Inbox.delete(ctx, keys)
                    deleting = null
                    if (detailKey in keys) detailKey = null
                    leaveSelection()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
}

private fun inboxTime(m: InboxMessage, now: LocalDateTime): String =
    whenText(LocalDateTime.ofInstant(Instant.ofEpochMilli(m.created), ZoneId.systemDefault()), now)
