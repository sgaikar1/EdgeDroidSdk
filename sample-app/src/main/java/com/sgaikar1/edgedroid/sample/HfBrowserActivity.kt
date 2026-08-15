package com.sgaikar1.edgedroid.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sgaikar1.edgedroid.common.ModelFormat
import com.sgaikar1.edgedroid.core.DeviceCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A user-selectable parameter-count filter. Both bounds 0 = any size. */
data class ParamsRange(val min: Float, val max: Float, val label: String)

private val PARAMS_OPTIONS = listOf(
    ParamsRange(0f, 0f, "Any"),
    ParamsRange(0f, 3f, "≤3B"),
    ParamsRange(3f, 8f, "3–8B"),
    ParamsRange(8f, 20f, "8–20B"),
    ParamsRange(20f, 1e9f, "≥20B"),
)

class HfBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialFilter = intent.getStringExtra(EXTRA_FILTER) ?: "LLAMA"
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HfBrowserScreen(initialFilter) { selection ->
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(EXTRA_SELECTION, selection),
                        )
                        finish()
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_FILTER = "filter"
        const val EXTRA_SELECTION = "selection"
    }
}

@Composable
fun HfBrowserScreen(initialFilter: String, onPicked: (HfModelSelection) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = (context.applicationContext as EdgeDroidApp).sampleStore
    val caps = store.capabilities
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(if (initialFilter == "ONNX") "onnx" else "gguf") }
    // Default the size cap to what the device's free storage can actually hold.
    var maxGb by remember { mutableStateOf(recommendedMaxGb(caps)) }
    val maxBytes = if (maxGb <= 0) 0L else maxGb * 1024L * 1024L * 1024L
    var paramsRange by remember { mutableStateOf(PARAMS_OPTIONS[0]) }
    var showAll by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<HfModelSummary>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf<HfModelSummary?>(null) }
    var files by remember { mutableStateOf<List<HfFile>>(emptyList()) }
    var loadingFiles by remember { mutableStateOf(false) }
    var filesError by remember { mutableStateOf<String?>(null) }

    fun doSearch() {
        scope.launch {
            searching = true
            error = null
            results = try {
                val fmt = if (filter == "onnx") ModelFormat.ONNX else ModelFormat.GGUF
                withContext(Dispatchers.IO) {
                    HfHubClient.searchModels(query, filter, maxBytes, paramsRange.min, paramsRange.max)
                }.map { summary ->
                    val best = summary.bestFile
                    if (best == null) {
                        summary.copy(deviceFit = DeviceFit.BLOCKED_RUNTIME, deviceFitReason = "No usable file")
                    } else {
                        val (fit, reason) = deviceFit(best, fmt, caps)
                        summary.copy(deviceFit = fit, deviceFitReason = reason)
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: "Search failed"
                emptyList()
            }
            searching = false
        }
    }

    fun pickModel(model: HfModelSummary) {
        scope.launch {
            loadingFiles = true
            filesError = null
            files = try {
                withContext(Dispatchers.IO) { HfHubClient.listFiles(model.id) }
            } catch (t: Throwable) {
                filesError = t.message ?: "Failed to list files"
                emptyList()
            }
            loadingFiles = false
            selectedModel = model
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Browse HuggingFace", style = MaterialTheme.typography.titleLarge)
        Text(
            deviceLine(caps),
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                label = { Text("Search models") },
                singleLine = true,
            )
            Button(onClick = { doSearch() }) { Text("Search") }
        }

        Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == "gguf", onClick = { filter = "gguf" }, label = { Text("GGUF") })
            FilterChip(selected = filter == "onnx", onClick = { filter = "onnx" }, label = { Text("ONNX") })
        }

        Text("Max model size:", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 4, 8, 0).forEach { gb ->
                FilterChip(
                    selected = maxGb == gb,
                    onClick = { maxGb = gb },
                    label = { Text(if (gb == 0) "Any" else "≤${gb}GB") },
                )
            }
        }
        Text("Parameters:", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PARAMS_OPTIONS.forEach { range ->
                FilterChip(
                    selected = paramsRange == range,
                    onClick = { paramsRange = range },
                    label = { Text(range.label) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                "Only chat-capable models (no mmproj / embeddings / rerankers) under the size cap are listed.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = showAll,
                onClick = { showAll = !showAll },
                label = { Text(if (showAll) "Showing all" else "Device-ready") },
            )
        }

        if (searching) {
            Row(Modifier.padding(8.dp)) {
                CircularProgressIndicator(Modifier.width(18.dp).padding(0.dp))
                Spacer(Modifier.width(8.dp))
                Text("Searching…")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (selectedModel == null) {
            val visible = if (showAll) {
                results
            } else {
                results.filter { it.deviceFit == DeviceFit.FITS || it.deviceFit == DeviceFit.LARGE_FOR_RAM }
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(visible) { model ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { pickModel(model) }.padding(vertical = 8.dp),
                    ) {
                        Text(model.id, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "⬇ ${model.downloads} · ♥ ${model.likes} · from ${model.bestFile?.let { formatBytes(it.size) } ?: "?"}" +
                                model.paramsB?.let { " · ~${formatParams(it)}" } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        model.deviceFit?.let { fit ->
                            Text(
                                text = (model.deviceFitReason ?: "") + when (fit) {
                                    DeviceFit.FITS -> ""
                                    DeviceFit.LARGE_FOR_RAM -> " (may be slow)"
                                    else -> " — not supported on this device"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when (fit) {
                                    DeviceFit.FITS -> MaterialTheme.colorScheme.primary
                                    DeviceFit.LARGE_FOR_RAM -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }
        } else {
            val model = selectedModel!!
            Text(model.id, style = MaterialTheme.typography.titleMedium)
            Text("Pick a file (supported, ≤ cap):", style = MaterialTheme.typography.labelMedium)
            if (loadingFiles) { Text("Loading files…") }
            filesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val fmt = if (filter == "onnx") ModelFormat.ONNX else ModelFormat.GGUF
            val eligible = files.filter { isEligibleFile(it.name, fmt, maxBytes) }
            val visible = if (showAll) {
                eligible
            } else {
                eligible.filter { deviceFit(it, fmt, caps).first != DeviceFit.BLOCKED_STORAGE }
            }
            if (visible.isEmpty() && !loadingFiles) {
                Text(
                    "No supported files under this size cap.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(visible) { file ->
                    val isTarget = if (filter == "onnx") file.name.endsWith(".onnx") else file.name.endsWith(".gguf")
                    if (isTarget) {
                        val (fit, reason) = deviceFit(file, fmt, caps)
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val format = if (filter == "onnx") ModelFormat.ONNX else ModelFormat.GGUF
                                onPicked(
                                    HfModelSelection(
                                        repoId = model.id,
                                        fileName = file.name,
                                        format = format,
                                        sizeBytes = file.size.takeIf { it > 0 },
                                    ),
                                )
                            }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                if (fit != DeviceFit.FITS) {
                                    Text(
                                        reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (fit == DeviceFit.LARGE_FOR_RAM) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            }
                            Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { selectedModel = null }) { Text("Back to results") }
        }
    }
}

/** Largest size chip (1/2/4/8) the device's free storage can hold, defaulting to 4 GB. */
private fun recommendedMaxGb(caps: DeviceCapabilities): Int {
    if (caps.freeStorageBytes <= 0L) return 4
    val freeGb = caps.freeStorageBytes.toDouble() / (1024.0 * 1024 * 1024)
    val usableGb = (freeGb - 0.25).coerceAtLeast(1.0)
    return when {
        usableGb >= 8.0 -> 8
        usableGb >= 4.0 -> 4
        usableGb >= 2.0 -> 2
        else -> 1
    }
}

private fun deviceLine(caps: DeviceCapabilities): String = buildString {
    append("Device: ")
    if (caps.cpuCores > 0) append("${caps.cpuCores} cores · ")
    if (caps.totalRamBytes > 0) append(formatBytes(caps.totalRamBytes) + " RAM · ")
    if (caps.freeStorageBytes > 0) append(formatBytes(caps.freeStorageBytes) + " free · ")
    append(if (caps.vulkanSupported) "Vulkan ✓" else "CPU only")
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "$bytes B"
}

private fun formatParams(b: Float): String =
    if (b >= 1f) "%.1fB".format(b) else "%.0fM".format(b * 1000f)
