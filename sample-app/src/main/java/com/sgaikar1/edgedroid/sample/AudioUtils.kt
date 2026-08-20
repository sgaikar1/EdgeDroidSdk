package com.sgaikar1.edgedroid.sample

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes picked audio files and records/encodes PCM. Whisper expects 16-bit little-endian
 * PCM (mono is mixed from stereo) at any sample rate — the runtime resamples to 16 kHz.
 */
object AudioUtils {

    const val TARGET_SAMPLE_RATE = 16000

    data class PcmAudio(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
    )

    /** Decode any content [uri] (WAV, AAC, MP3, …) to 16-bit PCM. */
    fun decode(context: Context, uri: Uri): PcmAudio {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read audio file")
        if (isWav(bytes)) return decodeWav(bytes)
        return decodeWithMediaCodec(context, uri)
    }

    /** Parse a RIFF/WAVE container with 16-bit PCM audio. */
    fun decodeWav(bytes: ByteArray): PcmAudio {
        if (bytes.size < 44 || bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            throw IllegalArgumentException("Not a RIFF/WAV file")
        }
        val little = ByteOrder.LITTLE_ENDIAN
        val buf = ByteBuffer.wrap(bytes).order(little)
        buf.position(22) // skip RIFF header + fmt chunk start
        val channels = buf.getShort().toInt()
        val sampleRate = buf.getInt()
        buf.getInt() // byte rate
        buf.getShort() // block align
        val bitsPerSample = buf.getShort().toInt()

        // Walk chunks to find "data".
        buf.position(12)
        var dataOffset = -1
        var dataSize = 0
        while (buf.remaining() >= 8) {
            val id = bytes[buf.position()].toChar().toString() +
                bytes[buf.position() + 1].toChar().toString() +
                bytes[buf.position() + 2].toChar().toString() +
                bytes[buf.position() + 3].toChar().toString()
            buf.position(buf.position() + 4)
            val size = buf.getInt()
            if (id == "data") {
                dataOffset = buf.position()
                dataSize = size
                break
            }
            buf.position(buf.position() + size + (size and 1))
        }
        if (dataOffset < 0 || dataSize <= 0) throw IllegalArgumentException("WAV has no audio data")

        var pcm = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
        if (bitsPerSample == 32) {
            // IEEE float WAV -> 16-bit PCM.
            pcm = floatWavToPcm16(pcm)
        } else if (bitsPerSample != 16) {
            throw IllegalArgumentException("Unsupported WAV bit depth: $bitsPerSample (only 16-bit supported)")
        }
        if (channels > 1) pcm = mixToMono(pcm, channels)
        return PcmAudio(pcm, sampleRate, 1)
    }

    /** True when the first 4 bytes are "RIFF" (a WAV container). */
    private fun isWav(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()

    private fun floatWavToPcm16(floatPcm: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(floatPcm).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteArrayOutputStream(floatPcm.size / 2)
        while (buf.remaining() >= 4) {
            val f = buf.float.coerceIn(-1f, 1f)
            val s = (f * 32767f).toInt().coerceIn(-32768, 32767)
            out.write(s and 0xFF)
            out.write((s shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    /** Average interleaved 16-bit samples down to mono. */
    private fun mixToMono(pcm: ByteArray, channels: Int): ByteArray {
        val frames = pcm.size / (2 * channels)
        val out = ByteArray(frames * 2)
        for (i in 0 until frames) {
            var sum = 0L
            for (c in 0 until channels) {
                val idx = (i * channels + c) * 2
                sum += (pcm[idx].toInt() and 0xFF) or (pcm[idx + 1].toInt() shl 8)
            }
            val s = (sum / channels).toShort()
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Decode arbitrary compressed audio via MediaExtractor + MediaCodec to 16-bit PCM. */
    private fun decodeWithMediaCodec(context: Context, uri: Uri): PcmAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot open audio: ${e.message}", e)
        }
        var track = -1
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                break
            }
        }
        if (track < 0) throw IllegalArgumentException("No audio track found")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            1
        }
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(outIndex)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val chunk = ByteArray(info.size)
                    buffer.get(chunk)
                    out.write(chunk)
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // format may change; keep going
            }
        }
        codec.stop()
        codec.release()
        extractor.release()

        var pcm = out.toByteArray()
        if (channels > 1) pcm = mixToMono(pcm, channels)
        return PcmAudio(pcm, sampleRate, 1)
    }

    /** Write a minimal RIFF/WAVE file from 16-bit PCM. */
    fun writeWav(pcm: ByteArray, sampleRate: Int, channels: Int, file: File) {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val dataSize = pcm.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16) // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)
        file.parentFile?.mkdirs()
        file.writeBytes(header.array() + pcm)
    }
}