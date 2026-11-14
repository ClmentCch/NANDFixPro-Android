package com.nandfixpro.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.FirmwareState
import com.nandfixpro.android.ProdKeysState
import com.nandfixpro.android.data.StorageRepository.VolumeInfo
import com.nandfixpro.android.nand.NandSize
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
import com.nandfixpro.android.ui.theme.NandTeal

@Composable
fun FirmwareScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val state by viewModel.firmwareState.collectAsStateWithLifecycle()
    val prodKeys by viewModel.prodKeys.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val volumes = remember { viewModel.storageVolumes() }
    var showVolumes by remember { mutableStateOf(false) }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.scanFirmware(it) }
    }
    val keysPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.loadProdKeys(it) }
    }

    ScreenScaffold(
        title = "Informations firmware",
        subtitle = "Dossier, prod.keys et inventaire — lecture seule",
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
                text = "Parcours en trois étapes : dossier firmware, validation de prod.keys, inventaire. " +
                    "Aucun NCA n'est déchiffré et aucun firmware n'est installé dans cette version.",
                color = NandAmber,
            )

            NandCard(title = "Étape 1 · dossier firmware", icon = Icons.Filled.Folder) {
                Text(
                    "Choisissez un dossier contenant Contents/registered, des fichiers .nca/.cnmt.nca " +
                        "ou une structure bootloader/atmosphere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryAction(
                    text = "CHOISIR UN DOSSIER FIRMWARE",
                    icon = Icons.Filled.Folder,
                    onClick = { showVolumes = true },
                )
                Text(
                    "Android interdit de sélectionner la racine du stockage interne (message « Pour protéger " +
                        "votre vie privée »). Ouvrez un sous-dossier, ou choisissez la carte SD/USB dans la liste. " +
                        "Une cible UMS Hekate n'apparaît pas dans le sélecteur Android : Android n'a pas de " +
                        "pilote UMS. Copiez le dossier sur le téléphone ou utilisez un lecteur de carte.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            NandCard(title = "Étape 2 · prod.keys (carte SD)", icon = Icons.Filled.Key) {
                Text(
                    "Chargez votre fichier prod.keys depuis la carte SD ou un dossier du téléphone. " +
                        "La structure est vérifiée localement ; les valeurs ne sont jamais affichées, " +
                        "journalisées ni transmises.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryAction(
                    text = "CHOISIR PROD.KEYS",
                    icon = Icons.Filled.Key,
                    onClick = { keysPicker.launch(arrayOf("*/*")) },
                )
                when (val keys = prodKeys) {
                    is ProdKeysState.Empty -> Text(
                        "Aucun fichier chargé. prod.keys est généralement à la racine de la carte SD " +
                            "ou dans le dossier switch/.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is ProdKeysState.Failed -> Text(
                        keys.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is ProdKeysState.Loaded -> {
                        val summary = keys.summary
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill(
                                if (summary.looksComplete) "Structure complète" else "Structure partielle",
                                if (summary.looksComplete) NandTeal else NandAmber,
                            )
                            StatusPill("${summary.validKeys} clés", NandTeal)
                        }
                        StatRow("Fichier", summary.fileName)
                        StatRow("Entrées valides", "${summary.validKeys} / ${summary.totalLines}")
                        StatRow("Lignes invalides", "${summary.invalidLines}")
                        StatRow(
                            "Master keys",
                            if (summary.masterKeys.isEmpty()) "aucune"
                            else "${summary.masterKeys.size} · génération max 0x${summary.newestMasterKey?.toString(16)}",
                        )
                        StatRow("Keys area", if (summary.hasKeyAreaKeys) "présentes" else "absentes")
                        NandDivider()
                        Text(
                            "Validation structurelle uniquement : aucune clé n'est utilisée pour déchiffrer " +
                                "un NCA et aucun firmware n'est modifié.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            NandCard(title = "Étape 3 · inventaire", icon = Icons.Filled.SystemUpdate) {
                Text(
                    "L'inventaire compte les fichiers et vérifie la présence des dossiers attendus. " +
                        "Il ne déduit pas la version du firmware.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state is FirmwareState.Empty) {
                    Text(
                        "• Contents/registered (NCA installables)\n" +
                            "• Contents/placehld (placeholders)\n" +
                            "• fichiers .nca et .cnmt.nca",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            when (val current = state) {
                is FirmwareState.Empty -> Unit

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

            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            Text(
                "Aucune écriture sur la carte SD, le stockage interne ou une cible USB. " +
                    "Les fichiers sélectionnés sont lus en lecture seule.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showVolumes) {
        VolumeDialog(
            volumes = volumes,
            onDismiss = { showVolumes = false },
            onPick = { volume ->
                showVolumes = false
                treePicker.launch(volume.treeUri)
            },
            onBrowse = {
                showVolumes = false
                treePicker.launch(null)
            },
        )
    }
}

@Composable
private fun VolumeDialog(
    volumes: List<VolumeInfo>,
    onDismiss: () -> Unit,
    onPick: (VolumeInfo) -> Unit,
    onBrowse: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choisir un emplacement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Android interdit la racine du stockage interne. Pour la carte SD ou un lecteur USB, " +
                        "choisissez l'emplacement ci-dessous, puis ouvrez le dossier firmware.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (volumes.isEmpty()) {
                    Text(
                        "Aucun volume monté n'a été détecté. Insérez la carte SD dans le téléphone ou " +
                            "branchez un lecteur de carte USB. La cible UMS de Hekate ne peut pas être " +
                            "montée par Android.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NandAmber,
                    )
                }
                volumes.forEach { volume ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(volume) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            if (volume.isPrimary) Icons.Filled.Storage else Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = if (volume.isRemovable) NandTeal else MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(volume.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (volume.isPrimary) {
                                    "Stockage interne — choisissez un sous-dossier"
                                } else {
                                    "Volume amovible — racine accessible"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onBrowse) { Text("Parcourir avec Android") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
