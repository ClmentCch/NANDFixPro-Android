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
import androidx.compose.material.icons.filled.Memory
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
fun BootScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val state by viewModel.bootState.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.analyzeDocument(it, AnalysisTarget.BOOT) }
    }

    ScreenScaffold(
        title = "BOOT0 / BOOT1 Manager",
        subtitle = "Contrôle des partitions de boot",
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
                text = "Les partitions BOOT0 et BOOT1 font 4 Mio chacune. Cette page vérifie taille, contenu et " +
                    "empreinte. Le flash nécessite Hekate (Tools → Restore eMMC) : aucune écriture n'est faite ici.",
                color = NandTeal,
            )
            PrimaryAction(
                text = "CHOISIR BOOT0 OU BOOT1",
                icon = Icons.Filled.Description,
                onClick = { picker.launch(arrayOf("*/*")) },
            )
            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            when (val current = state) {
                is AnalysisState.Empty -> NandCard(title = "Pourquoi vérifier BOOT0/BOOT1 ?", icon = Icons.Filled.Memory) {
                    Text(
                        "Un boot file incorrect ou tronqué provoque un échec de démarrage. La vérification " +
                            "confirme la taille attendue (4 Mio), l'absence de zone vide et fournit un SHA-256 " +
                            "comparatif avant tout flash manuel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is AnalysisState.Failed -> NandCard(title = "Lecture impossible", accent = NandRed) {
                    Text(current.message)
                }

                is AnalysisState.Loaded -> {
                    val analysis = current.analysis
                    val boot = analysis.boot
                    NandCard(title = "Résultat BOOT", icon = Icons.Filled.Memory) {
                        if (boot == null) {
                            Text(
                                "Le fichier sélectionné n'est pas identifié comme BOOT0/BOOT1.",
                                color = NandAmber,
                            )
                        } else {
                            StatRow("Fichier", boot.name)
                            StatRow("Taille", "${boot.size} octets (${NandSize.human(boot.size)})")
                            StatRow("Taille attendue", "${boot.expectedSize} octets")
                            StatRow("Zone vide", if (boot.blank) "Oui" else "Non")
                            StatRow("Premiers octets", boot.firstBytes, mono = true)
                            StatRow("SHA-256", boot.sha256, mono = true)
                            StatusPill(
                                text = if (boot.size == boot.expectedSize && !boot.blank) "Conforme" else "À vérifier",
                                color = if (boot.size == boot.expectedSize && !boot.blank) NandTeal else NandAmber,
                            )
                        }
                    }
                    IssuesCard(analysis)
                    NandCard(title = "Flash des boot files", accent = NandAmber) {
                        Text(
                            "1. Copiez BOOT0 et BOOT1 dans backup/<emmc-id>/restore/ sur la carte SD.\n" +
                                "2. Sur la console : Hekate → Tools → Restore eMMC → eMMC BOOT0 & BOOT1.\n" +
                                "3. Ne coupez jamais l'alimentation pendant la restauration.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
