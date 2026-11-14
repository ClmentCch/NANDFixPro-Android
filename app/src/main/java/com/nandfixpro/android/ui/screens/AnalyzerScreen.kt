package com.nandfixpro.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.nandfixpro.android.AnalysisState
import com.nandfixpro.android.AnalysisTarget
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.ui.Routes
import com.nandfixpro.android.ui.components.InfoBanner
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.OperationBanner
import com.nandfixpro.android.ui.components.PrimaryAction
import com.nandfixpro.android.ui.components.ScreenScaffold
import com.nandfixpro.android.ui.components.SecondaryAction
import com.nandfixpro.android.ui.theme.NandAmber
import com.nandfixpro.android.ui.theme.NandTeal

@Composable
fun AnalyzerScreen(viewModel: AppViewModel, navController: NavHostController) {
    val state by viewModel.analyzerState.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.analyzeDocument(it, AnalysisTarget.ANALYZER) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.analyzeSplitFolder(it, AnalysisTarget.ANALYZER) }
    }

    ScreenScaffold(title = "NAND Analyzer", subtitle = "RAWNAND · GPT · PRODINFO · jeux découpés") { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            InfoBanner(
                text = "Sélectionnez un dump local. L'analyse est strictement en lecture seule et ne “répare” rien : " +
                    "elle établit un diagnostic vérifiable.",
                color = NandTeal,
            )

            PrimaryAction(
                text = "CHOISIR UN FICHIER NAND",
                icon = Icons.Filled.Description,
                onClick = { filePicker.launch(arrayOf("*/*")) },
            )
            SecondaryAction(
                text = "Analyser un jeu découpé (dossier)",
                icon = Icons.Filled.Folder,
                onClick = { folderPicker.launch(null) },
            )

            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            when (val current = state) {
                is AnalysisState.Empty -> NandCard(title = "Formats pris en charge", icon = Icons.Filled.Search) {
                    Text("• RAWNAND.bin (image GPP complète)", style = MaterialTheme.typography.bodyMedium)
                    Text("• rawnand.bin.NN / fichiers emuMMC 00, 01, …", style = MaterialTheme.typography.bodyMedium)
                    Text("• BOOT0, BOOT1 (4 Mio attendus)", style = MaterialTheme.typography.bodyMedium)
                    Text("• PRODINFO / PRODINFO.bin (CAL0 déchiffré)", style = MaterialTheme.typography.bodyMedium)
                    Text("• prod.keys (structure uniquement, aucune valeur affichée)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Les gros fichiers sont lus par accès aléatoire, sans copie en mémoire ni déplacement.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is AnalysisState.Failed -> NandCard(title = "Analyse impossible", accent = NandAmber) {
                    Text(current.message, style = MaterialTheme.typography.bodyMedium)
                    SecondaryAction(
                        text = "Effacer",
                        icon = Icons.Filled.Description,
                        onClick = { viewModel.clearAnalysis(AnalysisTarget.ANALYZER) },
                    )
                }

                is AnalysisState.Loaded -> {
                    val analysis = current.analysis
                    AnalysisHeaderCard(analysis)
                    if (analysis.keys != null) {
                        NandCard(title = "prod.keys", icon = Icons.Filled.Description) {
                            val keys = analysis.keys
                            Text("${keys.validKeys} clés valides · ${keys.masterKeys.size} générations de master keys")
                            Text(
                                "Dernière génération détectée : ${keys.newestMasterKey?.toString() ?: "—"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Les valeurs des clés ne sont ni affichées, ni journalisées, ni copiées.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (analysis.gpt != null || analysis.gptError != null) {
                        GptCard(analysis)
                        PartitionsCard(analysis)
                    }
                    if (analysis.prodinfo != null) {
                        ProdinfoSummaryCard(analysis)
                    }
                    if (analysis.boot != null) {
                        val boot = analysis.boot
                        NandCard(title = "Partition BOOT", icon = Icons.Filled.Description) {
                            Text(boot.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Taille ${boot.size} octets (attendu ${boot.expectedSize})",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Premiers octets : ${boot.firstBytes}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "SHA-256 : ${boot.sha256}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IssuesCard(analysis)

                    SecondaryAction(
                        text = "Effacer le résultat",
                        icon = Icons.Filled.Description,
                        onClick = { viewModel.clearAnalysis(AnalysisTarget.ANALYZER) },
                    )
                    Text(
                        "Besoin d'inspecter une partition précise ? Ouvrez l'analyseur de partitions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecondaryAction(
                        text = "SYSTEM · USER · RAW GPP",
                        icon = Icons.Filled.Search,
                        onClick = { navController.navigate(Routes.PARTITIONS) },
                    )
                }
            }
        }
    }
}
