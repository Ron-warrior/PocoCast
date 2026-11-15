package com.pococast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Turns what a source hands over into an MP4 the Apple TV will accept.
 *
 * Two jobs: repackaging a container (Matroska, MPEG-TS, AVI — nearly every P2P
 * release) and joining a separate video track and audio track into one file
 * (which is how a 1080p YouTube video arrives).
 *
 * Video and audio are copied, not re-encoded, so quality is untouched. The one
 * exception is audio an MP4 cannot carry — DTS, TrueHD, FLAC, Opus — which is
 * re-encoded to AAC, because a cast with sound beats a silent one. If that
 * conversion cannot be done on this device, the video goes out silent rather
 * than not at all.
 */
object Remuxer {

    data class Result(val file: File, val videoMime: String, val audioMime: String?)

    private val MUXABLE_VIDEO = setOf("video/avc", "video/hevc", "video/mp4v-es")
    private val MUXABLE_AUDIO = setOf("audio/mp4a-latm", "audio/ac3", "audio/eac3")

    private const val AUDIO_BITRATE = 192_000
    private const val AUDIO_BITRATE_MULTICHANNEL = 320_000

    /** Repackages [input] as [output], converting the audio to AAC if it has to. */
    fun remux(
        input: File,
        output: File,
        cancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): Result {
        val probe = MediaExtractor()
        var hasVideo = false
        var hasAnyAudio = false
        var hasUsableAudio = false
        try {
            probe.setDataSource(input.absolutePath)
            for (index in 0 until probe.trackCount) {
                val format = probe.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (MUXABLE_VIDEO.contains(mime)) hasVideo = true
                if (mime.startsWith("audio/")) {
                    hasAnyAudio = true
                    if (MUXABLE_AUDIO.contains(mime)) hasUsableAudio = true
                }
            }
        } finally {
            probe.release()
        }

        if (!hasVideo) {
            throw IOException(
                "its video codec cannot go in an MP4, so the Apple TV cannot play it " +
                    "(H.264 and HEVC can; VP9 and AV1 cannot)"
            )
        }

        if (!hasUsableAudio && hasAnyAudio) {
            val audioFile = File(output.absolutePath + ".audio.m4a")
            try {
                if (transcodeAudio(input, audioFile, cancelled, onProgress)) {
                    return writeMovie(input, audioFile, output, cancelled, onProgress)
                }
            } catch (cancelledError: CancelledException) {
                throw cancelledError
            } catch (ignored: Throwable) {
                // Silent video is still worth casting.
            } finally {
                audioFile.delete()
            }
        }

        return writeMovie(input, input, output, cancelled, onProgress)
    }

    /** Joins a video-only file and an audio-only file into one MP4. */
    fun combine(
        videoInput: File,
        audioInput: File,
        output: File,
        cancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): Result = writeMovie(videoInput, audioInput, output, cancelled, onProgress)

    private fun writeMovie(
        videoInput: File,
        audioInput: File,
        output: File,
        cancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): Result {
        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor = videoExtractor
        var secondExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoInput.absolutePath)
            val video = pickVideo(videoExtractor)
                ?: throw IOException("no H.264 or HEVC video track was found in this file")

            var audio: Picked? = null
            if (audioInput.absolutePath == videoInput.absolutePath) {
                audio = pickAudio(videoExtractor)
            } else {
                val separate = MediaExtractor()
                secondExtractor = separate
                separate.setDataSource(audioInput.absolutePath)
                audioExtractor = separate
                audio = pickAudio(separate)
            }

            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val videoOut = mux.addTrack(video.format)
            // A device's MP4 muxer can refuse a codec its extractor reads
            // happily; losing the audio track beats losing the cast.
            val audioOut = if (audio == null) {
                -1
            } else {
                try {
                    mux.addTrack(audio.format)
                } catch (error: RuntimeException) {
                    -1
                }
            }

            try {
                if (video.format.containsKey(MediaFormat.KEY_ROTATION)) {
                    val rotation = video.format.getInteger(MediaFormat.KEY_ROTATION)
                    if (rotation != 0) mux.setOrientationHint(rotation)
                }
            } catch (ignored: Exception) {
            }

            mux.start()

            val audioPicked = audio
            val duration = durationOf(video.format, if (audioOut >= 0) audioPicked?.format else null)
            val videoReader = Reader(videoExtractor, video.track, bufferSizeOf(video.format))
            val audioReader = if (audioOut >= 0 && audioPicked != null) {
                Reader(audioExtractor, audioPicked.track, bufferSizeOf(audioPicked.format))
            } else {
                null
            }
            val info = MediaCodec.BufferInfo()

