package io.grokify.os.apps.lyre

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/** Device-side PCM decode for clips that have no stored `waveform` yet. */
object LyreWaveformDecoder {
    fun peaksFromFile(file: File, bins: Int = LyreWaveform.BINS): WaveformPeaks? {
        if (!file.isFile || file.length() <= 0L) return null
        LyreWaveform.peaksFromWav(file, bins)?.let { return it }
        return runCatching { decode(file, bins) }.getOrNull()
    }

    fun fillMissing(board: BoardData, files: Map<String, File>): Pair<BoardData, Int> {
        var next = board
        var n = 0
        fun fileOf(src: String): File? = LyreStorageKeys.file(files, src)

        next = next.copy(
            audioLayers = next.audioLayers.map { layer ->
                layer.copy(
                    clips = layer.clips.map { clip ->
                        if (clip.waveform != null || clip.src.isEmpty()) return@map clip
                        val file = fileOf(clip.src) ?: return@map clip
                        val peaks = peaksFromFile(file) ?: return@map clip
                        n++
                        clip.copy(waveform = peaks.toJson())
                    },
                )
            },
            libraryAudio = next.libraryAudio.map { item ->
                if (item.waveform != null || item.src.isEmpty() || item.deletedAt != null) return@map item
                val file = fileOf(item.src) ?: return@map item
                val peaks = peaksFromFile(file) ?: return@map item
                n++
                item.copy(waveform = peaks.toJson())
            },
        )
        return next to n
    }

    private fun decode(file: File, bins: Int): WaveformPeaks? {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            return null
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
            extractor.release()
            return null
        }
        val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100)
        val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1).coerceAtLeast(1)
        val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        val totalSamples = if (durationUs > 0L) {
            max(1L, durationUs * sampleRate / 1_000_000L)
        } else {
            0L
        }
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val minA = FloatArray(bins) { 0f }
        val maxA = FloatArray(bins) { 0f }
        val info = MediaCodec.BufferInfo()
        var sample = 0L
        var sawInputEos = false
        var sawOutputEos = false
        var loops = 0
        try {
            while (!sawOutputEos && loops++ < 200_000) {
                if (!sawInputEos) {
                    val inIx = codec.dequeueInputBuffer(8_000)
                    if (inIx >= 0) {
                        val inBuf = codec.getInputBuffer(inIx)
                        val size = if (inBuf == null) -1 else extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIx, 0, size, pts.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                val outIx = codec.dequeueOutputBuffer(info, 8_000)
                if (outIx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIx)
                    if (outBuf != null && info.size > 0) {
                        sample += ingestPcm16(
                            buf = outBuf,
                            offset = info.offset,
                            size = info.size,
                            channels = channels,
                            bins = bins,
                            totalHint = totalSamples,
                            sampleIndex = sample,
                            minA = minA,
                            maxA = maxA,
                        )
                    }
                    codec.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }
        return WaveformPeaks(min = minA, max = maxA, mid = null)
    }

    private fun ingestPcm16(
        buf: ByteBuffer,
        offset: Int,
        size: Int,
        channels: Int,
        bins: Int,
        totalHint: Long,
        sampleIndex: Long,
        minA: FloatArray,
        maxA: FloatArray,
    ): Long {
        val frameBytes = channels * 2
        if (frameBytes <= 0) return 0L
        val frames = size / frameBytes
        val total = if (totalHint > 0L) totalHint else max(sampleIndex + frames, 1L)
        var idx = sampleIndex
        val pos = buf.position()
        var i = offset
        val end = offset + frames * frameBytes
        while (i + frameBytes <= end) {
            var peak = 0
            for (ch in 0 until channels) {
                val lo = buf.get(i + ch * 2).toInt() and 0xff
                val hi = buf.get(i + ch * 2 + 1).toInt()
                val s = (lo or (hi shl 8)).toShort().toInt()
                if (kotlin.math.abs(s) > kotlin.math.abs(peak)) peak = s
            }
            val bin = (idx * bins / total).toInt().coerceIn(0, bins - 1)
            val v = peak / 32768f
            if (v < minA[bin]) minA[bin] = v
            if (v > maxA[bin]) maxA[bin] = v
            idx++
            i += frameBytes
        }
        buf.position(pos)
        return frames.toLong()
    }
}
