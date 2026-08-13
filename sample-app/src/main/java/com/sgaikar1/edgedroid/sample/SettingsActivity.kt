package com.sgaikar1.edgedroid.sample

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sgaikar1.edgedroid.core.GpuConfig
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as EdgeDroidApp).sampleStore
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val scope = rememberCoroutineScope()
                    SettingsScreen(store) { newConfig ->
                        scope.launch {
                            val err = store.apply(newConfig)
                            if (err != null) {
                                android.util.Log.e("EdgeDroid.Settings", "apply failed: ${err.message}", err)
                            }
                            finish()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(store: SampleStore, onApply: (SampleConfig) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val config by store.config.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(config) }
    LaunchedEffect(config) { draft = config }

    val hfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val sel = result.data?.getSerializableExtra(HfBrowserActivity.EXTRA_SELECTION) as? HfModelSelection
            if (sel != null) {
                draft = draft.copy(
                    runtime = if (sel.format.isOnnx) SampleRuntime.ONNX else SampleRuntime.LLAMA,
                    customModel = sel,
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("SDK Settings", style = MaterialTheme.typography.titleLarge)
        Text("Runtime: ${draft.runtime} · Model: ${draft.model.label}", style = MaterialTheme.typography.bodySmall)

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownBox(
                    label = "Runtime",
                    selected = draft.runtime.name,
                    options = SampleRuntime.entries.map { it.name },
                    modifier = Modifier.weight(1f),
                ) { choice ->
                    draft = draft.withRuntime(SampleRuntime.valueOf(choice))
                }
                DropdownBox(
                    label = "Model",
                    selected = draft.model.label,
                    options = SampleModels.forRuntime(draft.runtime).map { it.label },
                    modifier = Modifier.weight(1.2f),
                ) { label ->
                    val model = SampleModels.forRuntime(draft.runtime).first { it.label == label }
                    draft = draft.copy(modelId = model.id, customModel = null)
                }
            }

            Button(
                onClick = {
                    hfLauncher.launch(
                        Intent(context, HfBrowserActivity::class.java)
                            .putExtra(HfBrowserActivity.EXTRA_FILTER, draft.runtime.name),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Browse HuggingFace models…")
            }

            SliderRow("Threads", draft.threads.toFloat(), 1f..8f, 7) { draft = draft.copy(threads = it.toInt()) }

            if (draft.runtime == SampleRuntime.LLAMA) {
                SliderRow("Context size", draft.contextSize.toFloat(), 512f..4096f, 7) {
                    draft = draft.copy(contextSize = it.toInt())
                }
                DropdownBox(
                    label = "GPU",
                    selected = when (draft.gpu) {
                        is GpuConfig.Auto -> "AUTO"
                        is GpuConfig.Cpu -> "CPU"
                        is GpuConfig.All -> "ALL"
                        is GpuConfig.Layers -> "LAYERS"
                    },
                    options = listOf("AUTO", "CPU", "ALL"),
                    modifier = Modifier.fillMaxWidth(),
                ) { choice ->
                    draft = draft.copy(
                        gpu = when (choice) {
                            "CPU" -> GpuConfig.Cpu
                            "ALL" -> GpuConfig.All
                            else -> GpuConfig.Auto
                        },
                    )
                }
            } else {
                DropdownBox(
                    label = "Execution provider",
                    selected = draft.executionProvider ?: "CPU",
                    options = listOf("NNAPI", "XNNPACK", "CPU"),
                    modifier = Modifier.fillMaxWidth(),
                ) { choice ->
                    draft = draft.copy(executionProvider = if (choice == "CPU") null else choice)
                }
            }

            Text("Creativity", style = MaterialTheme.typography.labelMedium)
            SliderRow("Temperature", draft.temperature, 0f..1.5f, 15) { draft = draft.copy(temperature = it) }
            SliderRow("Top-p", draft.topP, 0.5f..1f, 10) { draft = draft.copy(topP = it) }
            SliderRow("Top-k", draft.topK.toFloat(), 1f..200f, 39) { draft = draft.copy(topK = it.toInt()) }

            OutlinedTextField(
                value = draft.systemPrompt,
                onValueChange = { draft = draft.copy(systemPrompt = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("System prompt (applies on Apply)") },
                minLines = 2,
                maxLines = 4,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SliderRow("Retries", draft.maxRetries.toFloat(), 0f..5f, 5, Modifier.weight(1f)) {
                    draft = draft.copy(maxRetries = it.toInt())
                }
                SliderRow("Timeout (s)", draft.downloadTimeoutSeconds.toFloat(), 10f..300f, 29, Modifier.weight(1f)) {
                    draft = draft.copy(downloadTimeoutSeconds = it.toLong())
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { onApply(draft) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Apply")
        }
    }
}
