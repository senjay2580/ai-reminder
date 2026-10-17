package com.senjay.reminder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime

/** 响铃时的全屏弹窗:锁屏上方显示并点亮屏幕;同时在响的几个提醒一起列出。 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val ringing = AlarmService.ringing
                // 在通知栏点了关闭、或响满 5 分钟自动停了 → 弹窗跟着关
                if (ringing.isEmpty()) LaunchedEffect(Unit) { finish() }
                AlarmScreen(ringing.toList(), ::send)
            }
        }
    }

    private fun send(action: String) {
        startService(Intent(this, AlarmService::class.java).setAction(action))
        finish()
    }
}

@Composable
fun AlarmScreen(tasks: List<Task>, send: (String) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(LocalTime.now().format(HHMM), fontSize = 72.sp, fontWeight = FontWeight.Light)
            tasks.forEach { t ->
                Text(t.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                if (t.note.isNotBlank()) Text(t.note, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(32.dp))
            Button({ send(AlarmService.ACTION_STOP) }, Modifier.fillMaxWidth().height(64.dp)) {
                Text(if (tasks.size > 1) "全部关闭" else "关闭", fontSize = 20.sp)
            }
            OutlinedButton({ send(AlarmService.ACTION_SNOOZE) }, Modifier.fillMaxWidth().height(56.dp)) {
                Text("稍后 10 分钟")
            }
        }
    }
}
