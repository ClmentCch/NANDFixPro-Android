package com.nandfixpro.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nandfixpro.android.nand.IssueSeverity
import com.nandfixpro.android.nand.NandAnalysis
import com.nandfixpro.android.nand.NandCapacity
import com.nandfixpro.android.nand.NandSize
import com.nandfixpro.android.nand.PartitionScan
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.NandDivider
import com.nandfixpro.android.ui.components.StatRow
import com.nandfixpro.android.ui.components.StatusPill
import com.nandfixpro.android.ui.theme.NandAmber
import com.nandfixpro.android.ui.theme.NandBlue
import com.nandfixpro.android.ui.theme.NandRed
import com.nandfixpro.android.ui.theme.NandTeal
import com.nandfixpro.android.ui.theme.NandTextFaint

@Composable
fun AnalysisHeaderCard(analysis: NandAnalysis) {
    NandCard(
        title = "Identification",
        icon = Icons.Filled.Memory,
        accent = if (analysis.healthy) NandTeal else NandAmber,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(analysis.kind.label, style = MaterialTheme.typography.titleMedium)
            StatusPill(
                text = if (analysis.healthy) "Analyse cohérente" else "Anomalies détectées",
                color = if (analysis.healthy) NandTeal else NandAmber,
            )
        }
        StatRow("Source", analysis.sourceLabel)
        StatRow("Taille", NandSize.human(analysis.sourceSize))
        StatRow("Type détecté", analysis.kindDetail)
        StatRow(
            "Classe de capacité",
            if (analysis.capacity == NandCapacity.OTHER) "Non standard" else analysis.capacity.label,
        )
        StatRow("Durée d'analyse", "${analysis.durationMs} ms")
        StatRow("En-tête (SHA-256)", analysis.headSha256 ?: "—", mono = true)
        if (analysis.split != null) {
            StatRow("Jeu découpé", "${analysis.split.partNames.size} parties (${analysis.split.format.label})")
            StatRow("Taille de partie", NandSize.human(analysis.split.chunkSize))
            StatRow("Total assemblé", NandSize.human(analysis.split.totalSize))
            StatRow("BOOT0 / BOOT1", "${if (analysis.split.hasBoot0) "présent" else "absent"} / ${if (analysis.split.hasBoot1) "présent" else "absent"}")
        }
        NandDivider()
        Text(
            "ÉTAT GLOBAL",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("${analysis.errors} erreur(s)", NandRed)
            StatusPill("${analysis.warnings} avertissement(s)", NandAmber)
            StatusPill("${analysis.issues.size} contrôle(s)", NandBlue)
        }
    }
}

@Composable
fun GptCard(analysis: NandAnalysis) {
    NandCard(title = "Table GPT", icon = Icons.Filled.Storage) {
        val gpt = analysis.gpt
        if (gpt == null) {
            Text(
                analysis.gptError ?: "Aucune table GPT exploitable.",
                style = MaterialTheme.typography.bodyMedium,
                color = NandAmber,
            )
            return@NandCard
        }
        StatRow("Disque", gpt.header.diskGuid, mono = true)
        StatRow("Secteurs", "${gpt.totalSectors}", mono = true)
        StatRow("Taille déclarée", NandSize.human(gpt.header.imageSizeBytes))
        StatRow("GPT de secours", "LBA ${gpt.header.backupLba}", mono = true)
        StatRow("Partitions", "${gpt.partitions.size}")
        StatRow("Partitions Switch", "${gpt.knownPartitionCount} / ${com.nandfixpro.android.nand.GptParser.SWITCH_PARTITION_NAMES.size}")
        StatusPill(
            text = if (gpt.looksLikeSwitch) "Disposition Switch reconnue" else "Disposition non Nintendo",
            color = if (gpt.looksLikeSwitch) NandTeal else NandAmber,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                if (gpt.header.headerCrcValid) "CRC en-tête OK" else "CRC en-tête invalide",
                if (gpt.header.headerCrcValid) NandTeal else NandRed,
            )
            StatusPill(
                if (gpt.header.entryArrayCrcValid) "CRC table OK" else "CRC table invalide",
                if (gpt.header.entryArrayCrcValid) NandTeal else NandRed,
            )
        }
        StatRow("Espace non alloué", NandSize.human(gpt.gapBytes))
    }
}

