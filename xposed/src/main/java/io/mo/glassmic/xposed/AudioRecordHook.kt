package io.mo.glassmic.xposed

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.mo.glassmic.core.audio.ComfortNoise
import io.mo.glassmic.core.audio.voice.VoiceChanger
import io.mo.glassmic.core.model.SourceType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.WeakHashMap

/**
 * 拦截 AudioRecord 的全部 read() 重载，把读到的数据替换成虚拟音源。
 *
 * API 101 与 legacy API 82 两个入口共用这一份实现（经 [MethodHooker] 适配），
 * 由 [XposedHookGate.tryMarkAudioHookInstalled] 保证同一进程只装一次。
 *
 * NDK 侧的 AAudio / OpenSL ES / AudioRecord::read 路径由 [NativeAAudioHook]（glass_aaudio.cpp）覆盖。
 */
object AudioRecordHook {

    private const val TAG = "GlassMic-AudioRec"

    private val readers = WeakHashMap<AudioRecord, XposedPcmReader>()

    /** MIC_VOICE 模式的变声器，按 AudioRecord 实例隔离（采样率可能因 App 而异）。 */
    private val voiceChangers = WeakHashMap<AudioRecord, VoiceChanger>()

    /** 每个 AudioRecord 的诊断计数：用来定位"卡段"到底是 DSP 慢还是 Binder 慢。 */
    private class VoiceStats {
        var paramsEncoded: String = ""
        var paramsAt: Long = 0L
        var logAt: Long = 0L
        var reads: Long = 0L
        var bytes: Long = 0L
        var dspNanos: Long = 0L
        var maxDspNanos: Long = 0L
        var slowReads: Long = 0L
        var lastReadAt: Long = 0L
        var maxGapMs: Long = 0L
        var reqBytes: Long = 0L
        var shortReads: Long = 0L
        var wsFrames: Long = 0L
        var wsStarved: Long = 0L
    }

    private val voiceStats = WeakHashMap<AudioRecord, VoiceStats>()

    /** 首次命中 MIC_VOICE 时打一条日志：用来确认 hook 到底跑没跑。 */
    private val voiceEverHit = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * MIC_VOICE：先让目标 App 自己从真麦克风读（它在前台，不会被并发采集规则静音），
     * 再把读到的那块 PCM 就地变声后还给目标 App。
     *
     * 这是唯一能绕开「后台 App 采集一律喂静音」的做法：我们自己不开麦。
     * legacy API 82 的 before 阶段无法调用原方法，proceed() 返回 null，这里回退到放行。
     */
    private fun handleMicVoice(
        appCtx: Context, pkg: String, chain: HookCall, record: AudioRecord,
        buf: ByteArray, offset: Int, size: Int
    ): HookDecision {
        val raw = chain.proceed()
        if (raw == null) return HookDecision.Proceed
        val n = raw as? Int ?: return HookDecision.Replace(raw)
        val st = synchronized(voiceStats) { voiceStats.getOrPut(record) { VoiceStats() } }
        val now = SystemClock.uptimeMillis()

        // 记录两次 read 之间的最大间隔：这个数字直接反映"卡段"
        if (st.lastReadAt > 0L) {
            val gap = now - st.lastReadAt
            if (gap > st.maxGapMs) st.maxGapMs = gap
        }
        st.lastReadAt = now
        st.reqBytes += size.toLong()
        if (n < size) st.shortReads++

        if (n > 0 && n <= size &&
            record.audioFormat == AudioFormat.ENCODING_PCM_16BIT &&
            record.channelCount == 1
        ) {
            runCatching {
                val changer = synchronized(voiceChangers) {
                    voiceChangers.getOrPut(record) { VoiceChanger(record.sampleRate) }
                }
                // 参数查询是 Binder，最多每 500ms 做一次；否则只用本地缓存，
                // 避免在音频线程上周期性抖动。
                if (now - st.paramsAt >= 500L) {
                    st.paramsAt = now
                    val enc = XBridge.resolveVoicePreset(appCtx, pkg)
                    st.paramsEncoded = enc
                    VoiceChanger.applyEncoded(changer, enc)
                } else if (st.paramsEncoded.isNotEmpty() && changer.signature != st.paramsEncoded) {
                    VoiceChanger.applyEncoded(changer, st.paramsEncoded)
                }

                val t0 = System.nanoTime()
                changer.process(buf, offset, n)
                val dt = System.nanoTime() - t0
                val eng = changer.pitchEngineStats()
                st.wsFrames += eng[0]
                st.wsStarved = eng[2]
                st.reads++
                st.bytes += n.toLong()
                st.dspNanos += dt
                if (dt > st.maxDspNanos) st.maxDspNanos = dt
            }
        }
        accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)

