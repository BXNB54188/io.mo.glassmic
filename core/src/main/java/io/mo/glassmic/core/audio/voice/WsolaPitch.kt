package io.mo.glassmic.core.audio.voice

import kotlin.math.PI
import kotlin.math.cos

/**
 * 流式 WSOLA 变调内核（波形相似度对齐的重叠相加）。
 *
 * 与颗粒变调的关键区别：颗粒法是让读头按 (1-ratio) 匀速滑动，
 * 到边界时**盲目跳回**，跳回处波形相位断裂 —— 听感就是每秒十几次"卡"。
 *
 * WSOLA 在每次拼接前，先在 ±[SEARCH] 采样范围内搜索与"上一帧自然延续"
 * 最相似的起点，把拼接点放在波形天然重合的位置，从而消除相位不连续。
 *
 * 代价：约 (W + CORR + SEARCH) 个采样的固有延迟（44.1k 下约 29ms）。
 *
 * 接入方式：每推送一个采样，返回一个已变调的采样；内部按需推进帧。
 */
internal class WsolaPitch {

    /** 目标变调比（2^(半音/12)）。1 = 不变。 */
    var ratio: Float = 1f

    private val inRing = FloatArray(IN_SIZE)
    private var inWrite = 0
    private var inCount = 0L

    private val ola = FloatArray(OUT_SIZE)
    private var olaWrite = 0L
    private var outRead = 0L

    private var pos = 0.0
    private var prevStart = -1L
    private var primed = false

    /** 诊断：已生成帧数 / 帧生成耗时累计（纳秒）。 */
    var frames: Long = 0L
        private set
    var frameNanos: Long = 0L
        private set

    private val win = FloatArray(W) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / W)).toFloat()
    }

    fun push(x: Float): Float {
        inRing[inWrite % IN_SIZE] = x
        inWrite++
        inCount++

        if (inCount < LOOKAHEAD) return 0f

        if (!primed) {
            primed = true
            pos = 0.0
            prevStart = -1L
            olaWrite = 0L
            outRead = 0L
            ola.fill(0f)
        }

        var guard = 0
        while (outRead >= olaWrite && guard++ < 8) {
            if (!produceFrame()) return 0f
        }
        if (outRead >= olaWrite) return 0f

        return ola[(outRead++ % OUT_SIZE).toInt()]
    }

    private fun produceFrame(): Boolean {
        val start = pos.toInt()
        if (start < 0) return false
        if (start.toLong() + W + SEARCH > inCount) return false

        val t0 = System.nanoTime()
        val delta = bestDelta(start)
        val fs = start + delta
        if (fs < 0 || fs.toLong() + W > inCount) return false

        val base = olaWrite
        // 新帧会写入 [base, base+W)；其中 [base+W-HS, base+W) 上一帧没碰过，必须先清零
        for (k in (W - HS) until W) {
            ola[((base + k) % OUT_SIZE).toInt()] = 0f
        }
        for (k in 0 until W) {
            val idx = ((base + k) % OUT_SIZE).toInt()
            val src = ((fs + k).toLong() % IN_SIZE).toInt()
            ola[idx] += inRing[src] * win[k]
        }

        prevStart = fs.toLong()
        olaWrite = base + HS
        pos += HS.toDouble() * ratio

        frames++
        frameNanos += System.nanoTime() - t0
        return true
    }

    /** 在 ±SEARCH 内找与「上一帧自然延续」相关系数最大的偏移。 */
    private fun bestDelta(start: Int): Int {
        if (prevStart < 0L) return 0
        val tgt = (prevStart + HS)
        if (tgt + CORR > inCount) return 0

        var best = 0
        var bestScore = -Double.MAX_VALUE
        for (d in -SEARCH..SEARCH) {
            val p = start + d
            if (p < 0 || p.toLong() + CORR > inCount) continue
            var s = 0.0
            for (k in 0 until CORR) {
                s += inRing[((p + k).toLong() % IN_SIZE).toInt()] *
                    inRing[((tgt + k) % IN_SIZE).toInt()]
            }
            if (s > bestScore) {
                bestScore = s
                best = d
            }
        }
        return best
    }

    fun reset() {
        inRing.fill(0f); inWrite = 0; inCount = 0
        ola.fill(0f); olaWrite = 0; outRead = 0
        pos = 0.0; prevStart = -1; primed = false
        frames = 0; frameNanos = 0
    }

    private companion object {
        const val W = 1024           // 帧长（23ms @44.1k）
        const val HS = 512           // 合成步长，50% 重叠
        const val SEARCH = 128       // 相似度搜索半径
        const val CORR = 128         // 相关窗口
        const val IN_SIZE = 32768
        const val OUT_SIZE = 16384
        const val LOOKAHEAD = W + CORR + SEARCH
    }
}
