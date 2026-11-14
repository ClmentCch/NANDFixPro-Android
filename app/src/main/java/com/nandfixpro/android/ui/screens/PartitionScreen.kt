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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nandfixpro.android.AnalysisState
import com.nandfixpro.android.AnalysisTarget
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.nand.NandSize
import com.nandfixpro.android.ui.components.InfoBanner
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.OperationBanner
import com.nandfixpro.android.ui.components.PrimaryAction
import com.nandfixpro.android.ui.components.ScreenScaffold
import com.nandfixpro.android.ui.components.StatRow
import com.nandfixpro.android.ui.components.StatusPill
import com.nandfixpro.android.ui.theme.NandAmber
import com.nandfixpro.android.ui.theme.NandRed
import com.nandfixpro.android.ui.theme.NandTeal

@Composable
fun PartitionScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val state by viewModel.partitionState.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.analyzeDocument(it, AnalysisTarget.PARTITIONS) }
    }

    ScreenScaffold(
        title = "Partitions SYSTEM / USER",
        subtitle = "Analyse RAW GPP en lecture seule",
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
                text = "Cette page inspecte les partitions d'une image GPP. Elle ne monte pas les systèmes de " +
                    "fichiers et ne modifie aucune partition : le formatage/restauration exige des outils PC " +
                    "absents d'Android.",
                color = NandAmber,
            )
            PrimaryAction(
                text = "CHOISIR UNE IMAGE GPP",
                icon = Icons.Filled.Description,
                onClick = { picker.launch(arrayOf("*/*")) },
            )
            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            when (val current = state) {
                is AnalysisState.Empty -> NandCard(title = "Que vérifie ce module ?", icon = Icons.Filled.Storage) {
                    Text("• présence des partitions SYSTEM, USER, SAFE, PRODINFO…", style = MaterialTheme.typography.bodyMedium)
                    Text("• offset, taille et signature du premier secteur", style = MaterialTheme.typography.bodyMedium)
                    Text("• alignement et cohérence avec la table GPT", style = MaterialTheme.typography.bodyMedium)
                    Text("• empreinte SHA-256 d'un échantillon par partition", style = MaterialTheme.typography.bodyMedium)
                }

                is AnalysisState.Failed -> NandCard(title = "Analyse impossible", accent = NandRed) {
                    Text(current.message)
                }

                is AnalysisState.Loaded -> {
                    val analysis = current.analysis
                    val gpt = analysis.gpt
                    if (gpt == null) {
                        NandCard(title = "RAW GPP non lisible", accent = NandAmber) {
                            Text(analysis.gptError ?: "Aucune GPT détectée.")
                        }
                    } else {
                        NandCard(title = "RAW GPP", icon = Icons.Filled.Storage) {
                            StatRow("Image", analysis.sourceLabel)
                            StatRow("Taille", NandSize.human(analysis.sourceSize))
                            StatRow("Classe", analysis.capacity.label)
                            StatRow("Partitions", "${gpt.partitions.size}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusPill(
                                    if (gpt.looksLikeSwitch) "Switch GPP" else "GPT non Nintendo",
                                    if (gpt.looksLikeSwitch) NandTeal else NandAmber,
                                )
                                StatusPill("${analysis.errors} erreur(s)", NandRed)
                            }
                        }
                        val focused = analysis.partitions.filter {
                            it.name.equals("SYSTEM", true) || it.name.equals("USER", true)
                        }
                        if (focused.isNotEmpty()) {
                            NandCard(title = "Partitions ciblées", icon = Icons.Filled.Storage) {
                                focused.forEach { partition ->
                                    Text(partition.name, style = MaterialTheme.typography.titleMedium)
                                    StatRow("Offset", "0x${partition.startBytes.toString(16).uppercase()}", mono = true)
                                    StatRow("Taille", NandSize.human(partition.sizeBytes))
                                    StatRow("Signature", partition.signature, valueColor = NandTeal)
                                    StatRow("SHA-256 (4 Kio)", partition.sampleSha256 ?: "—", mono = true)
                                }
                            }
                        } else {
                            NandCard(title = "SYSTEM / USER absentes", accent = NandAmber) {
                                Text(
                                    "L'image ne contient pas les partitions SYSTEM et USER attendues. " +
                                        "Il peut s'agir d'une sauvegarde partielle.",
                                )
                            }
                        }
                        PartitionsCard(analysis, highlight = setOf("SYSTEM", "USER", "PRODINFO"))
                    }
                    IssuesCard(analysis)
                    NandCard(title = "Réparation", icon = Icons.Filled.Storage, accent = NandAmber) {
                        Text(
                            "Aucune réparation n'est proposée sur Android. Le projet original s'appuie sur " +
                                "EmmcHaccGen (.NET x64), NxNandManager (Windows + pilote Dokan) et OSFMount " +
                                "(pilote Windows), qui ne peuvent pas s'exécuter sur un téléphone ARM64.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Alternative : validez ici l'intégrité de la sauvegarde, puis effectuez la " +
                                "réparation sur PC avec NANDFixPro et flashez via Hekate.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
