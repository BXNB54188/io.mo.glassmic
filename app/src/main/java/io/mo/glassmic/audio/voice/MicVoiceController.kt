package io.mo.glassmic.audio.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.audio.LiveCaptureSource
import io.mo.glassmic.core.audio.voice.VoiceChanger
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 实时变声会话：自己打开麦克风，逐块变声，推进 [LiveCaptureSource]。
 *
 * 配合 **AudioPolicy 注入后端**使用：
 *   mic → 本类(DSP) → Publisher → AudioPolicyController → AudioTrack(INJECTOR) → 目标 App 的录音
 *
 * 这条路不依赖 LSPosed Hook：目标 App 的 AudioRecord 由系统从注入 Track 取数据，
 * 因此它不会再占用真实麦克风，我们这边也就不会被"并发采集规则"判静音。
 */
@Singleton
class MicVoiceController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class Phase { IDLE, ACTIVE, ERROR }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val detail: String = ""
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val changer = VoiceChanger(48_000)

    @Volatile private var source: LiveCaptureSource? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    /**
     * 是否有目标 App 正在录音（Publisher 有 consumer）。
     *
     * 没有消费者时完全不打开麦克风：省电、不占设备、也不会和其它录音应用抢麦。
     * 由 PlaybackController 根据 publisher.consumerCount 驱动。
     */
    @Volatile private var consumerActive = false

    fun setConsumerActive(active: Boolean) {
        if (consumerActive != active) {
            consumerActive = active
            GlassLog.b("MicVoice") { if (active) "检测到录音客户端，开始采集" else "无客户端录音，挂起采集" }
        }
    }

    private val prefs by lazy { context.getSharedPreferences("mic_voice", Context.MODE_PRIVATE) }

    fun open(): LiveCaptureSource {
        close()
        val src = LiveCaptureSource(SESSION_TAG) { released -> if (released === source) close() }
        source = src
        running = true
        _status.value = Status(Phase.ACTIVE, "")
        thread = Thread({ captureLoop(src) }, "glassmic-micvoice").apply {
            isDaemon = true
            start()
        }
        GlassLog.b("MicVoice") { "开始麦克风变声" }
        return src
    }

    fun close() {
        val was = running
        running = false
        thread?.let { runCatching { it.join(500) } }
        thread = null
        runCatching { record?.stop() }
        record = null
        source = null
        if (was) {
            _status.value = Status(Phase.IDLE, "")
            GlassLog.b("MicVoice") { "已结束麦克风变声" }
        }
    }

    private fun captureLoop(src: LiveCaptureSource) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val buf = ByteArray(BLOCK_BYTES)
        var lastParamsAt = 0L
        var peakWindow = 0
        var peakLogAt = 0L
        var CAPTURE_SOURCE = -1
        try {
            while (running) {
                // 没人录音就彻底不占麦克风
                if (!consumerActive) {
                    if (record != null) {
                        runCatching { record?.stop() }
                        runCatching { record?.release() }
                        record = null
                    }
                    Thread.sleep(100)
                    continue
                }
                if (record == null) {
                    val rec = openRecord()
                    if (rec == null) {
                        fail("麦克风不可用（检查录音权限或是否被其他应用占用）")
                        return
                    }
                    record = rec
                    CAPTURE_SOURCE = rec.audioSource
                    rec.startRecording()
                    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        fail("麦克风启动失败")
                        return
                    }
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastParamsAt >= 300L) {
                    lastParamsAt = now
                    val encoded = prefs.getString("voice_params", null)
                    if (encoded != null) VoiceChanger.applyEncoded(changer, encoded)
                }
                val rec = record ?: continue
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    fail("麦克风读取错误 $n")
                    break
                }
                if (n == 0) {
                    Thread.sleep(2)
                    continue
                }
                // 电平诊断：每 2 秒打一次峰值。
                // peak = 0 说明系统把我们的采集喂了静音（后台采集限制），
                // 与被注入端无关，必须先解决权限/appops。
                var peak = 0
                var i = 0
                while (i < n) {
                    val v = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
                    val a = if (v < 0) -v else v
                    if (a > peak) peak = a
                    i += 2
                }
                if (peak > peakWindow) peakWindow = peak
                val t2 = android.os.SystemClock.uptimeMillis()
                if (t2 - peakLogAt >= 2000L) {
                    GlassLog.b("MicVoice") {
                        "capture peak=$peakWindow (0~32767) 源=$CAPTURE_SOURCE 块=$n 字节"
                    }
                    peakWindow = 0
                    peakLogAt = t2
                }

                changer.process(buf, 0, n)
                src.push(buf, n)
            }
        } catch (t: Throwable) {
            fail(t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            record = null
        }
    }

    @Suppress("DEPRECATION")
    private fun openRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, SAMPLE_RATE / 1000 * BYTES_PER_FRAME * BUFFER_MS)
        for (src in CANDIDATE_SOURCES) {
            val rec = runCatching {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufBytes)
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                GlassLog.b("MicVoice") { "采集源 = $src" }
                return rec
            }
            runCatching { rec.release() }
        }
        return null
    }

    private fun fail(message: String) {
        running = false
        _status.value = Status(Phase.ERROR, message)
        GlassLog.b("MicVoice") { "错误: $message" }
    }

    private companion object {
        const val SESSION_TAG = "@micvoice"
        const val SAMPLE_RATE = 48_000
        const val BYTES_PER_FRAME = 2
        const val BUFFER_MS = 20
        const val BLOCK_BYTES = 960     // 10ms @48k mono PCM16

        val CANDIDATE_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
    }
}
