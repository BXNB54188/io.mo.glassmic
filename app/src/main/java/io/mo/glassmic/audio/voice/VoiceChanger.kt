package io.mo.glassmic.audio.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/**
 * 一个变声预设。所有参数都在 [VoiceChanger] 里实时可改，切换不需要重建对象。
 */
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
 * 实时变声 DSP（纯 Kotlin，不依赖 Android，可以在 JVM 单测里直接跑）。
 *
 * 工作格式固定 48kHz / 单声道 / PCM16，和 GlassMic 的主时钟一致，
 * 所以进出是同一块 ByteArray，原地处理，不需要重采样。
 *
 * 处理顺序：
 *   输入 -> 低频阻断(HP) -> 环形调制(robot) -> 颗粒变调(pitch)
 *        -> 高频衰减(LP) -> 噪声门 -> 输出增益 -> 限幅
 *
 * 延迟：颗粒变调只有在 pitch != 0 时才启用，启用后固有窗口一半约 21ms；
 * pitch = 0 时整级旁路，链路只有几个采样。
 */
class VoiceChanger {

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

    /** 当前处理链是否真的在改变声音（供 UI / 诊断判断）。 */
    val isActive: Boolean
        get() = abs(pitchSemitones) > 0.01f || robotHz > 0f ||
            highpassHz > 0f || lowpassHz > 0f || abs(gain - 1f) > 0.01f || gateDb > -89f

    // ---- 低频阻断：一阶高通 ----
    private var hpA = 0f
    private var hpX1 = 0f
    private var hpY1 = 0f
    private var hpHzCache = -1f

    // ---- 高频衰减：一阶低通 ----
    private var lpK = 0f
    private var lpY1 = 0f
    private var lpHzCache = -1f

    // ---- 噪声门 ----
    private var gateEnv = 0f
    private var gateOpen = false

    // ---- 环形调制 ----
    private var ringPhase = 0.0

    // ---- 颗粒变调：双抽头互补 Hann 窗 ----
    private val grainRing = FloatArray(GRAIN_RING)
    private var grainWrite = 0
    private var grainPhase = 0.0
    private val grainWindow = FloatArray(GRAIN_W) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / GRAIN_W)).toFloat()
    }

    /**
     * 原地处理一块 PCM16。返回是否真的做了处理（false = 全旁路）。
     * [length] 必须是偶数，奇数字节会被忽略。
     */
    fun process(buf: ByteArray, length: Int): Boolean {
        val usable = length and 0x7FFFFFFE
        if (usable <= 0) return false

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
            hpA = 1f / (1f + 2f * PI.toFloat() * highpassHz / SAMPLE_RATE)
        }
        if (useLp && lpHzCache != lowpassHz) {
            lpHzCache = lowpassHz
            lpK = 1f - exp(-2f * PI.toFloat() * lowpassHz / SAMPLE_RATE)
        }
        val ringStep = if (useRing) 2.0 * PI * robotHz / SAMPLE_RATE else 0.0
        val gateLin = 10f.pow(gateDb / 20f)

        var i = 0
        while (i < usable) {
            var s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toFloat() / 32768f

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
            if (s > 1f) s = 1f else if (s < -1f) s = -1f

            val v = (s * 32767f).toInt()
            buf[i] = (v and 0xFF).toByte()
            buf[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
        return true
    }

    /** 线性插值读颗粒缓冲里 delay 个样本之前的值。 */
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
        ringPhase = 0.0
        grainRing.fill(0f); grainWrite = 0; grainPhase = 0.0
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val GRAIN_W = 1024          // 窗口 21.3ms
        private const val GRAIN_RING = 4096

        val PRESETS: List<VoicePreset> = listOf(
            VoicePreset("off", "原声", 0f),
            VoicePreset("child", "童声", 7f, highpassHz = 120f, gain = 1.1f),
            VoicePreset("chipmunk", "花栗鼠", 12f, highpassHz = 200f),
            VoicePreset("deep", "低沉大叔", -6f, lowpassHz = 4500f, gain = 1.2f),
            VoicePreset("monster", "怪兽", -12f, lowpassHz = 3200f, gain = 1.4f),
            VoicePreset("robot", "机器人", 0f, robotHz = 55f, lowpassHz = 6000f),
            VoicePreset("alien", "外星人", 5f, robotHz = 120f, highpassHz = 300f),
            VoicePreset("phone", "电话音", 0f, highpassHz = 400f, lowpassHz = 3400f, gain = 1.6f)
        )

        fun applyPreset(target: VoiceChanger, preset: VoicePreset) {
            target.reset()
            target.pitchSemitones = preset.pitchSemitones
            target.robotHz = preset.robotHz
            target.highpassHz = preset.highpassHz
            target.lowpassHz = preset.lowpassHz
            target.gateDb = preset.gateDb
            target.gain = preset.gain
        }
    }
}
