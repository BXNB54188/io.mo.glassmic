package io.mo.glassmic.core.audio.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/** 一个变声预设。参数可被 UI 继续微调。 */
data class VoicePreset(
    val id: String,
    val label: String,
    val pitchSemitones: Float,
    val robotHz: Float = 0f,
    val highpassHz: Float = 0f,
    val lowpassHz: Float = 0f,
    val gateDb: Float = -90f,
    val gain: Float = 1f,
    val tremoloHz: Float = 0f,
    val tremoloDepth: Float = 0f,
    val drive: Float = 1f
)

/**
 * 实时变声 DSP（纯 Kotlin，零 Android 依赖）。
 *
 * 同时被 app 进程和注入到目标 App 的 xposed 进程使用，所以放在 core。
 *
 * 链路：DC 阻断 → 低频阻断 → 环形调制 → **4 颗粒变调** → 高频衰减 → 噪声门 → 增益 → 软限幅
 *
 * 颗粒变调用 4 个 Hann 窗读头，间隔 W/4：
 * 四个窗之和恒为 2，除以 2 归一化，交叉点从 2 个摊薄到 4 个，
 * 大幅压制双颗粒方案的"金属颗粒感"。
 *
 * pitch = 0 时整级旁路。
 */
class VoiceChanger(val sampleRate: Int = 48_000) {

    /** 当前参数签名，用于避免重复应用同一套参数。 */
    var signature: String = ""

    var pitchSemitones: Float = 0f
        set(value) { field = value.coerceIn(-24f, 24f) }

    var robotHz: Float = 0f
        set(value) { field = if (value <= 1f) 0f else value.coerceIn(20f, 400f) }

    var highpassHz: Float = 0f
        set(value) { field = if (value <= 1f) 0f else value.coerceIn(20f, 6000f) }

    var lowpassHz: Float = 0f
        set(value) { field = if (value <= 1f) 0f else value.coerceIn(300f, 16000f) }

    var gateDb: Float = -90f
        set(value) { field = value.coerceIn(-90f, 0f) }

    var gain: Float = 1f
        set(value) { field = value.coerceIn(0f, 4f) }

    /** 颤音频率 Hz，0 = 关。 */
    var tremoloHz: Float = 0f
        set(value) { field = if (value <= 0.1f) 0f else value.coerceIn(0.5f, 20f) }

    /** 颤音深度 0..1。 */
    var tremoloDepth: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** 失真驱动 1 = 关。 */
    var drive: Float = 1f
        set(value) { field = value.coerceIn(1f, 8f) }

    private val rate: Float = sampleRate.coerceIn(8_000, 192_000).toFloat()
    private val nrRate: Int = sampleRate.coerceIn(8_000, 192_000)

    // 常开 DC 阻断（约 18Hz），颗粒变调会带直流分量，表现为低频闷响
    private val dcA = 1f / (1f + 2f * PI.toFloat() * 18f / rate)
    private var dcX = 0f
    private var dcY = 0f

    private var hpA = 0f
    private var hpX1 = 0f
    private var hpY1 = 0f
    private var hpHzCache = -1f

    private var lpK = 0f
    private var lpY1 = 0f
    private var lpHzCache = -1f

    private var gateEnv = 0f
    private var gateOpen = false

    private var ringPhase = 0.0
    private var tremPhase = 0.0

    /** 变调内核：MicBoost Tone$Grain 的移植版（50ms 三角窗 + 零权重换向）。 */
    private val grain = GrainShift(sampleRate)

    private val grainRing = FloatArray(GRAIN_RING)
    private var grainWrite = 0
    private var grainPhase = 0.0
    // 三角窗，与 MicBoost 的 Tone$Grain 一致：win[i] = 1 - |2i/W - 1|
    // 两个抽头相差 W/2 时，win(d) + win(d + W/2) 恒为 1，天然互补，不需要额外归一化。
    private val grainWindow = FloatArray(GRAIN_W) { i ->
        val x = i.toDouble() / GRAIN_W
        (1.0 - kotlin.math.abs(x * 2.0 - 1.0)).toFloat()
    }

