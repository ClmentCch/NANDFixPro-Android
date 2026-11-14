package com.nandfixpro.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.data.StorageRepository
import com.nandfixpro.android.nand.NandSize
import com.nandfixpro.android.ui.components.ConfirmDialog
import com.nandfixpro.android.ui.components.EmptyState
import com.nandfixpro.android.ui.components.InfoBanner
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.NandDivider
import com.nandfixpro.android.ui.components.OperationBanner
import com.nandfixpro.android.ui.components.PrimaryAction
import com.nandfixpro.android.ui.components.ScreenScaffold
import com.nandfixpro.android.ui.components.StatRow
import com.nandfixpro.android.ui.components.StatusPill
import com.nandfixpro.android.ui.theme.NandTeal
import com.nandfixpro.android.ui.theme.NandTextFaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupScreen(viewModel: AppViewModel) {
    val backups by viewModel.backups.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingDelete by remember { mutableStateOf<StorageRepository.BackupArtifact?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedUri = uri
    }

    LaunchedEffect(Unit) { viewModel.refreshBackups() }

    val selectedName = selectedUri?.let { uri ->
        DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: "fichier sélectionné"
    }

    ScreenScaffold(title = "Backup Manager", subtitle = "Copies locales hachées et vérifiables") { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            InfoBanner(
                text = "Les sauvegardes sont copiées dans le stockage privé de l'application, avec un manifeste " +
                    "SHA-256. Aucune donnée n'est envoyée sur Internet.",
                color = NandTeal,
            )

            NandCard(title = "Nouvelle copie vérifiée", icon = Icons.Filled.Save) {
                StatRow("Fichier sélectionné", selectedName ?: "Aucun")
                StatRow("Espace disponible", NandSize.human(viewModel.storageFreeSpace()))
                StatRow("Limite par copie", "8 Gio")
                PrimaryAction(
                    text = if (selectedUri == null) "CHOISIR UN FICHIER" else "CHANGER DE FICHIER",
                    icon = Icons.Filled.Description,
                    onClick = { picker.launch(arrayOf("*/*")) },
                )
                PrimaryAction(
                    text = "CRÉER UNE COPIE HACHÉE",
                    icon = Icons.Filled.Save,
                    enabled = selectedUri != null,
                    onClick = { selectedUri?.let { viewModel.createBackup(it) } },
                )
                Text(
                    "Une image eMMC complète (29–58 Gio) dépasse l'espace d'un téléphone. La limite de 8 Gio " +
                        "protège contre une copie tronquée silencieuse.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            NandCard(title = "Sauvegardes (${backups.size})", icon = Icons.Filled.Verified) {
                if (backups.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Save,
                        title = "Aucune sauvegarde",
                        text = "Sélectionnez BOOT0, BOOT1, PRODINFO ou un dump pour en créer une copie vérifiée.",
                    )
                } else {
                    backups.forEach { artifact ->
                        NandCard(title = artifact.manifest.kind, icon = Icons.Filled.Description) {
                            Text(artifact.file.name, style = MaterialTheme.typography.titleMedium)
                            StatRow("Taille", NandSize.human(artifact.manifest.size))
                            StatRow(
                                "Créée le",
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)
                                    .format(Date(artifact.manifest.createdAt)),
                            )
                            StatRow("SHA-256", artifact.manifest.sha256, mono = true)
                            StatusPill(
                                text = if (artifact.manifest.sha256 == "—") "Non vérifiable" else "Hash enregistré",
                                color = if (artifact.manifest.sha256 == "—") NandTextFaint else NandTeal,
                            )
                            if (artifact.manifest.note.isNotBlank()) {
                                Text(
                                    artifact.manifest.note,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            NandDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { viewModel.verifyBackup(artifact) }) {
                                    Text("Vérifier")
                                }
                                TextButton(onClick = { pendingDelete = artifact }) {
                                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        ConfirmDialog(
            title = "Supprimer cette sauvegarde ?",
            message = "${toDelete.file.name} et son manifeste seront supprimés définitivement du stockage " +
                "de l'application.",
            confirmLabel = "Supprimer",
            onConfirm = { viewModel.deleteBackup(toDelete) },
            onDismiss = { pendingDelete = null },
        )
    }
}
