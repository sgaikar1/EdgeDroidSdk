package com.sgaikar1.edgedroid.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sgaikar1.edgedroid.core.GpuConfig

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModelFactory((application as EdgeDroidApp))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val reasoningText by viewModel.reasoningText.collectAsStateWithLifecycle()
    val progress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val compatibility by viewModel.compatibility.collectAsStateWithLifecycle()
    val embeddingResult by viewModel.embeddingResult.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }

    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "EdgeDroid — on-device LLM",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Runtime: ${config.runtime} · Model: ${config.model.label} · State: ${engineState}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        SettingsPanel(
            config = config,
            onApply = viewModel::applyConfig,
        )

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { viewModel.downloadModel() }, enabled = progress == null) {
                Text("Download")
            }
            Button(onClick = { viewModel.loadModel() }, enabled = !isLoading) {
                Text(if (isLoading) "Loading…" else "Load")
            }
            TextButton(onClick = { viewModel.checkCompatibility() }) { Text("Check") }
            if (config.model.embeddingCapable) {
                TextButton(onClick = { viewModel.runEmbeddings() }) { Text("Embed") }
            }
            TextButton(onClick = { viewModel.stop() }) { Text("Stop") }
            TextButton(onClick = { viewModel.clear() }) { Text("Clear") }
        }

        embeddingResult?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
        compatibility?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.contains("ERROR:")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        progress?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
            reasoningText?.let { current ->
                item { ThinkingBubble(current) }
            }
            streamingText?.let { current ->
                item { MessageBubble(ChatMessage("assistant", current)) }
            }
            item {
                if (isLoading || (engineState == com.sgaikar1.edgedroid.core.LlmEngineState.Generating &&
                        streamingText == null && reasoningText == null)
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Thinking…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask the model…") },
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.send(input)
                    input = ""
                },
                enabled = input.isNotBlank() && config.model.chatCapable,
            ) {
                Text("Send")
            }
        }
        if (!config.model.chatCapable) {
            Text(
                text = "This model is embeddings-only — chat is disabled for the ONNX all-MiniLM preset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPanel(config: SampleConfig, onApply: (SampleConfig) -> Unit) {
    var draft by remember { mutableStateOf(config) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(config) { draft = config }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Runtime: ${draft.runtime} · Model: ${draft.model.label}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Temp ${"%.2f".format(draft.temperature)} · top-p ${"%.2f".format(draft.topP)} · top-k ${draft.topK}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide settings" else "Settings")
                }
                Button(onClick = { onApply(draft) }) { Text("Apply") }
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .verticalScroll(rememberScrollState())
                        .height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                            draft = draft.copy(modelId = model.id)
                        }
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
                        maxLines = 3,
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
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    Column(modifier = modifier) {
        Text("$label: ${if (value % 1f == 0f) value.toInt() else "%.2f".format(value)}", style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DropdownBox(
    label: String,
    selected: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun ThinkingBubble(reasoning: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Card(
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Reasoning", style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
                Text(reasoning, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(if (isUser) 0.85f else 1f),
        ) {
            Column(Modifier.padding(12.dp)) {
                message.reasoning?.let { r ->
                    ThinkingBubble(r)
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewChat() {
    MaterialTheme {
        MessageBubble(ChatMessage("assistant", "Hello!", reasoning = "I am thinking"))
    }
}
