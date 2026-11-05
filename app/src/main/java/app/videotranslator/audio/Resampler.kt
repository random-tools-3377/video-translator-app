package app.videotranslator.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming sample-rate converter for mono float audio, by windowed-sinc interpolation.
 *
 * When going down (48 kHz to 16 kHz, say) the filter's cutoff sits below the new Nyquist
 * frequency, so everything above 8 kHz is removed before it can fold back as noise: the input
 * is low-passed, not just thinned out.
 *
 * Feed it with [process] in chunks of any size and finish with [flush]; the output is the same
 * as converting the whole signal at once. Output sample n sits at time n / outRate, measured
 * from the first input sample.
 */
class Resampler(
    val inRate: Int,
    val outRate: Int = 16_000,
    zeroCrossings: Int = 16,
    rolloff: Double = 0.92,
    kaiserBeta: Double = 9.0,
) {
    init {
        require(inRate > 0 && outRate > 0) { "bad rates $inRate -> $outRate" }
    }

    // inRate / outRate = step / q, in lowest terms: each output sample advances step / q input samples.
    private val step: Long
    private val q: Long
    private val phases: Int
    private val half: Int
    private val taps: Int
    private val table: FloatArray

    init {
        val g = gcd(inRate.toLong(), outRate.toLong())
        step = inRate / g
        q = outRate / g
        // Exact phases when there are few of them (44.1 kHz to 16 kHz needs 160), otherwise
        // the nearest of 1024, which is far below audible error.
        phases = min(q, 1024L).toInt()

        // Cutoff as a fraction of the input sample rate.
        val cutoff = 0.5 * rolloff * min(1.0, outRate.toDouble() / inRate)
        half = ceil(zeroCrossings / (2.0 * cutoff)).toInt()
        taps = 2 * half
        table = FloatArray(phases * taps)
        val i0 = besselI0(kaiserBeta)
        for (p in 0 until phases) {
            val frac = p.toDouble() / phases
            var sum = 0.0
            val row = p * taps
            for (j in 0 until taps) {
                // Distance from the output position to input sample (center - half + 1 + j).
                val d = (j - half + 1) - frac
                val x = 2.0 * cutoff * d
                val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                val r = d / half
                val w = if (abs(r) >= 1.0) 0.0 else besselI0(kaiserBeta * sqrt(1.0 - r * r)) / i0
                val v = 2.0 * cutoff * sinc * w
                table[row + j] = v.toFloat()
                sum += v
            }
            // Unity gain at DC for every phase.
            for (j in 0 until taps) table[row + j] = (table[row + j] / sum).toFloat()
        }
    }

    // Input history. buf[0] is input sample number bufStart; samples before 0 are silence.
    private var buf = FloatArray(maxOf(8192, taps * 4))
    private var bufLen = 0
    private var bufStart = 0L
    private var inCount = 0L
    private var outIndex = 0L
    private var out = FloatArray(4096)
    private var flushed = false

    init {
        // Silence before the start, so the first output samples have a full window.
        bufStart = -(half - 1).toLong()
        bufLen = half - 1
    }

    /** Number of output samples produced so far. */
    val outputCount: Long get() = outIndex

    fun process(input: FloatArray, count: Int = input.size, sink: (FloatArray, Int) -> Unit) {
        check(!flushed) { "already flushed" }
        var offset = 0
        while (offset < count) {
            val n = min(count - offset, buf.size - bufLen)
            if (n == 0) {
                compact()
                if (bufLen == buf.size) buf = buf.copyOf(buf.size * 2)
                continue
            }
            System.arraycopy(input, offset, buf, bufLen, n)
            bufLen += n
            inCount += n
            offset += n
            produce(inCount, sink)
        }
    }

    /** Finishes the stream: pads with silence so the tail comes out, and emits the rest. */
    fun flush(sink: (FloatArray, Int) -> Unit) {
        if (flushed) return
        flushed = true
        // Total output for the input seen: ceil(inCount * outRate / inRate).
        val total = (inCount * q + step - 1) / step
        val pad = FloatArray(half + 1)
        var available = inCount
        while (outIndex < total) {
            val n = min(pad.size, buf.size - bufLen)
            if (n == 0) {
                compact()
                if (bufLen == buf.size) buf = buf.copyOf(buf.size * 2)
                continue
            }
            System.arraycopy(pad, 0, buf, bufLen, n)
            bufLen += n
            available += n
            produce(available, sink, limit = total)
        }
    }

    private fun produce(available: Long, sink: (FloatArray, Int) -> Unit, limit: Long = Long.MAX_VALUE) {
        var produced = 0
        while (outIndex < limit) {
            val pos = outIndex * step               // input position times q
            val center = pos / q                    // input sample at or before the output time
            val last = center + half                // last input sample the window needs
            if (last >= available) break
            val phase = ((pos - center * q) * phases / q).toInt()
            val row = phase * taps
            val first = (center - half + 1 - bufStart).toInt()
            var acc = 0f
            for (j in 0 until taps) acc += buf[first + j] * table[row + j]
            if (produced == out.size) {
                sink(out, produced)
                produced = 0
            }
            out[produced++] = acc
            outIndex++
        }
        if (produced > 0) sink(out, produced)
    }

    /** Drops input that no future output sample needs. */
    private fun compact() {
        val center = outIndex * step / q
        val keepFrom = center - half + 1
        val drop = (keepFrom - bufStart).toInt().coerceIn(0, bufLen)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
    }

    companion object {
        private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val y = x * x / 4.0
            var k = 1
            while (term > 1e-12 * sum) {
                term *= y / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}
