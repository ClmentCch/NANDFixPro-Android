package com.nandfixpro.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.nandfixpro.android.BuildConfig
import com.nandfixpro.android.ui.components.InfoBanner
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.ScreenScaffold
import com.nandfixpro.android.ui.components.StatRow
import com.nandfixpro.android.ui.theme.NandAmber
import com.nandfixpro.android.ui.theme.NandTeal

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    ScreenScaffold(
        title = "À propos",
        subtitle = "NAND Fix Pro Android",
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
            NandCard(title = "Application", icon = Icons.Filled.Info) {
                StatRow("Nom", "NAND Fix Pro Android")
                StatRow("Version", BuildConfig.VERSION_NAME)
                StatRow("VersionCode", "${BuildConfig.VERSION_CODE}")
                StatRow("Plateforme", "Android 13+ (API 33+), ARM64")
                StatRow("Licence", "MIT")
                StatRow("Mode", "Analyse en lecture seule")
                Text(
                    "Portage Android du projet NANDFixPro pour Windows, avec une couche USB Host " +
                        "Mass Storage et une interface Hekate dédiées au téléphone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            NandCard(title = "Projet original", icon = Icons.Filled.Gavel) {
                Text(
                    "NANDFixPro — Copyright (c) 2025 Hendy Kartono (sthetix). Distribué sous licence MIT.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { uriHandler.openUri("https://github.com/sthetix/NANDFixPro") }) {
                    Text("github.com/sthetix/NANDFixPro")
                }
                TextButton(onClick = { uriHandler.openUri("https://github.com/ClmentCch/NANDFixPro-Android") }) {
                    Text("github.com/ClmentCch/NANDFixPro-Android")
                }
                Text(
                    "Le texte complet de la licence MIT est fourni dans le fichier LICENSE du dépôt.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            NandCard(title = "Crédits", icon = Icons.Filled.Info) {
                listOf(
                    "sthetix / Hendy Kartono — NANDFixPro original",
                    "CTCaer — hekate (UMS, eMMC RAW GPP)",
                    "suchmememanymuch — EmmecHaccGen",
                    "eliboa — NxNandManager",
                    "PassMark Software — OSFMount",
                    "Igor Pavlov — 7-Zip",
                    "Atmosphère — vérification PRODINFO / CAL0",
                ).forEach { credit ->
                    Text("• $credit", style = MaterialTheme.typography.bodyMedium)
                }
            }

            NandCard(title = "Fonctionnalités adaptées", icon = Icons.Filled.Info, accent = NandTeal) {
                listOf(
                    "Détection USB Host et énumération des interfaces Mass Storage",
                    "Transport SCSI Bulk-Only en lecture seule (INQUIRY, READ CAPACITY, READ 10/16)",
                    "Identification Hekate fondée sur la GPT et les partitions réellement lues",
                    "Analyse RAWNAND, jeux découpés Hekate et emuMMC",
                    "Analyse PRODINFO CAL0 avec CRC16, SHA-256 et export vérifié",
                    "Contrôle BOOT0/BOOT1, partitions SYSTEM/USER, inventaire firmware",
                    "Sauvegardes locales hachées, vérification et journal exportable",
                ).forEach { feature ->
                    Text("• $feature", style = MaterialTheme.typography.bodyMedium)
                }
            }

            NandCard(title = "Limites assumées", icon = Icons.Filled.Warning, accent = NandAmber) {
                InfoBanner(
                    text = "Aucune réparation NAND n'est simulée. Les outils Windows d'origine ne sont pas portables.",
                    color = NandAmber,
                    icon = Icons.Filled.Warning,
                )
                listOf(
                    "Pas d'écriture brute sur une NAND ni sur une carte SD.",
                    "Pas de flash de BOOT0/BOOT1 depuis Android : utiliser Hekate.",
                    "Pas de montage FAT/NTFS des partitions : seules les signatures sont identifiées.",
                    "Pas de génération de boot files : elle exige EmmecHaccGen et les clés.",
                    "La navigation FAT de la carte SD exposée en UMS n'est pas implémentée.",
                    "La version de firmware n'est pas déduite sans analyse NCA.",
                ).forEach { limit ->
                    Text("• $limit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            NandCard(title = "Avertissement", icon = Icons.Filled.Lock, accent = NandTeal) {
                Text(
                    "La mémoire NAND contient des données uniques. Une interruption d'écriture peut rendre " +
                        "une console définitivement inutilisable. Cette application ne réalise aucune écriture " +
                        "sur un périphérique physique et ne peut donc pas endommager la console par elle-même.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Aucune affiliation avec Nintendo. Utilisez uniquement vos propres sauvegardes et vos " +
                        "propres clés, conformément aux lois applicables.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
