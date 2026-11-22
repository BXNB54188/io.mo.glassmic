package io.mo.glassmic.core.audio.voice

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 频域变调：Stephan M. Bernsee 的 STFT 相位声码器（与 TarsosDSP PitchShifter 同一算法）。
 *
 * 为什么换成它：
 *   时域颗粒 / WSOLA 直接搬采样点，在目标 App 的 80ms 大块输入下会产生周期性相位断裂，
 *   听感就是"断断续续"。相位声码器在频域旋转相位再逆变换，**根本不搬采样点**，
 *   因此没有这种断口；代价是固有延迟约一个 FFT 窗（1024 / 44.1k ≈ 23ms）。
 *
 * 流式接口：每推入一个采样返回一个采样；内部按 hop 分帧、重叠相加。
 */
internal class PhaseVocoderPitch(private val sampleRate: Int) {

    /** 目标变调比 2^(半音/12)。1 = 不变。 */
    var ratio: Float = 1f

    private val n = 1024                 // FFT 帧长
    private val osamp = 4                // 4 倍重叠
    private val step = n / osamp         // 每次前进的采样数
    private val n2 = n / 2
    private val latency = n - step

    private val freqPerBin = sampleRate.coerceIn(8_000, 192_000).toDouble() / n
    private val expct = 2.0 * PI * step / n

    private val inFifo = FloatArray(n)
    private val outFifo = FloatArray(n)
    private val outAccum = FloatArray(n * 2)
    private val lastPhase = DoubleArray(n2 + 1)
    private val sumPhase = DoubleArray(n2 + 1)
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val window = FloatArray(n) { k ->
        (-0.5 * cos(2.0 * PI * k / n) + 0.5).toFloat()
    }

    private var rover = 0
    private var primed = false
    var frames: Long = 0L
        private set

    fun push(x: Float): Float {
        if (!primed) {
            rover = latency
            primed = true
        }
        inFifo[rover] = x
        val out = outFifo[rover - latency]
        rover++
        if (rover >= n) {
            rover = latency
            processFrame()
        }
        return out
    }

    private fun processFrame() {
        // ---------- 分析 ----------
        for (k in 0 until n) {
            re[k] = (inFifo[k] * window[k]).toDouble()
            im[k] = 0.0
        }
        fft(re, im, -1)

        // ---------- 相位旋转 ----------
        val r = ratio.coerceAtLeast(0.05f).toDouble()
        for (k in 0..n2) {
            val real = re[k]
            val imag = im[k]
            val magn = 2.0 * sqrt(real * real + imag * imag)
            val phase = atan2(imag, real)

            var tmp = phase - lastPhase[k]
            lastPhase[k] = phase

            // 减去期望相位差
            tmp -= k.toDouble() * expct
            // 把相位差折叠到 ±π
            var qpd = (tmp / PI).toInt()
            if (qpd >= 0) qpd += (qpd and 1) else qpd -= (qpd and 1)
            tmp -= PI * qpd.toDouble()
            // 得到相对 bin 中心的偏移，还原出真实瞬时频率
            tmp = osamp * tmp / (2.0 * PI)
            val trueFreq = k.toDouble() * freqPerBin + tmp * freqPerBin

            // 变调比作用在频率上（Bernsee 原式），再累积成合成相位
            sumPhase[k] += trueFreq * r
            val outPhase = sumPhase[k]
            re[k] = magn * cos(outPhase)
            im[k] = magn * sin(outPhase)
        }
        // 补齐共轭对称，保证逆变换是实信号
        for (k in 1 until n2) {
            re[n - k] = re[k]
            im[n - k] = -im[k]
        }
        im[0] = 0.0
        im[n2] = 0.0

        // ---------- 合成 ----------
        fft(re, im, 1)

        // Bernsee 原式：2*window*re/(n2*osamp)。这里把系数拆开写，
        // 避免和下面的 2.0 重复相乘导致整体增益翻倍（会一路顶到软限幅 → 沙哑）。
        val norm = 1.0 / n2 / osamp
        for (k in 0 until n) {
            outAccum[k] += (2.0 * window[k] * re[k] * norm).toFloat()
        }
        for (k in 0 until step) {
            outFifo[k] = outAccum[k]
        }
        System.arraycopy(outAccum, step, outAccum, 0, n)
        for (k in n until n * 2) outAccum[k] = 0f

        // 输入 FIFO 前移
        System.arraycopy(inFifo, step, inFifo, 0, n - step)

        frames++
    }

    /** 原地基 2 Cooley-Tukey FFT。sign = -1 正变换，+1 逆变换。 */
    private fun fft(re: DoubleArray, im: DoubleArray, sign: Int) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = sign * 2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val i1 = i + k
                    val i2 = i1 + len / 2
                    val xr = re[i2] * cr - im[i2] * ci
                    val xi = re[i2] * ci + im[i2] * cr
                    re[i2] = re[i1] - xr
                    im[i2] = im[i1] - xi
                    re[i1] += xr
                    im[i1] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun reset() {
        inFifo.fill(0f); outFifo.fill(0f); outAccum.fill(0f)
        lastPhase.fill(0.0); sumPhase.fill(0.0)
        re.fill(0.0); im.fill(0.0)
        rover = 0; primed = false; frames = 0
    }
}