    /** 就地处理 [buf] 中从 [offset] 开始的 [length] 字节（PCM16 单声道）。 */
    fun process(buf: ByteArray, offset: Int, length: Int): Boolean {
        val usable = length and 0x7FFFFFFE
        if (usable <= 0 || offset < 0 || offset + usable > buf.size) return false

        val pitchRatio = 2.0.pow(pitchSemitones / 12.0).toFloat()
        val useGrain = abs(pitchRatio - 1f) > 0.002f
        val useRing = robotHz > 0f
        val useHp = highpassHz > 0f
        val useLp = lowpassHz > 0f
        val useGate = gateDb > -89f
        val gainNow = gain

        if (!useGrain && !useRing && !useHp && !useLp && !useGate && !useTrem && !useDrive &&
            abs(gainNow - 1f) <= 0.01f
        ) {
            return false
        }

        if (useHp && hpHzCache != highpassHz) {
            hpHzCache = highpassHz
            hpA = 1f / (1f + 2f * PI.toFloat() * highpassHz / rate)
        }
        if (useLp && lpHzCache != lowpassHz) {
            lpHzCache = lowpassHz
            lpK = 1f - exp(-2f * PI.toFloat() * lowpassHz / rate)
        }
        val ringStep = if (useRing) 2.0 * PI * robotHz / rate else 0.0
        val useTrem = tremoloHz > 0f && tremoloDepth > 0f
        val tremStep = if (useTrem) 2.0 * PI * tremoloHz / rate else 0.0
        val useDrive = drive > 1.01f
        val gateLin = 10f.pow(gateDb / 20f)

        val end = offset + usable
        var i = offset
        while (i < end) {
            var s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toFloat() / 32768f

            val dc = dcA * (dcY + s - dcX)
            dcX = s; dcY = dc; s = dc

            if (useHp) {
                val y = hpA * (hpY1 + s - hpX1)
                hpX1 = s; hpY1 = y; s = y
            }

            if (useRing) {
                s *= cos(ringPhase).toFloat()
                ringPhase += ringStep
                if (ringPhase >= 2.0 * PI) ringPhase -= 2.0 * PI
            }

            if (useGrain) {
                grain.ratio = pitchRatio.toDouble()
                s = grain.push(s)
            }

            if (useLp) {
                lpY1 += lpK * (s - lpY1)
                s = lpY1
            }

            if (useDrive) {
                // 逐采样软饱和：不会引入任何时间轴行为
                val x = s * drive
                s = x / (1f + kotlin.math.abs(x))
                s *= 1.6f   // 补偿 tanh 型曲线的电平损失
            }

            if (useTrem) {
                val lfo = 0.5 - 0.5 * cos(tremPhase)          // 0..1
                s *= (1f - tremoloDepth * lfo)
                tremPhase += tremStep
                if (tremPhase >= 2.0 * PI) tremPhase -= 2.0 * PI
            }

            if (useGate) {
                val mag = abs(s)
                if (mag > gateEnv) gateEnv = mag else gateEnv += (mag - gateEnv) * 0.0015f
                if (!gateOpen && gateEnv > gateLin * 2f) gateOpen = true
                if (gateOpen && gateEnv < gateLin) gateOpen = false
                if (!gateOpen) s = 0f
            }

            s *= gainNow
            val amp = if (s < 0f) -s else s
            if (amp > SOFT_KNEE) {
                val over = amp - SOFT_KNEE
                val limited = SOFT_KNEE + (1f - SOFT_KNEE) * (1f - exp(-over * SOFT_RATIO))
                s = if (s < 0f) -limited else limited
            }

            val v = (s * 32767f).toInt()
            buf[i] = (v and 0xFF).toByte()
            buf[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
        return true
    }

    fun process(buf: ByteArray, length: Int): Boolean = process(buf, 0, length)

    /** 用编码串更新参数（签名未变时不做任何事）。 */
    fun applyParams(encoded: String) {
        applyEncoded(this, encoded)
    }

    /** 诊断：[已生成帧数, 累计耗时纳秒, 饥饿采样数(累计)] */
    fun pitchEngineStats(): LongArray = longArrayOf(grain.produced, 0L, 0L)

    private fun tap(delay: Double): Float {
        var pos = grainWrite - delay
        while (pos < 0.0) pos += GRAIN_RING.toDouble()
        while (pos >= GRAIN_RING.toDouble()) pos -= GRAIN_RING.toDouble()
        val i0 = floor(pos).toInt()
        val frac = (pos - i0).toFloat()
        val a = grainRing[i0 % GRAIN_RING]
        val b = grainRing[(i0 + 1) % GRAIN_RING]
        return a + (b - a) * frac
    }

    fun reset() {
        hpX1 = 0f; hpY1 = 0f
        lpY1 = 0f
        gateEnv = 0f; gateOpen = false
        dcX = 0f; dcY = 0f
        ringPhase = 0.0
        tremPhase = 0.0
        grainRing.fill(0f); grainWrite = 0; grainPhase = 0.0
        grain.reset()
    }

    companion object {
        // 2 抽头：4 抽头会让多个不同延迟的副本叠加，对音调信号产生梳状滤波，
        // 金属味反而更重。MicBoost 也是 2 抽头。
        private const val GRAINS = 2
        // 窗口 4096（93ms @44.1k）：读头的相位环绕周期 = W/(1-ratio)，
        // 对 -12 半音(ratio 0.5) 从 93ms 拉长到 186ms，跳变次数减半，
        // 听感从"每秒碎 10 次"变成"缓慢起伏"。
        private const val GRAIN_W = 4096
        private const val GRAIN_RING = 16384
        private const val GRAIN_NORM = 1f   // 三角窗互补，和恒为 1
        private const val SOFT_KNEE = 0.9f
        private const val SOFT_RATIO = 8f

        const val DEFAULT_SAMPLE_RATE = 48_000
        const val PARAM_FALLBACK = "off|0|0|0|0|-90|1.0|0.0|0.0|1.0"

        /**
         * 预设全部不使用变调（pitch 一律为 0）。
         *
         * 原因：时间拉伸类变调（颗粒/WSOLA）在部分 App 的大块读取录音管线上会产生
         * 断断续续的听感（上游 issue #25 同类问题）。而环形调制、滤波、失真、颤音
         * 都是逐采样无状态运算，实测干净、零风险。
         */
        val PRESETS: List<VoicePreset> = listOf(
            VoicePreset("off", "原声", 0f),
            VoicePreset("robot", "机器人", 0f, robotHz = 55f, lowpassHz = 6000f),
            VoicePreset("minion", "小黄人", 0f, robotHz = 95f, highpassHz = 300f, lowpassHz = 5200f),
            VoicePreset("alien", "外星人", 0f, robotHz = 140f, highpassHz = 500f, drive = 1.6f),
            VoicePreset("phone", "电话音", 0f, highpassHz = 400f, lowpassHz = 3400f, gain = 1.3f),
            VoicePreset("radio", "老收音机", 0f, highpassHz = 620f, lowpassHz = 2600f, drive = 1.9f, gain = 1.15f),
            VoicePreset("deep", "低沉", 0f, lowpassHz = 2200f, gain = 1.15f),
            VoicePreset("husky", "沙哑", 0f, drive = 2.4f, lowpassHz = 5000f),
            VoicePreset("tremble", "颤抖", 0f, tremoloHz = 6f, tremoloDepth = 0.6f),
            VoicePreset("cyber", "电子音", 0f, robotHz = 30f, tremoloHz = 4f, tremoloDepth = 0.4f),
            VoicePreset("pitch_up", "变调·童声(实验)", 7f, highpassHz = 120f),
            VoicePreset("pitch_down", "变调·低沉(实验)", -6f, lowpassHz = 4500f)
        )

        fun presetById(id: String): VoicePreset? = PRESETS.firstOrNull { it.id == id }

        /** 编码成 `id|pitch|robot|hp|lp|gate|gain`，跨进程只传这一条字符串。 */
        fun encode(id: String, p: VoicePreset): String =
            "$id|${p.pitchSemitones}|${p.robotHz}|${p.highpassHz}|${p.lowpassHz}|${p.gateDb}|${p.gain}" +
                "|${p.tremoloHz}|${p.tremoloDepth}|${p.drive}"

        fun encodeCurrent(id: String, c: VoiceChanger): String =
            "$id|${c.pitchSemitones}|${c.robotHz}|${c.highpassHz}|${c.lowpassHz}|${c.gateDb}|${c.gain}" +
                "|${c.tremoloHz}|${c.tremoloDepth}|${c.drive}"

        /** 解析并应用；签名没变就直接返回 false，不打断 DSP 状态。 */
        fun applyEncoded(target: VoiceChanger, encoded: String): Boolean {
            val raw = encoded.ifBlank { PARAM_FALLBACK }
            if (target.signature == raw) return false
            val f = raw.split('|')
            if (f.size < 7) {
                target.signature = raw
                return false
            }
            target.pitchSemitones = f[1].toFloatOrNull() ?: 0f
            target.robotHz = f[2].toFloatOrNull() ?: 0f
            target.highpassHz = f[3].toFloatOrNull() ?: 0f
            target.lowpassHz = f[4].toFloatOrNull() ?: 0f
            target.gateDb = f[5].toFloatOrNull() ?: -90f
            target.gain = f[6].toFloatOrNull() ?: 1f
            target.tremoloHz = if (f.size > 7) f[7].toFloatOrNull() ?: 0f else 0f
            target.tremoloDepth = if (f.size > 8) f[8].toFloatOrNull() ?: 0f else 0f
            target.drive = if (f.size > 9) f[9].toFloatOrNull() ?: 1f else 1f
            target.signature = raw
            return true
        }

        /** 由预设生成编码串。 */
        fun encodePreset(preset: VoicePreset): String = encode(preset.id, preset)
    }
}
