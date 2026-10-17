package com.senjay.reminder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** 按住说话的界面状态:按钮和屏幕中间的浮层共用。 */
class VoiceState {
    var listening by mutableStateOf(false)
    var cancel by mutableStateOf(false) // 手指上滑出按钮 = 松手取消
    var heard by mutableStateOf("")
}

private fun listenIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

/** 仿微信:按下开始听,松手发出,上滑取消。 */
@Composable
fun HoldToTalk(v: VoiceState, enabled: Boolean, onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val send by rememberUpdatedState(onText)
    val talk = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) Talk(ctx, v) { send(it) } else null }
    DisposableEffect(talk) { onDispose { talk?.destroy() } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        toast(ctx, if (ok) "可以了，再按住说话" else "没有麦克风权限，按住说话用不了")
    }
    // 手机上没有可调用的识别服务(部分国产系统如此)时,退回系统语音输入弹窗
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { send(it) }
    }

    fun begin(): Boolean {
        if (talk == null) {
            Log.w("Voice", "isRecognitionAvailable=false,改用系统语音输入弹窗")
            try {
                dialog.launch(listenIntent())
            } catch (e: ActivityNotFoundException) {
                toast(ctx, "这台手机没有语音识别，点左边切到键盘，用输入法的语音键")
            }
            return false
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mic.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        v.cancel = false
        v.listening = true
        talk.start()
        return true
    }

    fun end(cancel: Boolean) {
        v.listening = false
        v.cancel = false
        talk?.release(cancel)
    }

    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                when {
                    !v.listening -> colors.surfaceVariant
                    v.cancel -> colors.errorContainer
                    else -> colors.primaryContainer
                }
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val cancelPx = 80.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown().consume()
                    if (!begin()) return@awaitEachGesture
                    var ended = false
                    try {
                        do {
                            val c = awaitPointerEvent().changes.first()
                            v.cancel = c.position.y < -cancelPx
                            c.consume()
                        } while (c.pressed)
                        ended = true
                        end(v.cancel)
                    } finally {
                        if (!ended) end(cancel = true) // 手势被系统打断(来电、切走)按取消处理,别让麦克风一直开着
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                !enabled -> "AI 思考中…"
                !v.listening -> "按住 说话"
                v.cancel -> "松开 取消"
                else -> "松开 发送"
            },
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 按住时屏幕中间的浮层:实时显示听到的字。 */
@Composable
fun VoiceOverlay(v: VoiceState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (v.cancel) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier.width(240.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (v.cancel) "✋" else "🎤", fontSize = 40.sp)
                Text(v.heard.ifBlank { "正在听…" }, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Text(if (v.cancel) "松开手指，取消发送" else "手指上滑，取消发送", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 一次按住说话。识别器听到停顿会自己结束一轮,但用户还按着:
 * 把每轮结果拼起来接着听,直到松手才发出,中间停顿不会把话截断。
 */
private class Talk(private val ctx: Context, private val v: VoiceState, private val deliver: (String) -> Unit) : RecognitionListener {
    private val sr = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(this) }
    private val parts = mutableListOf<String>()
    private var released = false
    private var cancelled = false
    private var done = false
    private var stopped = false // 按住期间识别器出了硬错误,等松手再报
    private var lastError: String? = null
    private var restarts = 0

    fun start() {
        parts.clear()
        released = false
        cancelled = false
        done = false
        stopped = false
        lastError = null
        restarts = 0
        v.heard = ""
        sr.startListening(listenIntent())
    }

    fun release(cancel: Boolean) {
        released = true
        cancelled = cancel
        when {
            cancel -> sr.cancel()
            stopped -> finish()
            else -> sr.stopListening() // 结果随后经 onResults / onError 到达
        }
    }

    fun destroy() = sr.destroy()

    // ponytail: 按住期间每轮结束就接着听,最多 20 轮,防止识别器异常时空转
    private fun listenAgain() {
        if (restarts++ < 20) sr.startListening(listenIntent()) else stopped = true
    }

    private fun finish() {
        if (done) return
        done = true
        val text = v.heard.trim()
        if (text.isNotEmpty()) deliver(text) else toast(ctx, lastError ?: "没听清，再说一次")
    }

    override fun onPartialResults(b: Bundle?) {
        if (cancelled) return
        b.firstText()?.let { v.heard = (parts + it).joinToString("") }
    }

    override fun onResults(b: Bundle?) {
        if (cancelled) return
        b.firstText()?.let { parts += it }
        v.heard = parts.joinToString("")
        if (released) finish() else listenAgain()
    }

    override fun onError(code: Int) {
        if (cancelled) return
        Log.w("Voice", "识别出错 code=$code released=$released heard=${v.heard.length}字")
        if (code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) lastError = errorText(code)
        when {
            released -> finish()
            lastError == null -> listenAgain() // 还按着、只是这一轮没听到话
            else -> stopped = true
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun Bundle?.firstText() =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun errorText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别要联网，网络不通"
        SpeechRecognizer.ERROR_AUDIO -> "录音失败，麦克风可能被别的应用占着"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错"
        else -> "语音识别出错（代码 $code）"
    }
}
