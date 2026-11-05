package app.videotranslator.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {

    private fun sine(rate: Int, freq: Double, seconds: Double, amp: Double = 0.5): FloatArray =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun resampleAll(input: FloatArray, inRate: Int, chunk: Int = input.size): FloatArray {
        val r = Resampler(inRate)
        val out = ArrayList<Float>()
        val sink: (FloatArray, Int) -> Unit = { b, n -> for (i in 0 until n) out.add(b[i]) }
        var off = 0
        while (off < input.size) {
            val n = minOf(chunk, input.size - off)
            r.process(input.copyOfRange(off, off + n), n, sink)
            off += n
        }
        r.flush(sink)
        return out.toFloatArray()
    }

    /** Amplitude of [freq] in [x] at [rate], ignoring the edges. */
    private fun amplitudeAt(x: FloatArray, rate: Int, freq: Double): Double {
        val from = x.size / 10
        val to = x.size - x.size / 10
        var re = 0.0
        var im = 0.0
        for (i in from until to) {
            val ph = 2 * PI * freq * i / rate
            re += x[i] * cos(ph)
            im += x[i] * sin(ph)
        }
        return 2 * sqrt(re * re + im * im) / (to - from)
    }

    private fun rms(x: FloatArray): Double {
        val from = x.size / 10
        val to = x.size - x.size / 10
        var s = 0.0
        for (i in from until to) s += x[i] * x[i].toDouble()
        return sqrt(s / (to - from))
    }

    @Test
    fun outputLengthMatchesDuration() {
        for (rate in listOf(8_000, 11_025, 16_000, 22_050, 32_000, 44_100, 48_000, 96_000)) {
            val input = FloatArray(rate * 3) // 3 seconds
            val out = resampleAll(input, rate)
            assertEquals("rate $rate", 48_000, out.size)
        }
    }

    @Test
    fun speechBandToneKeepsItsLevelAndPitch() {
        for (rate in listOf(44_100, 48_000, 22_050, 8_000)) {
            val out = resampleAll(sine(rate, 1000.0, 2.0), rate)
            val amp = amplitudeAt(out, 16_000, 1000.0)
            assertEquals("rate $rate amplitude", 0.5, amp, 0.01)
        }
    }

    @Test
    fun toneAboveEightKilohertzIsFilteredOutNotFoldedBack() {
        // 11 kHz cannot exist at 16 kHz. Dropping samples would fold it to 5 kHz; filtering removes it.
        for (rate in listOf(44_100, 48_000)) {
            val out = resampleAll(sine(rate, 11_000.0, 2.0), rate)
            assertTrue("rate $rate: residue ${rms(out)}", rms(out) < 0.5 / sqrt(2.0) * 0.003) // below -50 dB
            assertTrue("rate $rate: alias ${amplitudeAt(out, 16_000, 5_000.0)}", amplitudeAt(out, 16_000, 5_000.0) < 0.002)
        }
    }

    @Test
    fun chunkSizeDoesNotChangeTheResult() {
        val rnd = Random(42)
        val input = FloatArray(44_100 * 2) { rnd.nextFloat() * 2 - 1 }
        val whole = resampleAll(input, 44_100)
        for (chunk in listOf(1, 7, 1024, 4096, 30_000)) {
            assertArrayEquals("chunk $chunk", whole, resampleAll(input, 44_100, chunk), 1e-6f)
        }
    }

    @Test
    fun timingIsPreserved() {
        // A click at exactly 1.000 s should come out at sample 16000.
        for (rate in listOf(44_100, 48_000)) {
            val input = FloatArray(rate * 2)
            input[rate] = 1f
            val out = resampleAll(input, rate)
            val peak = out.indices.maxByOrNull { abs(out[it]) }!!
            assertEquals("rate $rate", 16_000, peak)
        }
    }

    @Test
    fun stereoIsAveragedToMono() {
        val frames = 4
        val bb = ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.nativeOrder())
        val pairs = listOf(16384 to 16384, 32767 to -32768, 0 to 8192, -16384 to -16384)
        for ((l, r) in pairs) {
            bb.putShort(l.toShort())
            bb.putShort(r.toShort())
        }
        bb.flip()
        val out = FloatArray(frames)
        val n = downmixToMono(bb, bb.remaining(), 2, PcmEncoding.PCM_16BIT, out)
        assertEquals(frames, n)
        assertEquals(0.5f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-3f)
        assertEquals(0.125f, out[2], 1e-4f)
        assertEquals(-0.5f, out[3], 1e-4f)
    }

    @Test
    fun floatPcmIsAveragedToMono() {
        val bb = ByteBuffer.allocate(3 * 4 * 3).order(ByteOrder.nativeOrder())
        floatArrayOf(0.3f, 0.6f, 0.9f, -1f, 0f, 1f, 0.1f, 0.1f, 0.1f).forEach { bb.putFloat(it) }
        bb.flip()
        val out = FloatArray(3)
        assertEquals(3, downmixToMono(bb, bb.remaining(), 3, PcmEncoding.PCM_FLOAT, out))
        assertEquals(0.6f, out[0], 1e-6f)
        assertEquals(0f, out[1], 1e-6f)
        assertEquals(0.1f, out[2], 1e-6f)
    }
}
