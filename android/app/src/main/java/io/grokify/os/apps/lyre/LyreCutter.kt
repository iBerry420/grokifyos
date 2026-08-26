@file:OptIn(UnstableApi::class)

package io.grokify.os.apps.lyre

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.math.roundToLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class Probe(
    val durationUs: Long,
    val durationSec: Double,
    val fps: Double,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val frameCount: Int?,
)

data class CutOk(val file: File, val durationSec: Double, val fps: Double)

interface LyreCutter {
    suspend fun probe(file: File): Probe
    suspend fun trim(input: File, startSec: Double, endSec: Double, fpsHint: Double?): CutOk
    suspend fun stitch(movie: File?, clip: File, dropLast: Boolean, keepSec: Double?): CutOk
    suspend fun mute(input: File): CutOk
    suspend fun extractAudio(input: File): CutOk
    suspend fun split(input: File, atSec: Double): Pair<CutOk, CutOk>
    suspend fun burnAudio(video: File, beds: List<AudioBed>): CutOk
    /** Pop fallback only. Pairwise stitch remaining part files with dropLast. */
    suspend fun rebuild(partFiles: List<File>, dropLast: Boolean): CutOk

    companion object {
        @Volatile
        var useFfmpegFallback: Boolean = false
    }
}

class Media3LyreCutter(
    app: Context,
    private val tmpDir: () -> File,
) : LyreCutter {
    private val app = app.applicationContext

    override suspend fun probe(file: File): Probe {
        return withContext(Dispatchers.IO) { probeSync(file) }
    }

    override suspend fun trim(input: File, startSec: Double, endSec: Double, fpsHint: Double?): CutOk {
        val startUs = (startSec.coerceAtLeast(0.0) * 1_000_000.0).toLong()
        val endUs = (endSec * 1_000_000.0).toLong().coerceAtLeast(startUs + 1)
        val item = MediaItem.Builder()
            .setUri(input.toUri())
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(startUs)
                    .setEndPositionUs(endUs)
                    .build(),
            )
            .build()
        val p = probeSync(input)
        return export(single(item, p.width, p.height), scratch(".mp4"), TRIM_TIMEOUT_MS)
    }

    override suspend fun stitch(movie: File?, clip: File, dropLast: Boolean, keepSec: Double?): CutOk {
        if (movie == null) {
            val p = probeSync(clip)
            return export(single(MediaItem.fromUri(clip.toUri()), p.width, p.height), scratch(".mp4"), STITCH_TIMEOUT_MS)
        }
        val movieProbe = probeSync(movie)
        val clipProbe = probeSync(clip)
        val w = movieProbe.width.takeIf { it > 0 } ?: clipProbe.width
        val h = movieProbe.height.takeIf { it > 0 } ?: clipProbe.height
        val movieItem = if (dropLast) {
            val endUs = dropLastEndUs(movie, movieProbe, keepSec)
            MediaItem.Builder()
                .setUri(movie.toUri())
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(0)
                        .setEndPositionUs(endUs)
                        .build(),
                )
                .build()
        } else {
            MediaItem.fromUri(movie.toUri())
        }
        val seq = EditedMediaItemSequence.Builder()
            .addItem(edited(movieItem, w, h))
            .addItem(edited(MediaItem.fromUri(clip.toUri()), w, h))
            .build()
        return export(Composition.Builder(seq).build(), scratch(".mp4"), STITCH_TIMEOUT_MS)
    }

    override suspend fun mute(input: File): CutOk {
        val p = probeSync(input)
        val item = edited(MediaItem.fromUri(input.toUri()), p.width, p.height, removeAudio = true)
        val seq = EditedMediaItemSequence.Builder().addItem(item).build()
        return export(Composition.Builder(seq).build(), scratch(".mp4"), TRIM_TIMEOUT_MS)
    }

    override suspend fun extractAudio(input: File): CutOk {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(input.toUri()))
            .setRemoveVideo(true)
            .build()
        val seq = EditedMediaItemSequence.Builder().addItem(item).build()
        return export(Composition.Builder(seq).build(), scratch(".m4a"), TRIM_TIMEOUT_MS)
    }

    override suspend fun split(input: File, atSec: Double): Pair<CutOk, CutOk> {
        val p = probeSync(input)
        val end = p.durationSec
        val left = trim(input, 0.0, atSec.coerceIn(0.0, end), p.fps)
        val right = trim(input, atSec.coerceIn(0.0, end), end, p.fps)
        return left to right
    }

    override suspend fun burnAudio(video: File, beds: List<AudioBed>): CutOk {
        val p = probeSync(video)
        val videoItem = edited(MediaItem.fromUri(video.toUri()), p.width, p.height)
        val videoSeq = EditedMediaItemSequence.Builder()
            .addItem(videoItem)
            .build()
        val audioSeqs = beds.map { bed ->
            val bedItem = EditedMediaItem.Builder(MediaItem.fromUri(bed.file.toUri()))
                .setRemoveVideo(true)
                .build()
            val b = EditedMediaItemSequence.Builder()
            if (bed.startSec > 0.0) {
                // Javadoc says ms; the value is Us.
                b.addGap((bed.startSec * 1_000_000L).toLong())
            }
            b.addItem(bedItem).build()
        }
        val composition = Composition.Builder(videoSeq, *audioSeqs.toTypedArray()).build()
        return export(composition, scratch(".mp4"), STITCH_TIMEOUT_MS)
    }

    override suspend fun rebuild(partFiles: List<File>, dropLast: Boolean): CutOk {
        require(partFiles.size >= 2) { "rebuild needs >= 2 parts" }
        var acc = stitch(partFiles[0], partFiles[1], dropLast, keepSec = null)
        for (i in 2 until partFiles.size) {
            acc = stitch(acc.file, partFiles[i], dropLast, keepSec = null)
        }
        return acc
    }

    private fun single(item: MediaItem, w: Int, h: Int): Composition {
        val seq = EditedMediaItemSequence.Builder().addItem(edited(item, w, h)).build()
        return Composition.Builder(seq).build()
    }

    private fun edited(
        item: MediaItem,
        w: Int,
        h: Int,
        removeAudio: Boolean = false,
        removeVideo: Boolean = false,
    ): EditedMediaItem {
        val b = EditedMediaItem.Builder(item)
        if (removeAudio) b.setRemoveAudio(true)
        if (removeVideo) b.setRemoveVideo(true)
        if (w > 0 && h > 0) {
            b.setEffects(
                Effects(
                    emptyList(),
                    listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)),
                ),
            )
        }
        return b.build()
    }

    private fun dropLastEndUs(file: File, probe: Probe, keepSec: Double?): Long {
        if (keepSec != null && keepSec > 0.0) {
            return (keepSec * 1_000_000.0).toLong().coerceAtLeast(1L)
        }
        val fps = probe.fps
        val frames = videoFrameCount(file) ?: probe.frameCount
        if (frames != null && frames >= 2) {
            return (((frames - 1).toDouble() / fps) * 1_000_000.0).toLong().coerceAtLeast(1L)
        }
        val step = 1_000_000L / fps.roundToLong().coerceAtLeast(1L)
        return (probe.durationUs - step).coerceAtLeast(1L)
    }

    private fun scratch(ext: String): File {
        val dir = tmpDir()
        dir.mkdirs()
        return File(dir, "cut-${System.nanoTime()}$ext")
    }

    private suspend fun export(composition: Composition, out: File, timeoutMs: Long): CutOk {
        if (LyreCutter.useFfmpegFallback) error("ffmpeg fallback not bundled")
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        val done = CompletableDeferred<Result<Unit>>()
        val transformer = withContext(Dispatchers.Main) {
            Transformer.Builder(app)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (!done.isCompleted) done.complete(Result.success(Unit))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (!done.isCompleted) done.complete(Result.failure(exportException))
                        }
                    },
                )
                .build()
        }
        try {
            withContext(Dispatchers.Main) {
                transformer.start(composition, out.absolutePath)
            }
            withTimeout(timeoutMs) {
                done.await().getOrThrow()
            }
        } catch (e: TimeoutCancellationException) {
            withContext(Dispatchers.Main) { runCatching { transformer.cancel() } }
            out.delete()
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { runCatching { transformer.cancel() } }
            out.delete()
            throw e
        }
        val p = probeSync(out)
        return CutOk(out, p.durationSec, p.fps)
    }

    private fun probeSync(file: File): Probe {
        var durationMs = 0L
        var width = 0
        var height = 0
        var fps = 0.0
        var hasAudio = false
        var frames: Int? = null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 0.0
            hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.equals("yes", true) == true
            if (Build.VERSION.SDK_INT >= 28) {
                frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            }
        } finally {
            r.release()
        }
        if (!hasAudio) hasAudio = extractorHasAudio(file)
        if (frames == null) frames = videoFrameCount(file)
        val durationUs = (durationMs * 1000L).coerceAtLeast(0L)
        if (fps <= 1.0 && durationUs > 0L && frames != null && frames > 0) {
            fps = frames * 1_000_000.0 / durationUs
        }
        if (fps <= 1.0) fps = 24.0
        return Probe(
            durationUs = durationUs,
            durationSec = durationUs / 1_000_000.0,
            fps = fps,
            width = width,
            height = height,
            hasAudio = hasAudio,
            frameCount = frames,
        )
    }

    companion object {
        private const val TRIM_TIMEOUT_MS = 90_000L
        private const val STITCH_TIMEOUT_MS = 180_000L

        fun videoFrameCount(file: File): Int? {
            if (Build.VERSION.SDK_INT >= 28) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.absolutePath)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?.let { return it }
                } catch (_: Exception) {
                } finally {
                    r.release()
                }
            }
            val ex = MediaExtractor()
            return try {
                ex.setDataSource(file.absolutePath)
                val track = (0 until ex.trackCount).firstOrNull { i ->
                    ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return null
                ex.selectTrack(track)
                var n = 0
                while (true) {
                    if (ex.sampleTrackIndex == track) n++
                    if (!ex.advance()) break
                }
                n.takeIf { it > 0 }
            } catch (_: Exception) {
                null
            } finally {
                ex.release()
            }
        }

        private fun extractorHasAudio(file: File): Boolean {
            val ex = MediaExtractor()
            return try {
                ex.setDataSource(file.absolutePath)
                (0 until ex.trackCount).any { i ->
                    val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: return@any false
                    mime.startsWith("audio/") || MimeTypes.isAudio(mime)
                }
            } catch (_: Exception) {
                false
            } finally {
                ex.release()
            }
        }
    }
}
