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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime

/** 响铃时的全屏弹窗:锁屏上方显示并点亮屏幕。 */
class AlarmActivity : ComponentActivity() {
    private var task by mutableStateOf<Task?>(null)

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
        load(intent)
        setContent { AppTheme { AlarmScreen(task, ::send) } }
    }

    // 弹窗还开着时又响了一个提醒 → 换成新的那个
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        load(intent)
    }

    private fun load(intent: Intent) {
        task = AlarmService.taskFor(this, intent.getIntExtra("id", -1))
    }

    private fun send(action: String) {
        startService(Intent(this, AlarmService::class.java).setAction(action).putExtra("id", task?.id ?: -1))
        finish()
    }
}

@Composable
fun AlarmScreen(t: Task?, send: (String) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(LocalTime.now().format(HHMM), fontSize = 72.sp, fontWeight = FontWeight.Light)
            Text(t?.title ?: "提醒", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            t?.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(32.dp))
            Button({ send(AlarmService.ACTION_STOP) }, Modifier.fillMaxWidth().height(64.dp)) {
                Text("关闭", fontSize = 20.sp)
            }
            OutlinedButton({ send(AlarmService.ACTION_SNOOZE) }, Modifier.fillMaxWidth().height(56.dp)) {
                Text("稍后 10 分钟")
            }
        }
    }
}
