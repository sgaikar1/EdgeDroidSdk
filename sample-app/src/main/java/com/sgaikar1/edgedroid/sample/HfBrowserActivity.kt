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
import androidx.compose.ui.unit.dp
import com.sgaikar1.edgedroid.common.ModelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(if (initialFilter == "ONNX") "onnx" else "gguf") }
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
                withContext(Dispatchers.IO) { HfHubClient.searchModels(query, filter) }
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

        if (searching) {
            Row(Modifier.padding(8.dp)) {
                CircularProgressIndicator(Modifier.width(18.dp).padding(0.dp))
                Spacer(Modifier.width(8.dp))
                Text("Searching…")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (selectedModel == null) {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(results) { model ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { pickModel(model) }.padding(vertical = 8.dp),
                    ) {
                        Text(model.id, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "⬇ ${model.downloads} · ♥ ${model.likes}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            val model = selectedModel!!
            Text(model.id, style = MaterialTheme.typography.titleMedium)
            Text("Pick a file:", style = MaterialTheme.typography.labelMedium)
            if (loadingFiles) { Text("Loading files…") }
            filesError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(files) { file ->
                    val isTarget = if (filter == "onnx") file.name.endsWith(".onnx") else file.name.endsWith(".gguf")
                    if (isTarget) {
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
                        ) {
                            Text(file.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "$bytes B"
}
