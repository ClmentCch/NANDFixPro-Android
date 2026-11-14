package com.nandfixpro.android.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.nand.NandFileDetector
import com.nandfixpro.android.nand.NandSize
import com.nandfixpro.android.nand.SplitNandDetector
import com.nandfixpro.android.ui.Routes
import com.nandfixpro.android.ui.components.EmptyState
import com.nandfixpro.android.ui.components.InfoBanner
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.OperationBanner
import com.nandfixpro.android.ui.components.ScreenScaffold
import com.nandfixpro.android.ui.components.SecondaryAction
import com.nandfixpro.android.ui.components.StatRow
import com.nandfixpro.android.ui.components.StatusPill
import com.nandfixpro.android.ui.theme.NandAmber
import com.nandfixpro.android.ui.theme.NandBlue
import com.nandfixpro.android.ui.theme.NandTeal
import com.nandfixpro.android.ui.theme.NandTextFaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class BrowserEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

@Composable
fun FileBrowserScreen(viewModel: AppViewModel, navController: NavHostController) {
    val context = LocalContext.current
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    var treeUri by remember { mutableStateOf<Uri?>(null) }
    var stack by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var selected by remember { mutableStateOf<BrowserEntry?>(null) }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            treeUri = uri
            stack = listOfNotNull(DocumentFile.fromTreeUri(context, uri))
        }
    }

    val currentDirectory = stack.lastOrNull()
    val pathNames = remember(stack) { stack.mapNotNull { it.name } }
    val entries = remember(stack) {
        currentDirectory?.listFiles()
            ?.map {
                BrowserEntry(
                    uri = it.uri,
                    name = it.name ?: "(sans nom)",
                    isDirectory = it.isDirectory,
                    size = it.length(),
                    lastModified = it.lastModified(),
                )
            }
            ?.sortedWith(compareByDescending<BrowserEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
    }

    ScreenScaffold(
        title = "File Manager",
        subtitle = "Stockage SAF · carte SD · dossiers NAND",
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
                text = "Le navigateur utilise Storage Access Framework. Android ne fournit pas de pilote UMS : " +
                    "une cible Hekate en USB n'apparaît pas ici. Utilisez la carte SD montée par le téléphone " +
                    "ou le stockage interne.",
                color = NandBlue,
            )

            SecondaryAction(
                text = if (treeUri == null) "CHOISIR UN DOSSIER" else "CHANGER DE DOSSIER",
                icon = Icons.Filled.FolderOpen,
                onClick = { treePicker.launch(null) },
            )

            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            NandCard(title = "Emplacement", icon = Icons.Filled.Folder) {
                Text(
                    if (pathNames.isEmpty()) "Aucun dossier sélectionné"
                    else pathNames.joinToString(" / "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (stack.size > 1) {
                    TextButton(onClick = { stack = stack.dropLast(1) }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null, modifier = Modifier.height(16.dp))
                        Text("  Dossier parent")
                    }
                }
            }

            if (treeUri == null) {
                EmptyState(
                    icon = Icons.Filled.Folder,
                    title = "Sélectionnez un dossier",
                    text = "Choisissez la racine d'une carte SD, un dossier Hekate ou un dossier de dumps NAND.",
                )
            } else if (entries.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Folder,
                    title = "Dossier vide",
                    text = "Aucun fichier accessible dans ce dossier.",
                )
            } else {
                NandCard(title = "${entries.size} élément(s)", icon = Icons.Filled.Search) {
                    entries.forEach { entry ->
                        EntryRow(entry) {
                            if (entry.isDirectory) {
                                val child = stack.lastOrNull()?.findFile(entry.name)
                                if (child != null) stack = stack + child
                            } else {
                                selected = entry
                            }
                        }
                    }
                }
            }
        }
    }

    val entry = selected
    if (entry != null) {
        val fileRefs = entries.filter { !it.isDirectory }.map { SplitNandDetector.FileRef(it.name, it.size) }
        val splitDetection = remember(entry.name, entries) { SplitNandDetector.detect(entry.name, fileRefs) }
        val detected = remember(entry.name, entry.size) { NandFileDetector.detect(entry.name, entry.size, null) }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow("Taille", NandSize.human(entry.size))
                    StatRow(
                        "Modifié le",
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH).format(Date(entry.lastModified)),
                    )
                    StatRow("Type", detected.kind.label)
                    StatusPill(
                        text = if (splitDetection is SplitNandDetector.Result.Success) "Jeu découpé complet" else "Fichier",
                        color = if (splitDetection is SplitNandDetector.Result.Success) NandTeal else NandTextFaint,
                    )
                    Text(
                        "Analyse en lecture seule. Aucune écriture sur le fichier.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        selected = null
                        viewModel.analyzeDocument(entry.uri)
                        navController.navigate(Routes.ANALYZER)
                    }) { Text("Analyser ce fichier") }
                    if (splitDetection is SplitNandDetector.Result.Success) {
                        TextButton(onClick = {
                            selected = null
                            val tree = treeUri
                            if (tree != null) {
                                viewModel.analyzeSplitAt(tree, pathNames.drop(1), entry.name)
                                navController.navigate(Routes.ANALYZER)
                            } else {
                                viewModel.message("Permission de dossier absente")
                            }
                        }) { Text("Analyser le jeu découpé (${splitDetection.set.partNames.size} parties)") }
                    }
                    TextButton(onClick = {
                        viewModel.hashFile(entry.uri)
                        selected = null
                    }) { Text("Calculer le SHA-256") }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Fermer") } },
        )
    }
}

@Composable
private fun EntryRow(entry: BrowserEntry, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = if (entry.isDirectory) NandAmber else NandTeal,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (entry.isDirectory) "Dossier" else NandSize.human(entry.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
