package app.videotranslator.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sample formats a MediaCodec audio decoder hands back (AudioFormat.ENCODING_*). */
enum class PcmEncoding(val bytesPerSample: Int) {
    PCM_8BIT(1),
    PCM_16BIT(2),
    PCM_24BIT_PACKED(3),
    PCM_32BIT(4),
    PCM_FLOAT(4),
}

/**
 * Turns interleaved PCM from a decoder into mono float in -1..1, averaging the channels.
 * Returns the number of mono samples written to [out], which must be large enough.
 */
fun downmixToMono(
    data: ByteBuffer,
    bytes: Int,
    channels: Int,
    encoding: PcmEncoding,
    out: FloatArray,
): Int {
    require(channels > 0) { "channels=$channels" }
    val src = data.duplicate().order(ByteOrder.nativeOrder())
    val frameBytes = channels * encoding.bytesPerSample
    val frames = bytes / frameBytes
    require(out.size >= frames) { "output too small: ${out.size} < $frames" }
    val scale = 1f / channels
    val start = src.position()
    when (encoding) {
        PcmEncoding.PCM_16BIT -> {
            val s = src.asShortBuffer()
            var i = 0
            for (f in 0 until frames) {
                var acc = 0f
                for (c in 0 until channels) acc += s.get(i++).toFloat()
                out[f] = acc * scale / 32768f
            }
        }
        PcmEncoding.PCM_FLOAT -> {
            val s = src.asFloatBuffer()
            var i = 0
            for (f in 0 until frames) {
                var acc = 0f
                for (c in 0 until channels) acc += s.get(i++)
                out[f] = acc * scale
            }
        }
        PcmEncoding.PCM_32BIT -> {
            val s = src.asIntBuffer()
            var i = 0
            for (f in 0 until frames) {
                var acc = 0.0
                for (c in 0 until channels) acc += s.get(i++).toDouble()
                out[f] = (acc * scale / 2147483648.0).toFloat()
            }
        }
        PcmEncoding.PCM_8BIT -> {
            var p = start
            for (f in 0 until frames) {
                var acc = 0f
                for (c in 0 until channels) acc += ((src.get(p++).toInt() and 0xff) - 128).toFloat()
                out[f] = acc * scale / 128f
            }
        }
        PcmEncoding.PCM_24BIT_PACKED -> {
            val little = src.order() == ByteOrder.LITTLE_ENDIAN
            var p = start
            for (f in 0 until frames) {
                var acc = 0f
                for (c in 0 until channels) {
                    val b0 = src.get(p).toInt() and 0xff
                    val b1 = src.get(p + 1).toInt() and 0xff
                    val b2 = src.get(p + 2).toInt()
                    p += 3
                    val v = if (little) (b2 shl 16) or (b1 shl 8) or b0
                    else ((b0.toByte().toInt()) shl 16) or (b1 shl 8) or (b2 and 0xff)
                    acc += v.toFloat()
                }
                out[f] = acc * scale / 8388608f
            }
        }
    }
    return frames
}
