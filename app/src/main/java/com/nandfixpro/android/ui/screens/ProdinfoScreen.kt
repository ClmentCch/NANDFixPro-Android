package com.nandfixpro.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.ProdinfoState
import com.nandfixpro.android.nand.ProdinfoEngine
import com.nandfixpro.android.ui.components.ConfirmDialog
import com.nandfixpro.android.ui.components.InfoBanner
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.NandDivider
import com.nandfixpro.android.ui.components.OperationBanner
import com.nandfixpro.android.ui.components.PrimaryAction
import com.nandfixpro.android.ui.components.ScreenScaffold
import com.nandfixpro.android.ui.components.SecondaryAction
import com.nandfixpro.android.ui.components.StatRow
import com.nandfixpro.android.ui.components.StatusPill
import com.nandfixpro.android.ui.theme.NandAmber
import com.nandfixpro.android.ui.theme.NandRed
import com.nandfixpro.android.ui.theme.NandTeal
import com.nandfixpro.android.ui.theme.NandTextFaint

@Composable
fun ProdinfoScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val state by viewModel.prodinfoState.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var serial by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("Europe") }
    var color by remember { mutableStateOf("") }
    var showRegionDialog by remember { mutableStateOf(false) }
    var showExportConfirm by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.loadProdinfo(it) }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let {
            viewModel.exportProdinfo(
                destination = it,
                edit = ProdinfoEngine.Edit(
                    serial = serial.takeIf { value -> value.isNotBlank() },
                    wifiRegion = region.takeIf { value -> value.isNotBlank() },
                    color = color.takeIf { value -> value.isNotBlank() },
                ),
            )
        }
    }

    val loaded = state as? ProdinfoState.Loaded
    LaunchedEffect(loaded?.summary) {
        val summary = loaded?.summary ?: return@LaunchedEffect
        serial = summary.serialNumber
        region = if (summary.wifiRegion in ProdinfoEngine.REGION_CODES) summary.wifiRegion else "Europe"
        color = summary.colors["HousingBezelColor"] ?: ""
    }

    ScreenScaffold(
        title = "PRODINFO Analyzer",
        subtitle = "CAL0 · CRC16 · SHA-256 · export vérifié",
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
            InfoBanner(
                text = "Le PRODINFO (CAL0) identifie la console. L'application le lit localement, sans " +
                    "téléversement. Toute modification est exportée dans une NOUVELLE copie, jamais en place.",
                color = NandTeal,
            )
            PrimaryAction(
                text = "CHOISIR UN PRODINFO DÉCHIFFRÉ",
                icon = Icons.Filled.Key,
                onClick = { picker.launch(arrayOf("*/*")) },
            )
            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            when (val current = state) {
                is ProdinfoState.Empty -> NandCard(title = "Formats acceptés", icon = Icons.Filled.Description) {
                    Text("• PRODINFO, PRODINFO.bin, prodinfo.dec", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Le fichier doit commencer par la signature CAL0. Un PRODINFO chiffré est détecté mais " +
                            "ne peut pas être analysé sans les clés de la console.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ProdinfoState.Failed -> NandCard(title = "PRODINFO refusé", accent = NandRed) {
                    Text(current.message, style = MaterialTheme.typography.bodyMedium)
                }

                is ProdinfoState.Loaded -> {
                    val summary = current.summary
                    val verification = current.verification
                    NandCard(title = "Identité de la console", icon = Icons.Filled.Key) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(summary.modelName, style = MaterialTheme.typography.titleMedium)
                            StatusPill(
                                if (verification.valid) "Intègre" else "Invalide",
                                if (verification.valid) NandTeal else NandRed,
                            )
                        }
                        StatRow("Numéro de série", summary.serialNumber.ifBlank { "—" }, mono = true)
                        StatRow("Région WiFi", summary.wifiRegion)
                        StatRow("MAC WiFi", summary.wifiMac.ifBlank { "—" }, mono = true)
                        StatRow("MAC Bluetooth", summary.bluetoothMac.ifBlank { "—" }, mono = true)
                        StatRow("Configuration ID", summary.configurationId.take(32) + "…", mono = true)
                        StatRow("Taille du fichier", "${summary.fileSize} octets")
                        StatRow("Body size déclaré", "${summary.declaredBodySize} octets")
                        StatRow("Compteur d'écritures", "${summary.updateCount}")
                    }

                    NandCard(title = "Couleurs", icon = Icons.Filled.Info) {
                        summary.colors.forEach { (name, hex) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(parseHexColor(hex)),
                                )
                                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text("#$hex", style = MaterialTheme.typography.labelMedium, color = NandTextFaint)
                            }
                        }
                    }

                    NandCard(title = "Vérification des checksums", icon = Icons.Filled.CheckCircle) {
                        Text(
                            "${verification.errors} erreur(s), ${verification.warnings} avertissement(s). " +
                                "Une erreur de SHA-256 déclenche INVALID_PRODINFO dans Atmosphère.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        verification.checks.forEach { check ->
                            val color = when (check.severity) {
                                ProdinfoEngine.Severity.OK -> NandTeal
                                ProdinfoEngine.Severity.WARNING -> NandAmber
                                ProdinfoEngine.Severity.ERROR -> NandRed
                            }
                            val icon = when (check.severity) {
                                ProdinfoEngine.Severity.OK -> Icons.Filled.CheckCircle
                                ProdinfoEngine.Severity.WARNING -> Icons.Filled.Warning
                                ProdinfoEngine.Severity.ERROR -> Icons.Filled.Error
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(icon, contentDescription = null, tint = color)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(check.name, style = MaterialTheme.typography.bodyLarge, color = color)
                                    Text(
                                        check.detail,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    NandCard(title = "Édition avancée (copie uniquement)", icon = Icons.Filled.Warning, accent = NandAmber) {
                        InfoBanner(
                            text = "Modifier le numéro de série a des conséquences en ligne. Cette action " +
                                "n'écrit que dans un fichier exporté, après recalcul et vérification des checksums.",
                            color = NandAmber,
                            icon = Icons.Filled.Warning,
                        )
                        OutlinedTextField(
                            value = serial,
                            onValueChange = { serial = it.take(14) },
                            label = { Text("Numéro de série (14 caractères)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SecondaryAction(
                            text = "Région WiFi : $region",
                            icon = Icons.Filled.Info,
                            onClick = { showRegionDialog = true },
                        )
                        OutlinedTextField(
                            value = color,
                            onValueChange = { color = it.take(6) },
                            label = { Text("Couleur HousingBezelColor (RRGGBB)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        NandDivider()
                        PrimaryAction(
                            text = "EXPORTER UNE COPIE VÉRIFIÉE",
                            icon = Icons.Filled.Description,
                            onClick = {
                                if (settings.requireConfirmation) showExportConfirm = true
                                else exportLauncher.launch("PRODINFO-edité.bin")
                            },
                        )
                    }
                }
            }
        }
    }

    if (showRegionDialog) {
        AlertDialog(
            onDismissRequest = { showRegionDialog = false },
            title = { Text("Région WiFi") },
            text = {
                Column {
                    ProdinfoEngine.REGION_CODES.keys.forEach { name ->
                        TextButton(onClick = {
                            region = name
                            showRegionDialog = false
                        }) { Text(name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRegionDialog = false }) { Text("Fermer") } },
        )
    }

    if (showExportConfirm) {
        ConfirmDialog(
            title = "Exporter une copie modifiée ?",
            message = "Un nouveau fichier sera créé avec les checksums recalculés. " +
                "Le PRODINFO d'origine n'est pas modifié et rien n'est écrit sur la console.",
            confirmLabel = "Exporter",
            onConfirm = { exportLauncher.launch("PRODINFO-edité.bin") },
            onDismiss = { showExportConfirm = false },
        )
    }
}

private fun parseHexColor(hex: String): Color = runCatching {
    Color(0xFF000000L or hex.toLong(16).toInt().toLong())
}.getOrDefault(Color(0xFF303030))
