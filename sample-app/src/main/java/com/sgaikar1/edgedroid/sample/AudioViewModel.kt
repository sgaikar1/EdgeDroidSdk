package com.sgaikar1.edgedroid.sample

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sgaikar1.edgedroid.core.LlmEngineState
import com.sgaikar1.edgedroid.core.ModelDownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class AudioViewModel(private val app: EdgeDroidApp) : ViewModel() {

    private val store = app.sampleStore
    private val sdk get() = store.sdk
    private val appContext = app.applicationContext

    val config = store.config
    val engineState: StateFlow<LlmEngineState> = store.sdkState

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _audioFile = MutableStateFlow<File?>(null)
    val audioFile: StateFlow<File?> = _audioFile.asStateFlow()

    private val _partial = MutableStateFlow<String?>(null)
    val partial: StateFlow<String?> = _partial.asStateFlow()

    private val _transcription = MutableStateFlow<String?>(null)
    val transcription: StateFlow<String?> = _transcription.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var recorder: AudioRecord? = null
    private var recordJob: Job? = null

    val isDownloaded: Boolean
        get() = store.sdk.models.available().any {
            it.id == store.config.value.model.id &&
                it.localPath?.let { p -> File(p).exists() } == true
        }

    fun toggleRecording() {
        if (recording.value) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val sampleRate = AudioUtils.TARGET_SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            _error.value = "Failed to initialize audio recorder"
            return
        }
        recorder = record
        record.startRecording()
        _recording.value = true
        _error.value = null
        _audioFile.value = null
        _transcription.value = null
        _partial.value = null

        recordJob = viewModelScope.launch(Dispatchers.Default) {
            val out = ByteArrayOutputStream()
            val buffer = ShortArray(minBuf)
            while (isActive && recorder != null) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        val s = buffer[i].toInt()
                        out.write(s and 0xFF)
                        out.write((s shr 8) and 0xFF)
                    }
                }
            }
            val file = File(appContext.filesDir, "recording.wav")
            AudioUtils.writeWav(out.toByteArray(), sampleRate, 1, file)
            _audioFile.value = file
        }
    }

    private fun stopRecording() {
        recordJob?.cancel()
        recordJob = null
        recorder?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        recorder = null
        _recording.value = false
    }

    fun pickAudio(uri: Uri) {
        viewModelScope.launch {
            _error.value = null
            runCatching {
                withContext(Dispatchers.Default) { AudioUtils.decode(appContext, uri) }
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    _error.value = "Cannot read audio file"
                    return@launch
                }
                val file = File(appContext.filesDir, "picked_audio")
                file.writeBytes(bytes)
                _audioFile.value = file
                _transcription.value = null
                _partial.value = null
            }.onFailure { t ->
                _error.value = "Failed to load audio: ${t.message}"
                Log.e("EdgeDroid.Audio", "pick failed", t)
            }
        }
    }

    fun clearTranscription() {
        _transcription.value = null
        _partial.value = null
        _error.value = null
    }

    fun transcribe() {
        val file = _audioFile.value ?: run {
            _error.value = "Record or pick an audio file first"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            _partial.value = ""
            _transcription.value = null
            try {
                ensureModelReady()
                val audio = withContext(Dispatchers.Default) { AudioUtils.decode(appContext, Uri.fromFile(file)) }
                Log.d("EdgeDroid.Audio", "decoded ${audio.pcm.size} bytes @ ${audio.sampleRate}Hz ch=${audio.channels}")
                val sb = StringBuilder()
                sdk.transcribeStream(audio.pcm, audio.sampleRate).collect { seg ->
                    sb.append(seg.text)
                    _partial.value = sb.toString()
                }
                _partial.value = null
                _transcription.value = sb.toString()
            } catch (t: Throwable) {
                _partial.value = null
                _error.value = t.message ?: "Transcription failed"
                Log.e("EdgeDroid.Audio", "transcribe failed", t)
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun ensureModelReady() {
        if (!isDownloaded) {
            sdk.models.download().collect { state ->
                when (state) {
                    is ModelDownloadState.Downloading -> _progress.value = state.progress
                    is ModelDownloadState.Completed -> {
                        _progress.value = null
                        store.refreshDownloaded()
                    }
                    is ModelDownloadState.Failed -> throw RuntimeException(
                        "Download failed (${state.kind}): ${state.message}",
                    )
                    else -> Unit
                }
            }
        }
        sdk.load()
    }

    override fun onCleared() {
        super.onCleared()
        stopRecording()
    }
}