        if (now - st.logAt >= 2000L) {
            val reads = if (st.reads > 0L) st.reads else 1L
            Log.i(
                "GlassMic-MicVoice",
                "reads=${st.reads} bytes=${st.bytes} req=${st.reqBytes} short=${st.shortReads} " +
                    "avgDspUs=${st.dspNanos / 1000 / reads} " +
                    "maxDspUs=${st.maxDspNanos / 1000} maxGapMs=${st.maxGapMs} " +
                    "wsFrames=${st.wsFrames} wsStarved=${st.wsStarved} " +
                    "rate=${record.sampleRate} ch=${record.channelCount} " +
                    "fmt=${record.audioFormat} params=${st.paramsEncoded}"
            )
            st.logAt = now
            st.reads = 0L; st.bytes = 0L; st.reqBytes = 0L; st.shortReads = 0L; st.wsFrames = 0L
            st.dspNanos = 0L; st.maxDspNanos = 0L; st.maxGapMs = 0L
        }
        return HookDecision.Replace(n)
    }

    @Volatile private var installed = false

    private fun accumulate(ctx: Context, pkg: String, bytes: Int, sampleRate: Int, channels: Int) {
        XBridge.recordInterception(ctx, pkg, bytes, sampleRate, channels)
    }

    /** libxposed API 101 入口。 */
    fun install(api: XposedInterface, appCtx: Context, callerPkg: String) =
        install(ModernHooker(api, TAG), appCtx, callerPkg)

    /** legacy API 82 入口。 */
    fun installLegacy(appCtx: Context, callerPkg: String) =
        install(LegacyHooker(TAG), appCtx, callerPkg)

    private fun install(hooker: MethodHooker, appCtx: Context, callerPkg: String) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            if (!XposedHookGate.tryMarkAudioHookInstalled()) {
                installed = true
                hooker.log(Log.INFO, "skip duplicate audio hook in $callerPkg")
                return
            }
            installed = true
        }
        val hookMethod = { method: java.lang.reflect.Method?, handler: (Context, String, HookCall) -> HookDecision ->
            if (method != null) hooker.hook(method) { call -> handler(appCtx, callerPkg, call) }
        }

        hookMethod(findMethod(AudioRecord::class.java, "read", ByteArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!), ::handleByteArray)
        hookMethod(findMethod(AudioRecord::class.java, "read", ByteArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!), ::handleByteArray)

        hookMethod(findMethod(AudioRecord::class.java, "read", ShortArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!), ::handleShortArray)
        hookMethod(findMethod(AudioRecord::class.java, "read", ShortArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!), ::handleShortArray)

        hookMethod(findMethod(AudioRecord::class.java, "read", FloatArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!), ::handleFloatArray)

        hookMethod(findMethod(AudioRecord::class.java, "read", ByteBuffer::class.java, Int::class.javaPrimitiveType!!), ::handleByteBuffer)
        hookMethod(findMethod(AudioRecord::class.java, "read", ByteBuffer::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!), ::handleByteBuffer)

        hookMethod(findMethod(AudioRecord::class.java, "release"), ::handleRelease)

        hooker.log(Log.INFO, "installed in $callerPkg")
    }

    private fun handleByteArray(appCtx: Context, pkg: String, chain: HookCall): HookDecision {
        val src = XBridge.resolveSource(appCtx, pkg)
        if (src == SourceType.REAL_MIC) return HookDecision.Proceed

        val buf = chain.arg(0) as ByteArray
        val offset = chain.arg(1) as Int
        val size = chain.arg(2) as Int
        if (!validRange(buf.size, offset, size)) return HookDecision.Proceed

        val record = chain.thisObject as AudioRecord
        if (src == SourceType.MIC_VOICE) return handleMicVoice(appCtx, pkg, chain, record, buf, offset, size)
        if (src == SourceType.SILENCE) {
            // 注入舒适噪声而非纯 0，避免下游 App 把静音判定为"无输入"而中断录音
            val isFloat = record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
            if (isFloat) ComfortNoise.fillFloatBytes(buf, offset, size)
            else ComfortNoise.fillBytes(buf, offset, size)
            val bytesPerFrame = record.channelCount.coerceAtLeast(1) * (if (isFloat) 4 else 2)
            paceSilence(size / bytesPerFrame, record.sampleRate)
            accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
            return HookDecision.Replace(size)
        }

        val reader = obtainReader(appCtx, record)
        val n = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            reader.readFloatBytes(buf, offset, size)
        } else {
            reader.read(buf, offset, size)
        }
        if (n < 0) return HookDecision.Proceed
        if (n < size) java.util.Arrays.fill(buf, offset + n, offset + size, 0.toByte())
        accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
        return HookDecision.Replace(size)
    }

    private fun handleShortArray(appCtx: Context, pkg: String, chain: HookCall): HookDecision {
        val src = XBridge.resolveSource(appCtx, pkg)
        if (src == SourceType.REAL_MIC) return HookDecision.Proceed

        val buf = chain.arg(0) as ShortArray
        val offset = chain.arg(1) as Int
        val sizeInShorts = chain.arg(2) as Int
        if (!validRange(buf.size, offset, sizeInShorts)) return HookDecision.Proceed

        val record = chain.thisObject as AudioRecord
        if (src == SourceType.MIC_VOICE) {
            val raw = chain.proceed()
            if (raw == null) return HookDecision.Proceed
            val n = raw as? Int ?: return HookDecision.Replace(raw)
            if (n > 0 && n <= sizeInShorts && record.channelCount == 1) {
                runCatching {
                    val bytes = ByteArray(n * 2)
                    val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (k in 0 until n) bb.putShort(buf[offset + k])
                    val c = changerOf(record)
                    markHit(appCtx, pkg, record)
                    c.applyParams(XBridge.resolveVoicePreset(appCtx, pkg))
                    c.process(bytes, 0, bytes.size)
                    val rb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (k in 0 until n) buf[offset + k] = rb.short
                }
            }
            accumulate(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
            return HookDecision.Replace(n)
        }
        if (src == SourceType.SILENCE) {
            ComfortNoise.fillShorts(buf, offset, sizeInShorts)
            paceSilence(sizeInShorts / record.channelCount.coerceAtLeast(1), record.sampleRate)
            accumulate(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
            return HookDecision.Replace(sizeInShorts)
        }

        val reader = obtainReader(appCtx, record)
        val byteBuf = ByteArray(sizeInShorts * 2)
        val n = reader.read(byteBuf, 0, byteBuf.size)
        if (n < 0) return HookDecision.Proceed
        val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shortsRead = n / 2
        repeat(shortsRead) { i -> buf[offset + i] = bb.get(i) }
        if (shortsRead < sizeInShorts) {
            java.util.Arrays.fill(buf, offset + shortsRead, offset + sizeInShorts, 0.toShort())
        }
        accumulate(appCtx, pkg, sizeInShorts * 2, record.sampleRate, record.channelCount)
        return HookDecision.Replace(sizeInShorts)
    }

    private fun handleFloatArray(appCtx: Context, pkg: String, chain: HookCall): HookDecision {
        val src = XBridge.resolveSource(appCtx, pkg)
        if (src == SourceType.REAL_MIC) return HookDecision.Proceed

        val buf = chain.arg(0) as FloatArray
        val offset = chain.arg(1) as Int
        val sizeInFloats = chain.arg(2) as Int
        if (!validRange(buf.size, offset, sizeInFloats)) return HookDecision.Proceed

        val record = chain.thisObject as AudioRecord
        if (src == SourceType.MIC_VOICE) {
            val raw = chain.proceed()
            if (raw == null) return HookDecision.Proceed
            val n = raw as? Int ?: return HookDecision.Replace(raw)
            if (n > 0 && n <= sizeInFloats && record.channelCount == 1) {
                runCatching {
                    val bytes = ByteArray(n * 2)
                    val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (k in 0 until n) {
                        val v = (buf[offset + k] * 32767f).toInt().coerceIn(-32768, 32767)
                        bb.putShort(v.toShort())
                    }
                    val c = changerOf(record)
                    markHit(appCtx, pkg, record)
                    c.applyParams(XBridge.resolveVoicePreset(appCtx, pkg))
                    c.process(bytes, 0, bytes.size)
                    val rb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (k in 0 until n) buf[offset + k] = rb.short / 32768f
                }
            }
            accumulate(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
            return HookDecision.Replace(n)
        }
        if (src == SourceType.SILENCE) {
            ComfortNoise.fillFloats(buf, offset, sizeInFloats)
            paceSilence(sizeInFloats / record.channelCount.coerceAtLeast(1), record.sampleRate)
            accumulate(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
            return HookDecision.Replace(sizeInFloats)
        }

        val reader = obtainReader(appCtx, record)
        val byteBuf = ByteArray(sizeInFloats * 2)
        val n = reader.read(byteBuf, 0, byteBuf.size)
        if (n < 0) return HookDecision.Proceed
        val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val floatsRead = n / 2
        repeat(floatsRead) { i -> buf[offset + i] = bb.get(i) / 32768f }
        if (floatsRead < sizeInFloats) {
            java.util.Arrays.fill(buf, offset + floatsRead, offset + sizeInFloats, 0f)
        }
        accumulate(appCtx, pkg, sizeInFloats * 4, record.sampleRate, record.channelCount)
        return HookDecision.Replace(sizeInFloats)
    }

    private fun handleByteBuffer(appCtx: Context, pkg: String, chain: HookCall): HookDecision {
        val src = XBridge.resolveSource(appCtx, pkg)
        if (src == SourceType.REAL_MIC) return HookDecision.Proceed

        val buf = chain.arg(0) as ByteBuffer
        val size = chain.arg(1) as Int
        if (size < 0 || size > buf.remaining()) return HookDecision.Proceed

        val record = chain.thisObject as AudioRecord
        val pos = buf.position()
        if (src == SourceType.MIC_VOICE) {
            val raw = chain.proceed()
            if (raw == null) return HookDecision.Proceed
            val n = raw as? Int ?: return HookDecision.Replace(raw)
            if (n > 0 && n <= size &&
                record.audioFormat == AudioFormat.ENCODING_PCM_16BIT &&
                record.channelCount == 1
            ) {
                runCatching {
                    val tmp = ByteArray(n)
                    buf.position(pos)
                    buf.get(tmp, 0, n)
                    val c = changerOf(record)
                    markHit(appCtx, pkg, record)
                    c.applyParams(XBridge.resolveVoicePreset(appCtx, pkg))
                    c.process(tmp, 0, n)
                    buf.position(pos)
                    buf.put(tmp, 0, n)
                }
            }
            accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
            return HookDecision.Replace(n)
        }
        if (src == SourceType.SILENCE) {
            val isFloat = record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
            if (isFloat) ComfortNoise.putFloat32(buf, size)
            else ComfortNoise.putPcm16(buf, size)
            buf.position(pos)
            val bytesPerFrame = record.channelCount.coerceAtLeast(1) * (if (isFloat) 4 else 2)
            paceSilence(size / bytesPerFrame, record.sampleRate)
            accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
            return HookDecision.Replace(size)
        }

        val reader = obtainReader(appCtx, record)
        val n = if (record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            reader.readFloatBuffer(buf, size)
        } else {
            reader.read(buf, size)
        }
        if (n < 0) {
            buf.position(pos)
            return HookDecision.Proceed
        }
        if (n < size) {
            repeat(size - n) { buf.put(0.toByte()) }
        }
        buf.position(pos)
        accumulate(appCtx, pkg, size, record.sampleRate, record.channelCount)
        return HookDecision.Replace(size)
    }

    private fun changerOf(record: AudioRecord): VoiceChanger =
        synchronized(voiceChangers) { voiceChangers.getOrPut(record) { VoiceChanger(record.sampleRate) } }

    private fun markHit(appCtx: Context, pkg: String, record: AudioRecord) {
        if (voiceEverHit.compareAndSet(false, true)) {
            Log.i(
                "GlassMic-MicVoice",
                "MIC_VOICE 分支首次命中 pkg=$pkg rate=${record.sampleRate} " +
                    "ch=${record.channelCount} fmt=${record.audioFormat} api=${XBridge.apiVersion}"
            )
        }
    }

    private fun handleRelease(appCtx: Context, pkg: String, chain: HookCall): HookDecision {
        val record = chain.thisObject as AudioRecord
        synchronized(readers) {
            readers.remove(record)?.release()
        }
        return HookDecision.Proceed
    }

    private fun obtainReader(ctx: Context, record: AudioRecord): XposedPcmReader {
        synchronized(readers) {
            readers[record]?.let { return it }
            val r = XposedPcmReader(
                ctx,
                sampleRate = record.sampleRate,
                channels = record.channelCount
            )
            readers[record] = r
            return r
        }
    }

    private fun validRange(total: Int, offset: Int, size: Int): Boolean =
        offset >= 0 && size >= 0 && offset <= total && size <= total - offset

    /**
     * SILENCE 路径按实时节流。
     *
     * REAL_MIC 走 chain.proceed() 阻塞到真麦一帧就绪、FILE 走 XposedPcmReader 阻塞读实时管道，
     * 两者天然是实时节奏；而舒适噪声是即时填充、无阻塞——若不节流，下游（微信）的 read() 会以
     * 最快速度空转返回，录音循环远超实时速率、时间线错乱 → 最终化时判"语音被其他应用占用/系统错误"。
     * 这里按本次返回的帧数睡对应时长，补齐与真麦/FILE 一致的实时节奏。
     */
    private fun paceSilence(frames: Int, sampleRate: Int) {
        if (frames <= 0 || sampleRate <= 0) return
        val ms = frames.toLong() * 1000L / sampleRate
        if (ms > 0) runCatching { Thread.sleep(ms) }
    }
}
