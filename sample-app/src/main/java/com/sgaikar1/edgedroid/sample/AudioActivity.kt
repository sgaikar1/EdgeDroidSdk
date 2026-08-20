package com.sgaikar1.edgedroid.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class AudioActivity : ComponentActivity() {

    private val viewModel: AudioViewModel by viewModels {
        AudioViewModelFactory((application as EdgeDroidApp))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AudioScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun AudioScreen(viewModel: AudioViewModel) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val audioFile by viewModel.audioFile.collectAsStateWithLifecycle()
    val partial by viewModel.partial.collectAsStateWithLifecycle()
    val transcription by viewModel.transcription.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micPermissionGranted = granted }
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.pickAudio(uri) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Speech-to-text (whisper.cpp)",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Model: ${config.model.label}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    if (!micPermissionGranted) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.toggleRecording()
                    }
                },
                enabled = !busy,
            ) {
                Text(if (recording) "Stop recording" else "Record")
            }
            Button(
                onClick = { audioPicker.launch("audio/*") },
                enabled = !recording && !busy,
            ) {
                Text("Pick audio")
            }
            Button(
                onClick = { viewModel.transcribe() },
                enabled = audioFile != null && !recording && !busy,
            ) {
                Text("Transcribe")
            }
        }

        if (recording) {
            Text(
                "Recording… speak now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        audioFile?.let { file ->
            Text(
                "Audio: ${file.name} (${formatBytes(file.length())})",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        progress?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        if (busy && partial == null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp).padding(0.dp))
                Spacer(Modifier.height(0.dp))
                Text("Transcribing…", style = MaterialTheme.typography.bodySmall)
            }
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            ) {
                if (partial != null) {
                    Text("Partial:", style = MaterialTheme.typography.labelSmall)
                    Text(partial.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                }
                if (transcription != null) {
                    Text("Transcription:", style = MaterialTheme.typography.labelSmall)
                    Text(transcription.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
                if (partial == null && transcription == null) {
                    Text(
                        "Record a phrase or pick a WAV/audio file, then tap Transcribe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                viewModel.clearTranscription()
            }) {
                Text("Clear")
            }
        }
    }
}