            var hasVideo = videoReader.pull()
            if (!hasVideo) throw IOException("its video track has no readable frames")
            var hasAudio = audioReader?.pull() ?: false

            // Both tracks are handed over in timestamp order, which keeps the
            // file playable from its first second.
            while (hasVideo || hasAudio) {
                if (cancelled()) throw CancelledException()
                val takeVideo = when {
                    !hasVideo -> false
                    !hasAudio -> true
                    else -> videoReader.time <= (audioReader?.time ?: 0L)
                }
                if (takeVideo) {
                    info.set(0, videoReader.size, videoReader.time, videoReader.flags)
                    mux.writeSampleData(videoOut, videoReader.buffer, info)
                    if (duration > 0L) onProgress(percentOf(videoReader.time, duration))
                    hasVideo = videoReader.pull()
                } else {
                    val source = audioReader ?: break
                    info.set(0, source.size, source.time, source.flags)
                    mux.writeSampleData(audioOut, source.buffer, info)
                    if (duration > 0L) onProgress(percentOf(source.time, duration))
                    hasAudio = source.pull()
                }
            }

            mux.stop()
            onProgress(100)
            return Result(
                output,
                video.format.getString(MediaFormat.KEY_MIME) ?: "video/avc",
                if (audioOut >= 0) audio?.format?.getString(MediaFormat.KEY_MIME) else null
            )
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            try {
                muxer?.release()
            } catch (ignored: Exception) {
            }
            try {
                secondExtractor?.release()
            } catch (ignored: Exception) {
            }
            try {
                videoExtractor.release()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Re-encodes the first audio track of [input] as AAC in [output].
     *
     * The encoder is configured from what the decoder actually produces rather
     * than what the container claims, because a downmixing decoder would
     * otherwise be described with a channel count it does not emit and the
     * result would be noise instead of sound.
     */
    private fun transcodeAudio(
        input: File,
        output: File,
        cancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)

            var track = -1
            var chosen: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = index
                    chosen = format
                    break
                }
            }
            val format = chosen
            if (track < 0 || format == null) return false
            val decoderMime = format.getString(MediaFormat.KEY_MIME) ?: return false

            extractor.selectTrack(track)

            val probe = MediaCodec.createDecoderByType(decoderMime)
            probe.configure(format, null, null, 0)
            probe.start()
            val pcm = try {
                decoderOutputFormat(probe, extractor)
            } finally {
                try {
                    probe.stop()
                } catch (ignored: Exception) {
                }
                try {
                    probe.release()
                } catch (ignored: Exception) {
                }
            }

            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            extractor.selectTrack(track)

            val sampleRate = if (pcm.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                48000
            }
            val channels = if (pcm.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                -1L
            }

            val dec = MediaCodec.createDecoderByType(decoderMime)
            decoder = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val target = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    if (channels > 2) AUDIO_BITRATE_MULTICHANNEL else AUDIO_BITRATE
                )
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 128 * 1024)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = enc
            enc.configure(target, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            var muxTrack = -1
            var writing = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var lastProgress = System.currentTimeMillis()

            while (!encoderDone) {
                if (cancelled()) throw CancelledException()
                if (System.currentTimeMillis() - lastProgress > 30000L) {
                    throw IOException("audio conversion stopped making progress")
                }

                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val buffer = dec.getInputBuffer(inIndex)
                            ?: throw IOException("the audio decoder gave no input buffer")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    while (true) {
                        val outIndex = dec.dequeueOutputBuffer(info, 0)
                        if (outIndex < 0) break
                        val pcmBuffer = dec.getOutputBuffer(outIndex)
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (pcmBuffer != null && info.size > 0) {
                            feedEncoder(enc, pcmBuffer, info.size, info.presentationTimeUs, false)
                            if (duration > 0L) onProgress(percentOf(info.presentationTimeUs, duration))
                        }
                        dec.releaseOutputBuffer(outIndex, false)
                        lastProgress = System.currentTimeMillis()
                        if (end) {
                            decoderDone = true
                            feedEncoder(enc, null, 0, info.presentationTimeUs, true)
                            break
                        }
                    }
                }

                while (true) {
                    val outIndex = enc.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = mux.addTrack(enc.outputFormat)
                        mux.start()
                        writing = true
                        lastProgress = System.currentTimeMillis()
                        continue
                    }
                    if (outIndex < 0) break
                    val encoded = enc.getOutputBuffer(outIndex)
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (writing && encoded != null && info.size > 0) {
                        mux.writeSampleData(muxTrack, encoded, info)
                    }
                    enc.releaseOutputBuffer(outIndex, false)
                    lastProgress = System.currentTimeMillis()
                    if (end) encoderDone = true
                }
            }

            mux.stop()
            onProgress(100)
            return true
        } finally {
            try {
                muxer?.release()
            } catch (ignored: Exception) {
            }
            try {
                encoder?.stop()
            } catch (ignored: Exception) {
            }
            try {
                encoder?.release()
            } catch (ignored: Exception) {
            }
            try {
                decoder?.stop()
            } catch (ignored: Exception) {
            }
            try {
                decoder?.release()
            } catch (ignored: Exception) {
            }
            try {
                extractor.release()
            } catch (ignored: Exception) {
            }
        }
    }

    /** Runs a decoder until it reports the PCM format it produces. */
    private fun decoderOutputFormat(decoder: MediaCodec, extractor: MediaExtractor): MediaFormat {
        val info = MediaCodec.BufferInfo()
        var attempts = 0
        while (attempts < 4000) {
            attempts++
            val inIndex = decoder.dequeueInputBuffer(1000)
            if (inIndex >= 0) {
                val buffer = decoder.getInputBuffer(inIndex)
                    ?: throw IOException("the audio decoder gave no input buffer")
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(info, 0)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return decoder.outputFormat
            if (outIndex >= 0) decoder.releaseOutputBuffer(outIndex, false)
        }
        throw IOException("the audio decoder never reported its output format")
    }

    /**
     * Hands one buffer of PCM to the encoder, waiting for room and giving up
     * rather than hanging if the encoder stops accepting audio.
     */
    private fun feedEncoder(
        encoder: MediaCodec,
        source: ByteBuffer?,
        size: Int,
        timeUs: Long,
        endOfStream: Boolean
    ) {
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        var attempts = 0
        while (true) {
            val index = encoder.dequeueInputBuffer(10000)
            if (index >= 0) {
                val buffer = encoder.getInputBuffer(index)
                    ?: throw IOException("the AAC encoder gave no input buffer")
                buffer.clear()
                var copied = 0
                if (source != null && size > 0) {
                    source.position(0)
                    source.limit(minOf(size, source.capacity()))
                    copied = minOf(source.remaining(), buffer.remaining())
                    source.limit(source.position() + copied)
                    buffer.put(source)
                }
                encoder.queueInputBuffer(index, 0, copied, timeUs, flags)
                return
            }
            attempts++
            if (attempts > 200) throw IOException("the AAC encoder is not accepting audio")
        }
    }

    private class Picked(val track: Int, val format: MediaFormat)

    private fun pickVideo(extractor: MediaExtractor): Picked? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (MUXABLE_VIDEO.contains(mime)) return Picked(index, format)
        }
        return null
    }

    private fun pickAudio(extractor: MediaExtractor): Picked? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (MUXABLE_AUDIO.contains(mime)) return Picked(index, format)
        }
        return null
    }

    private class Reader(
        private val extractor: MediaExtractor,
        private val track: Int,
        bufferSize: Int
    ) {
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(bufferSize)
        var size = 0
        var time = 0L
        var flags = 0

        fun pull(): Boolean {
            extractor.selectTrack(track)
            val read = extractor.readSampleData(buffer, 0)
            if (read < 0) {
                size = 0
                return false
            }
            size = read
            time = extractor.sampleTime
            flags = extractor.sampleFlags
            extractor.advance()
            return true
        }
    }

    private fun durationOf(video: MediaFormat, audio: MediaFormat?): Long {
        if (video.containsKey(MediaFormat.KEY_DURATION)) {
            val fromVideo = video.getLong(MediaFormat.KEY_DURATION)
            if (fromVideo > 0L) return fromVideo
        }
        if (audio != null && audio.containsKey(MediaFormat.KEY_DURATION)) {
            return audio.getLong(MediaFormat.KEY_DURATION)
        }
        return -1L
    }

    private fun percentOf(timeUs: Long, durationUs: Long): Int =
        if (durationUs <= 0L) 0 else ((timeUs * 100) / durationUs).toInt().coerceIn(0, 100)

    private fun bufferSizeOf(format: MediaFormat): Int {
        val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            0
        }
        return maxOf(8 * 1024 * 1024, declared * 2)
    }
}
