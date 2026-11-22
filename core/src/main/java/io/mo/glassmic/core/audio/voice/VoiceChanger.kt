package io.mo.glassmic.core.audio.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/** 一个变声预设。 */
data class VoicePreset(
    val id: String,
    val label: String,
    val pitchSemitones: Float,
    val robotHz: Float = 0f,
    val highpassHz: Float = 0f,
    val lowpassHz: Float = 0f,
    val gateDb: Float = -90f,
    val gain: Float = 1f
)

/**
 * 实时变声 DSP（纯 Kotlin，零 Android 依赖）。
 *
 * 同时被 app 进程和**注入到目标 App 的 xposed 进程**使用，所以放在 core：
 * core 不允许依赖 androidx / Hilt，这里正好只有 kotlin.math。
 *
 * 链路：低频阻断 → 环形调制 → 颗粒变调 → 高频衰减 → 噪声门 → 增益 → 限幅
 *
 * 采样率由构造参数决定（目标 App 常见 44100，我们自己的主时钟是 48000）。
 * pitch = 0 时颗粒变调整级旁路，只留几个采样的开销。
 */
class VoiceChanger(val sampleRate: Int = 48_000) {

    /** 当前生效的预设 id，用于避免重复应用同一个预设。 */
    var presetId: String = "off"

    var pitchSemitones: Float = 0f
        set(value) { field = value.coerceIn(-24f, 24f) }

    var robotHz: Float = 0f
        set(value) { field = if (value <= 1f) 0f else value.coerceIn(20f, 400f) }

    var highpassHz: Float = 0f
        set(value) { field = if (value <= 1f) 0f else value.coerceIn(20f, 6000f) }

    var lowpassHz: Float = 0f
        set(value) { field = if (value <= 1f) 0f else value.coerceIn(300f, 20000f) }

    var gateDb: Float = -90f
        set(value) { field = value.coerceIn(-90f, 0f) }

    var gain: Float = 1f
        set(value) { field = value.coerceIn(0f, 8f) }

    private val rate: Float = sampleRate.coerceIn(8_000, 192_000).toFloat()

    private var hpA = 0f
    private var hpX1 = 0f
    private var hpY1 = 0f
    private var hpHzCache = -1f

    private var lpK = 0f
    private var lpY1 = 0f
    private var lpHzCache = -1f

    private var gateEnv = 0f
    private var gateOpen = false

    // 常开 DC 阻断（约 18Hz 一阶高通），系数按构造时的采样率算死
    private val dcA = 1f / (1f + 2f * PI.toFloat() * 18f / sampleRate.coerceIn(8_000, 192_000))
    private var dcX = 0f
    private var dcY = 0f

    private var ringPhase = 0.0

    private val grainRing = FloatArray(GRAIN_RING)
    private var grainWrite = 0
    private var grainPhase = 0.0
    private val grainWindow = FloatArray(GRAIN_W) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / GRAIN_W)).toFloat()
    }

    /** 就地处理 [buf] 中从 [offset] 开始的 [length] 个字节（PCM16 单声道）。 */
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

        if (!useGrain && !useRing && !useHp && !useLp && !useGate && abs(gainNow - 1f) <= 0.01f) {
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
        val gateLin = 10f.pow(gateDb / 20f)

        val end = offset + usable
        var i = offset
        while (i < end) {
            var s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toFloat() / 32768f

            if (useHp) {
                val y = hpA * (hpY1 + s - hpX1)
                hpX1 = s; hpY1 = y; s = y
            }

            // 常开 DC 阻断：颗粒变调会引入直流分量，表现为低频"砰/咚"的闷响。
            val dc = dcA * (dcY + s - dcX)
            dcX = s; dcY = dc; s = dc

            if (useRing) {
                s *= cos(ringPhase).toFloat()
                ringPhase += ringStep
                if (ringPhase >= 2.0 * PI) ringPhase -= 2.0 * PI
            }

            if (useGrain) {
                grainRing[grainWrite] = s
                val w1 = grainWindow[grainPhase.toInt() % GRAIN_W]
                val w2 = grainWindow[(grainPhase.toInt() + GRAIN_W / 2) % GRAIN_W]
                val d1 = grainPhase
                val d2 = (grainPhase + GRAIN_W / 2.0) % GRAIN_W
                s = tap(d1) * w1 + tap(d2) * w2
                grainWrite = (grainWrite + 1) % GRAIN_RING
                grainPhase += 1.0 - pitchRatio
                while (grainPhase < 0.0) grainPhase += GRAIN_W.toDouble()
                while (grainPhase >= GRAIN_W.toDouble()) grainPhase -= GRAIN_W.toDouble()
            }

            if (useLp) {
                lpY1 += lpK * (s - lpY1)
                s = lpY1
            }

            if (useGate) {
                val mag = abs(s)
                if (mag > gateEnv) gateEnv = mag else gateEnv += (mag - gateEnv) * 0.0015f
                if (!gateOpen && gateEnv > gateLin * 2f) gateOpen = true
                if (gateOpen && gateEnv < gateLin) gateOpen = false
                if (!gateOpen) s = 0f
            }

            s *= gainNow
            // 软限幅：0.9 以下原样通过，超过部分用指数曲线压回去。
            // 原来是硬 clamp(±1)，溢出时直接切成方波，听起来就是"爆音"。
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
        grainRing.fill(0f); grainWrite = 0; grainPhase = 0.0
    }

    companion object {
        // 窗口从 1024 提到 2048：幅度更大的变调（如 +12 半音）时，
        // 相位环绕周期翻倍，颗粒抖动率减半，听起来不再"炸"。
        private const val GRAIN_W = 2048
        private const val GRAIN_RING = 8192
        private const val SOFT_KNEE = 0.9f
        private const val SOFT_RATIO = 8f

        val DEFAULT_SAMPLE_RATE = 48_000

        val PRESETS: List<VoicePreset> = listOf(
            VoicePreset("off", "原声", 0f),
            VoicePreset("child", "童声", 7f, highpassHz = 120f, gain = 1.05f),
            VoicePreset("chipmunk", "花栗鼠", 12f, highpassHz = 200f),
            VoicePreset("deep", "低沉大叔", -6f, lowpassHz = 4500f, gain = 1.1f),
            VoicePreset("monster", "怪兽", -12f, lowpassHz = 3200f, gain = 1.2f),
            VoicePreset("robot", "机器人", 0f, robotHz = 55f, lowpassHz = 6000f),
            VoicePreset("alien", "外星人", 5f, robotHz = 120f, highpassHz = 300f),
            VoicePreset("phone", "电话音", 0f, highpassHz = 400f, lowpassHz = 3400f, gain = 1.3f)
        )

        fun presetById(id: String): VoicePreset? = PRESETS.firstOrNull { it.id == id }

        fun applyPreset(target: VoiceChanger, preset: VoicePreset) {
            target.reset()
            target.pitchSemitones = preset.pitchSemitones
            target.robotHz = preset.robotHz
            target.highpassHz = preset.highpassHz
            target.lowpassHz = preset.lowpassHz
            target.gateDb = preset.gateDb
            target.gain = preset.gain
            target.presetId = preset.id
        }

        fun applyPresetById(target: VoiceChanger, id: String): Boolean {
            val p = presetById(id) ?: return false
            if (target.presetId == id) return true
            applyPreset(target, p)
            return true
        }
    }
}
