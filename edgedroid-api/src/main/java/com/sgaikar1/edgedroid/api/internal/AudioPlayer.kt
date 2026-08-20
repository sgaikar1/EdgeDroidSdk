package com.sgaikar1.edgedroid.api.internal

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.sgaikar1.edgedroid.common.TtsAudio

/**
 * Minimal playback for [TtsAudio] using a float [AudioTrack]. Handles short clips by writing
 * the whole buffer and playing it blockingly on the calling (background) thread.
 *
 * This keeps `speak()` self-contained — apps that need streaming/latency control can call
 * [com.sgaikar1.edgedroid.api.EdgeDroid.synthesize] and drive their own [AudioTrack] instead.
 */
internal object AudioPlayer {

    fun play(audio: TtsAudio) {
        if (audio.samples.isEmpty()) return
        val minBuffer = AudioTrack.getMinBufferSize(
            audio.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(audio.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer.coerceAtLeast(audio.samples.size * 4))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            // Block until playback finishes so speak() is a complete "speak" action.
            val expectedMs = audio.durationMillis
            Thread.sleep(expectedMs.toLong().coerceAtLeast(0L))
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
