package com.nandfixpro.android.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.data.LogLevel
import com.nandfixpro.android.ui.components.LogConsole
import com.nandfixpro.android.ui.components.OperationBanner
import com.nandfixpro.android.ui.components.ScreenScaffold

@Composable
fun LogsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val logs by viewModel.logEntries.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    var selectedLevels by remember { mutableStateOf(LogLevel.entries.toSet()) }
    val filtered = remember(logs, selectedLevels) { logs.filter { it.level in selectedLevels } }

    ScreenScaffold(
        title = "Journal",
        subtitle = "${filtered.size} entrée(s) affichée(s)",
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = level in selectedLevels,
                        onClick = {
                            selectedLevels = if (level in selectedLevels) {
                                selectedLevels - level
                            } else {
                                selectedLevels + level
                            }
                        },
                        label = { Text(level.label) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = viewModel::exportLogs) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.height(16.dp))
                    Text("  Exporter le journal")
                }
                TextButton(onClick = viewModel::clearLogs) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.height(16.dp))
                    Text("  Effacer", color = MaterialTheme.colorScheme.error)
                }
            }

            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            LogConsole(
                entries = filtered,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                maxEntries = 1000,
            )
        }
    }
}