@Composable
fun PartitionsCard(analysis: NandAnalysis, highlight: Set<String> = emptySet()) {
    NandCard(title = "Partitions", icon = Icons.Filled.Storage) {
        if (analysis.partitions.isEmpty()) {
            Text(
                "Aucune partition analysée.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@NandCard
        }
        analysis.partitions.forEach { partition ->
            PartitionRow(partition, emphasised = highlight.any { it.equals(partition.name, true) })
        }
    }
}

@Composable
private fun PartitionRow(partition: PartitionScan, emphasised: Boolean) {
    var expanded by remember(partition.name) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    partition.name.ifBlank { "Partition ${partition.index}" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (emphasised) NandBlue else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${NandSize.human(partition.sizeBytes)} · offset 0x${partition.startBytes.toString(16).uppercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    partition.signature,
                    style = MaterialTheme.typography.labelMedium,
                    color = NandTeal,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                StatRow("Index GPT", "${partition.index}", mono = true)
                StatRow("Début (octets)", "0x${partition.startBytes.toString(16).uppercase()}", mono = true)
                StatRow("Taille (octets)", "${partition.sizeBytes}", mono = true)
                StatRow("Type GUID", partition.typeGuid, mono = true)
                StatRow("Premiers octets", partition.firstBytes, mono = true)
                StatRow("SHA-256 (échantillon)", partition.sampleSha256 ?: "—", mono = true)
            }
        }
        NandDivider()
    }
}

@Composable
fun IssuesCard(analysis: NandAnalysis) {
    NandCard(title = "Contrôles d'intégrité", icon = Icons.Filled.CheckCircle) {
        analysis.issues.forEach { issue ->
            val color = when (issue.severity) {
                IssueSeverity.OK -> NandTeal
                IssueSeverity.INFO -> NandBlue
                IssueSeverity.WARNING -> NandAmber
                IssueSeverity.ERROR -> NandRed
            }
            val icon = when (issue.severity) {
                IssueSeverity.OK -> Icons.Filled.CheckCircle
                IssueSeverity.INFO -> Icons.Filled.Info
                IssueSeverity.WARNING -> Icons.Filled.Warning
                IssueSeverity.ERROR -> Icons.Filled.Error
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(issue.title, style = MaterialTheme.typography.bodyLarge, color = color)
                    Text(
                        issue.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (analysis.notes.isNotEmpty()) {
            NandDivider()
            Text(
                "NOTES",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            analysis.notes.forEach { note ->
                Text(
                    "• $note",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ProdinfoSummaryCard(analysis: NandAnalysis) {
    val summary = analysis.prodinfo ?: return
    val verification = analysis.prodinfoVerification
    NandCard(title = "PRODINFO (CAL0)", icon = Icons.Filled.Memory) {
        StatRow("Modèle", summary.modelName)
        StatRow("Numéro de série", summary.serialNumber.ifBlank { "—" }, mono = true)
        StatRow("Région WiFi", summary.wifiRegion)
        StatRow("MAC WiFi", summary.wifiMac.ifBlank { "—" }, mono = true)
        StatRow("MAC Bluetooth", summary.bluetoothMac.ifBlank { "—" }, mono = true)
        StatRow("Body size", "${summary.declaredBodySize} octets", mono = true)
        StatRow("Compteur de mises à jour", "${summary.updateCount}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                text = if (verification?.valid == true) "Checksums valides" else "Checksums invalides",
                color = if (verification?.valid == true) NandTeal else NandRed,
            )
            StatusPill("${verification?.errors ?: 0} erreur(s)", NandRed)
            StatusPill("${verification?.warnings ?: 0} avert.", NandAmber)
        }
        Text(
            "Le PRODINFO contient l'identité unique de la console. Aucune valeur n'est partagée ni journalisée.",
            style = MaterialTheme.typography.labelMedium,
            color = NandTextFaint,
        )
    }
}

@Composable
fun SeverityLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill("OK", NandTeal)
        StatusPill("Info", NandBlue)
        StatusPill("Attention", NandAmber)
        StatusPill("Erreur", NandRed)
    }
}

fun issueColor(severity: IssueSeverity): Color = when (severity) {
    IssueSeverity.OK -> NandTeal
    IssueSeverity.INFO -> NandBlue
    IssueSeverity.WARNING -> NandAmber
    IssueSeverity.ERROR -> NandRed
}

fun shortHash(hash: String?): String =
    if (hash.isNullOrBlank()) "—" else hash.take(16) + "…" + hash.takeLast(8)

fun partitionFileName(partition: PartitionScan): String =
    "${partition.name} @ 0x${partition.startBytes.toString(16).uppercase()}"

fun ellipsize(text: String, max: Int = 28): String =
    if (text.length <= max) text else text.take(max - 1) + "…"

@Composable
fun MonoValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
