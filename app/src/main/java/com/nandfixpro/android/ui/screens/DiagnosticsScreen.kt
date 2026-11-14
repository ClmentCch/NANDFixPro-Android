package com.nandfixpro.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.nand.NandSize
import com.nandfixpro.android.ui.components.InfoBanner
import com.nandfixpro.android.ui.components.LogConsole
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.NandDivider
import com.nandfixpro.android.ui.components.OperationBanner
import com.nandfixpro.android.ui.components.ScreenScaffold
import com.nandfixpro.android.ui.components.SecondaryAction
import com.nandfixpro.android.ui.components.StatRow
import com.nandfixpro.android.ui.components.StatusPill
import com.nandfixpro.android.ui.theme.NandAmber
import com.nandfixpro.android.ui.theme.NandRed
import com.nandfixpro.android.ui.theme.NandTeal

@Composable
fun DiagnosticsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val logs by viewModel.logEntries.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val report by viewModel.selfTest.collectAsStateWithLifecycle()
    val info = viewModel.deviceInfo()

    ScreenScaffold(
        title = "Diagnostics",
        subtitle = "Appareil · moteurs · journaux",
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NandCard(title = "Appareil", icon = Icons.Filled.Usb) {
                StatRow("Modèle", info.model)
                StatRow("Fabricant", info.manufacturer)
                StatRow("Android", "${info.androidRelease} (API ${info.sdk})")
                StatRow("ABI", info.abis.joinToString(", "))
                StatusPill(
                    text = if (info.isArm64) "ARM64 détecté" else "ARM64 absent",
                    color = if (info.isArm64) NandTeal else NandAmber,
                )
                StatRow("USB Host", if (info.usbHostSupported) "Pris en charge" else "Non pris en charge")
                StatRow("Version application", info.appVersion)
                StatRow("Stockage applicatif libre", NandSize.human(info.freeStorage))
            }

            NandCard(title = "Auto-test des moteurs", icon = Icons.Filled.CheckCircle) {
                Text(
                    "Exécute les moteurs sur des structures synthétiques valides : GPT, PRODINFO, CRC, " +
                        "détection de jeu découpé et trames SCSI. Aucun matériel n'est requis.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryAction(
                    text = "LANCER L'AUTO-TEST",
                    icon = Icons.Filled.PlayArrow,
                    onClick = { viewModel.runSelfTest() },
                )
                report?.let { current ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${current.passed}/${current.results.size} contrôles réussis")
                        StatusPill(
                            if (current.allPassed) "Tout est OK" else "Échec partiel",
                            if (current.allPassed) NandTeal else NandRed,
                        )
                    }
                    NandDivider()
                    current.results.forEach { result ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                if (result.passed) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                contentDescription = null,
                                tint = if (result.passed) NandTeal else NandRed,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(result.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    result.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            NandCard(title = "Journal (${logs.size} entrées)", icon = Icons.Filled.BugReport) {
                LogConsole(entries = logs, modifier = Modifier.height(200.dp), maxEntries = 200)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = viewModel::exportLogs) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.height(16.dp))
                        Text("  Exporter")
                    }
                    TextButton(onClick = viewModel::clearLogs) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.height(16.dp))
                        Text("  Effacer", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            NandCard(title = "Modules non portables tels quels", icon = Icons.Filled.Info, accent = NandAmber) {
                InfoBanner(
                    text = "Le projet original est un outil Windows. Ces composants ne peuvent pas tourner sur Android :",
                    color = NandAmber,
                )
                AppViewModel.KNOWN_MODULE_LIMITS.forEach { limit ->
                    Text(
                        "• $limit",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Le mode lecture seule est une décision de conception, pas une limitation technique : " +
                        "aucune commande WRITE SCSI ni écriture de partition n'est implémentée dans l'APK.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            NandCard(title = "Accès au journal complet", icon = Icons.Filled.Description) {
                Text(
                    "Utilisez l'onglet Journal pour filtrer par niveau et exporter un rapport horodaté.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
