package app.videotranslator.audio

import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.videotranslator.whisper.Cancellation
import app.videotranslator.whisper.CancelledException

const val WHISPER_SAMPLE_RATE = 16_000

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the audio track turned out to be. */
data class AudioInfo(
    val mime: String,
    val sampleRate: Int,
    val channels: Int,
    /** Track duration from the container, if it says. */
    val durationUs: Long?,
    /** Time of the first decoded sample, in the video's timeline. Subtitle times are offset by this. */
    val firstSampleUs: Long,
    /** 16 kHz mono samples produced. */
    val outputSamples: Long,
)

/**
 * Decodes the first audio track of a video with MediaExtractor and MediaCodec, mixes it to mono
 * and converts it to 16 kHz float for Whisper. Blocking: call from a background thread.
 */
object AudioDecoder {
    private const val TIMEOUT_US = 10_000L

    /** Reads the audio track's format without decoding: duration and codec. */
    fun probe(source: AssetFileDescriptor): Pair<String, Long?> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source)
            val track = findAudioTrack(extractor) ?: throw AudioDecodeException("This video has no sound track.")
            val format = extractor.getTrackFormat(track)
            return format.getString(MediaFormat.KEY_MIME).orEmpty() to format.durationUsOrNull()
        } finally {
            extractor.release()
        }
    }

    /**
     * @param onProgress fraction 0..1 of the track decoded so far.
     * @param sink receives 16 kHz mono chunks; the array is reused, so copy what you keep.
     */
    fun decode(
        source: AssetFileDescriptor,
        cancellation: Cancellation,
        onStart: (durationUs: Long?) -> Unit = {},
        onProgress: (Float) -> Unit = {},
        sink: (FloatArray, Int) -> Unit,
    ): AudioInfo {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(source)
            } catch (e: Exception) {
                throw AudioDecodeException("Could not open this video.", e)
            }
            val track = findAudioTrack(extractor) ?: throw AudioDecodeException("This video has no sound track.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val durationUs = format.durationUsOrNull()
            onStart(durationUs)

            codec = try {
                MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (e: Exception) {
                throw AudioDecodeException("This phone cannot decode the video's sound ($mime).", e)
            }

            var sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 0)
            var channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 0)
            var encoding = PcmEncoding.PCM_16BIT
            var resampler: Resampler? = null
            var mono = FloatArray(0)
            var firstSampleUs = -1L
            var produced = 0L
            val countingSink: (FloatArray, Int) -> Unit = { buf, n ->
                produced += n
                sink(buf, n)
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastProgress = -1f
            var idleAfterInput = 0

            while (!outputDone) {
                if (cancellation.isCancelled) throw CancelledException()

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // A decoder that never signals the end: stop after 5 s of nothing.
                    if (++idleAfterInput > 500) break
                } else {
                    idleAfterInput = 0
                }
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        val newRate = out.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = out.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        encoding = pcmEncoding(out.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT))
                        if (resampler != null && newRate != sampleRate) {
                            // Rare: the rate changed mid-stream. Finish the old converter first.
                            resampler.flush(countingSink)
                            resampler = null
                        }
                        sampleRate = newRate
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (sampleRate <= 0 || channels <= 0) {
                                throw AudioDecodeException("The video's sound track has no usable format.")
                            }
                            if (firstSampleUs < 0) firstSampleUs = info.presentationTimeUs
                            val r = resampler ?: Resampler(sampleRate, WHISPER_SAMPLE_RATE).also { resampler = it }
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val frames = info.size / (channels * encoding.bytesPerSample)
                            if (mono.size < frames) mono = FloatArray(frames)
                            val n = downmixToMono(outBuf, info.size, channels, encoding, mono)
                            r.process(mono, n, countingSink)

                            if (durationUs != null && durationUs > 0) {
                                val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                if (p - lastProgress >= 0.005f) {
                                    lastProgress = p
                                    onProgress(p)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
            resampler?.flush(countingSink)
            onProgress(1f)
            if (produced == 0L) throw AudioDecodeException("The video's sound track is empty.")

            return AudioInfo(
                mime = mime,
                sampleRate = sampleRate,
                channels = channels,
                durationUs = durationUs,
                firstSampleUs = firstSampleUs.coerceAtLeast(0),
                outputSamples = produced,
            )
        } catch (e: MediaCodec.CodecException) {
            throw AudioDecodeException("The video's sound could not be decoded.", e)
        } catch (e: IllegalStateException) {
            throw AudioDecodeException("The video's sound could not be decoded.", e)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun pcmEncoding(value: Int): PcmEncoding = when (value) {
        AudioFormat.ENCODING_PCM_8BIT -> PcmEncoding.PCM_8BIT
        AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.PCM_16BIT
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmEncoding.PCM_24BIT_PACKED
        AudioFormat.ENCODING_PCM_32BIT -> PcmEncoding.PCM_32BIT
        AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.PCM_FLOAT
        else -> throw AudioDecodeException("The video's sound comes out in a format this app does not handle ($value).")
    }

    private fun MediaFormat.getIntegerOr(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun MediaFormat.durationUsOrNull(): Long? =
        if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 } else null
}
