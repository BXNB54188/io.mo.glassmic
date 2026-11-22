package io.mo.glassmic.core.audio.voice

import kotlin.math.abs
import kotlin.math.floor

/**
 * 变调内核：移植自 MicBoost `Tone$Grain` 的延迟线颗粒变调。
 *
 * 关键设计（也是它不像教科书 WSOLA 那样"断断续续"的原因）：
 *
 * 三角窗 `win[i] = 1 - |2i/L - 1|`，两个抽头相差 L/2，权重分别是 w 和 1-w。
 *   - 抽头1 在 d -> L 时换向，而 win(L) = 0  →  换向瞬间权重为 0
 *   - 抽头2 在 d -> L/2 时换向，此时 w = 1 → 1-w = 0 → 同样权重为 0
 *
 * 所以**相位跳变永远发生在权重为 0 的位置**，听感上没有咔哒、没有断口。
 * 窗口取 50ms（对 44.1k 是 2205 采样）。
 *
 * 速率天然平衡：每推一个输入采样就吐一个输出采样，不存在缓冲漂移。
 */
internal class GrainShift(sampleRate: Int) {

    /** 目标变调比 2^(半音/12)。 */
    var ratio: Double = 1.0

    private val l: Int = (sampleRate.coerceIn(8_000, 192_000) * WINDOW_MS / 1000).coerceAtLeast(256)
    private val size: Int = Integer.highestOneBit(l * 2 - 1) shl 1   // >= 2L 的 2 的幂
    private val mask: Long = (size - 1).toLong()

    private val buf = FloatArray(size)
    private val win = FloatArray(l) { i ->
        (1.0 - abs(i.toDouble() * 2.0 / l - 1.0)).toFloat()
    }

    private var n: Long = 0L
    private var d: Double = 0.0

    fun push(x: Float): Float {
        buf[(n and mask).toInt()] = x
        n++
        // 前一个窗口填充期输出静音（只在启动时发生一次，约 50ms）
        if (n < l + 4L) return 0f

        var d2 = d + l * 0.5
        if (d2 >= l) d2 -= l

        val w = win[d.toInt() % l]
        val w2 = 1f - w

        val out = read(d) * w + read(d2) * w2

        d += 1.0 - ratio
        if (d >= l) d -= l
        else if (d < 0.0) d += l

        return out
    }

    private fun read(delay: Double): Float {
        val p = (n - 1) - delay
        val f = floor(p)
        val frac = (p - f).toFloat()
        val i = f.toLong()
        val a = buf[(i and mask).toInt()]
        val b = buf[((i + 1) and mask).toInt()]
        return a + (b - a) * frac
    }

    fun reset() {
        buf.fill(0f)
        n = 0L
        d = 0.0
    }

    val produced: Long get() = n

    private companion object {
        const val WINDOW_MS = 50
    }
}
