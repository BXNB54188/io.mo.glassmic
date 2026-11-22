package io.mo.glassmic.audio.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.mo.glassmic.audio.LiveCaptureSource
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 实时变声会话：把真实麦克风采集下来，过 [VoiceChanger] 处理，再推进 [LiveCaptureSource]。
 *
 * 和 [io.mo.glassmic.root.LiveCaptureController] 的区别：那条路是 root 子进程抓「别的 App 的播放声音」，
 * 这条是 GlassMic 自己开麦克风（App 已声明 RECORD_AUDIO），所以不需要 root 子进程、也不需要 FIFO。
 *
 * 采集源优先 UNPROCESSED（最干净、延迟最低），失败依次退 VOICE_COMMUNICATION、MIC。
 */
@Singleton
class MicVoiceController @Inject constructor() {

    enum class Phase { IDLE, ACTIVE, ERROR }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val detail: String = "",
        val presetId: String = PRESETS_FIRST_ID
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 当前生效的变声参数，UI 改预设时直接改它。 */
    val changer = VoiceChanger()

    @Volatile private var source: LiveCaptureSource? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    fun setPreset(id: String) {
        val preset = VoiceChanger.PRESETS.firstOrNull { it.id == id } ?: return
        VoiceChanger.applyPreset(changer, preset)
        _status.value = _status.value.copy(presetId = preset.id)
        GlassLog.b("MicVoice") { "预设切换 -> ${preset.label}" }
    }

    /**
     * 开始采集并返回可交给 Publisher 的音源。重复调用会先结束上一个会话。
     * 返回的 source 被 Publisher 释放（换音源）时会自动回调 [close]。
     */
    fun open(): LiveCaptureSource {
        close()
        val src = LiveCaptureSource(SESSION_TAG) { released ->
            if (released === source) close()
        }
        source = src
        running = true
        _status.value = _status.value.copy(phase = Phase.ACTIVE, detail = "")
        thread = Thread({ captureLoop(src) }, "glassmic-micvoice").apply {
            isDaemon = true
            start()
        }
        GlassLog.b("MicVoice") { "开始麦克风变声，预设=${_status.value.presetId}" }
        return src
    }

    fun close() {
        val wasRunning = running
        running = false
        thread?.let { t -> runCatching { t.join(500) } }
        thread = null
        runCatching { record?.stop() }
        record = null
        source = null
        if (wasRunning) {
            _status.value = _status.value.copy(phase = Phase.IDLE, detail = "")
            GlassLog.b("MicVoice") { "已结束麦克风变声" }
        }
    }

    private fun captureLoop(src: LiveCaptureSource) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val buf = ByteArray(BLOCK_BYTES)
        try {
            val rec = openRecord()
            if (rec == null) {
                fail("麦克风不可用（检查录音权限或是否被其他应用占用）")
                return
            }
            record = rec
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fail("麦克风启动失败")
                return
            }
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    fail("麦克风读取错误 $n")
                    break
                }
                if (n == 0) {
                    Thread.sleep(2)
                    continue
                }
                changer.process(buf, n)
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
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 20ms 缓冲：比 HAL 最小缓冲略大，抗一次调度抖动，又不至于明显加延迟
        val bufBytes = maxOf(minBuf, SAMPLE_RATE / 1000 * BYTES_PER_FRAME * BUFFER_MS)
        for (source in CANDIDATE_SOURCES) {
            val rec = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufBytes
                )
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                GlassLog.b("MicVoice") { "采集源 = $source" }
                return rec
            }
            runCatching { rec.release() }
        }
        return null
    }

    private fun fail(message: String) {
        running = false
        _status.value = _status.value.copy(phase = Phase.ERROR, detail = message)
        GlassLog.b("MicVoice") { "错误: $message" }
    }

    private companion object {
        const val SESSION_TAG = "@micvoice"
        const val SAMPLE_RATE = 48_000
        const val BYTES_PER_FRAME = 2
        const val BUFFER_MS = 20
        const val BLOCK_BYTES = 480          // 5ms @ 48k mono PCM16
        const val PRESETS_FIRST_ID = "off"

        val CANDIDATE_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
    }
}
