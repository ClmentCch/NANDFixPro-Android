package com.nandfixpro.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.FirmwareState
import com.nandfixpro.android.nand.NandSize
import com.nandfixpro.android.ui.components.InfoBanner
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.OperationBanner
import com.nandfixpro.android.ui.components.PrimaryAction
import com.nandfixpro.android.ui.components.ScreenScaffold
import com.nandfixpro.android.ui.components.StatRow
import com.nandfixpro.android.ui.components.StatusPill
import com.nandfixpro.android.ui.theme.NandAmber
import com.nandfixpro.android.ui.theme.NandTeal

@Composable
fun FirmwareScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val state by viewModel.firmwareState.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.scanFirmware(it) }
    }

    ScreenScaffold(
        title = "Informations firmware",
        subtitle = "Inventaire de dossier et validation structurelle",
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
                text = "Cette page inventorie un dossier firmware fourni par l'utilisateur. Elle ne télécharge " +
                    "aucun firmware et ne prétend pas déterminer sa version sans parser les NCA.",
                color = NandAmber,
            )
            PrimaryAction(
                text = "CHOISIR UN DOSSIER FIRMWARE",
                icon = Icons.Filled.Folder,
                onClick = { picker.launch(null) },
            )
            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            when (val current = state) {
                is FirmwareState.Empty -> NandCard(title = "Structure attendue", icon = Icons.Filled.SystemUpdate) {
                    Text("• Contents/registered (NCA installables)", style = MaterialTheme.typography.bodyMedium)
                    Text("• Contents/placehld (placeholders)", style = MaterialTheme.typography.bodyMedium)
                    Text("• fichiers .nca et .cnmt.nca", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Le module vérifie la présence de ces éléments et compte les fichiers. " +
                            "Il ne valide pas cryptographiquement les NCA.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is FirmwareState.Failed -> NandCard(title = "Analyse impossible") {
                    Text(current.message)
                }

                is FirmwareState.Loaded -> {
                    val inventory = current.inventory
                    NandCard(title = "Inventaire", icon = Icons.Filled.SystemUpdate) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill(
                                if (inventory.ncaCount > 0) "NCA détectés" else "Aucun NCA",
                                if (inventory.ncaCount > 0) NandTeal else NandAmber,
                            )
                            StatusPill("${inventory.fileCount} fichiers", NandTeal)
                        }
                        StatRow("Racine", inventory.rootName)
                        StatRow("Taille totale", NandSize.human(inventory.totalBytes))
                        StatRow("Fichiers .nca", "${inventory.ncaCount}")
                        StatRow("Fichiers .cnmt.nca", "${inventory.cnmtCount}")
                        StatRow("Contents/registered", "${inventory.registeredCount} fichiers")
                        StatRow("Contents/placehld", "${inventory.placeholderCount} fichiers")
                        StatRow("bootloader", if (inventory.hasBootloader) "présent" else "absent")
                        StatRow("atmosphere", if (inventory.hasAtmosphere) "présent" else "absent")
                        if (inventory.folders.isNotEmpty()) {
                            StatRow("Dossiers racine", inventory.folders.joinToString(", "))
                        }
                    }
                    NandCard(title = "Notes", accent = NandAmber) {
                        inventory.notes.forEach { note ->
                            Text(
                                "• $note",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
