package com.bcam.app

import android.content.Context

/**
 * User-chosen video settings, stored in SharedPreferences.
 * Changed from the ⚙ Settings button in the recording notification ([SettingsActivity]).
 */
data class VideoSettings(
    /** Max height of the video: 720, 1080 or 2160 (4K). The camera's closest size at or below is used. */
    val resolution: Int = 1080,
    /** Frames per second: 24, 30 or 60. Falls back to 30 if the camera can't do it. */
    val fps: Int = 30,
    /** 0 = Low (smaller files), 1 = Standard, 2 = High (bigger files). */
    val quality: Int = 1,
    /** true = H.265 when the phone has a hardware encoder, false = always H.264 (plays everywhere). */
    val preferHevc: Boolean = true,
    /** Record sound (needs microphone permission). */
    val audio: Boolean = true,
    /** Minutes per file before a new one starts. */
    val segmentMinutes: Int = 30,
) {
    val segmentMs: Int get() = segmentMinutes * 60 * 1000

    /** Bits per pixel per frame. HEVC needs ~40% less than H.264 for the same picture. */
    fun bitsPerPixel(hevc: Boolean): Double = when (quality) {
        0 -> if (hevc) 0.05 else 0.09
        2 -> if (hevc) 0.12 else 0.20
        else -> if (hevc) 0.08 else 0.14
    }

    fun videoBitrate(width: Int, height: Int, fps: Int, hevc: Boolean): Int =
        (width.toLong() * height * fps * bitsPerPixel(hevc)).toLong()
            .coerceIn(1_000_000L, MAX_BITRATE.toLong()).toInt()

    /** Rough file size of one part in bytes, assuming a 16:9 picture at [resolution]. */
    fun estimatedPartBytes(hevc: Boolean): Long {
        val h = resolution
        val w = h * 16 / 9
        val bps = videoBitrate(w, h, fps, hevc).toLong() + if (audio) AUDIO_BITRATE else 0
        return bps / 8 * segmentMinutes * 60
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(K_RES, resolution)
            .putInt(K_FPS, fps)
            .putInt(K_QUALITY, quality)
            .putBoolean(K_HEVC, preferHevc)
            .putBoolean(K_AUDIO, audio)
            .putInt(K_SEGMENT, segmentMinutes)
            .apply()
    }

    companion object {
        const val PREFS = "bcam"
        const val AUDIO_BITRATE = 96_000
        const val MAX_BITRATE = 100_000_000
        private const val K_RES = "resolution"
        private const val K_FPS = "fps"
        private const val K_QUALITY = "quality"
        private const val K_HEVC = "prefer_hevc"
        private const val K_AUDIO = "audio"
        private const val K_SEGMENT = "segment_minutes"

        val RESOLUTIONS = listOf(720, 1080, 2160)
        val FPS_OPTIONS = listOf(24, 30, 60)
        val SEGMENTS = listOf(10, 15, 30, 60)

        fun resolutionLabel(r: Int) = if (r >= 2160) "4K (2160p)" else "${r}p"

        fun load(context: Context): VideoSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = VideoSettings()
            return VideoSettings(
                resolution = p.getInt(K_RES, d.resolution).takeIf { it in RESOLUTIONS } ?: d.resolution,
                fps = p.getInt(K_FPS, d.fps).takeIf { it in FPS_OPTIONS } ?: d.fps,
                quality = p.getInt(K_QUALITY, d.quality).coerceIn(0, 2),
                preferHevc = p.getBoolean(K_HEVC, d.preferHevc),
                audio = p.getBoolean(K_AUDIO, d.audio),
                segmentMinutes = p.getInt(K_SEGMENT, d.segmentMinutes).takeIf { it in SEGMENTS } ?: d.segmentMinutes,
            )
        }
    }
}
