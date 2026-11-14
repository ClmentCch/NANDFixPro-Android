package com.nandfixpro.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.nandfixpro.android.nand.NandSize
import com.nandfixpro.android.ui.components.ConfirmDialog
import com.nandfixpro.android.ui.components.InfoBanner
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.NandDivider
import com.nandfixpro.android.ui.components.ScreenScaffold
import com.nandfixpro.android.ui.components.SecondaryAction
import com.nandfixpro.android.ui.components.StatRow
import com.nandfixpro.android.ui.theme.NandAmber
import com.nandfixpro.android.ui.theme.NandTeal

@Composable
fun SettingsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showReset by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "Paramètres",
        subtitle = "Comportement USB, journal et sécurité",
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
            NandCard(title = "Général", icon = Icons.Filled.Settings) {
                SettingSwitch(
                    title = "Notifications d'état",
                    subtitle = "Connexion USB, fin d'analyse et résultat des sauvegardes.",
                    checked = settings.notificationsEnabled,
                    onCheckedChange = viewModel::setNotifications,
                )
                NandDivider()
                SettingSwitch(
                    title = "Confirmer les actions sensibles",
                    subtitle = "Demande une confirmation avant l'export d'un PRODINFO modifié.",
                    checked = settings.requireConfirmation,
                    onCheckedChange = viewModel::setRequireConfirmation,
                )
                NandDivider()
                SettingSwitch(
                    title = "Calcul automatique des empreintes",
                    subtitle = "Affiche les SHA-256 d'échantillons pendant l'analyse locale.",
                    checked = settings.autoHash,
                    onCheckedChange = viewModel::setAutoHash,
                )
                NandDivider()
                SettingSwitch(
                    title = "Journal détaillé",
                    subtitle = "Ajoute les messages de diagnostic supplémentaires.",
                    checked = settings.verboseLogs,
                    onCheckedChange = viewModel::setVerboseLogs,
                )
                NandDivider()
                SettingSwitch(
                    title = "Garder l'écran allumé",
                    subtitle = "Utile pendant une lecture USB ou une sauvegarde longue.",
                    checked = settings.keepScreenOn,
                    onCheckedChange = viewModel::setKeepScreenOn,
                )
            }

            NandCard(title = "USB", icon = Icons.Filled.Usb) {
                StatRow("Délai de réponse SCSI", settings.usbTimeoutLabel)
                Slider(
                    value = settings.usbTimeoutMs.toFloat(),
                    onValueChange = { value -> viewModel.setUsbTimeout(((value / 1000).toInt() * 1000).coerceIn(1000, 20000)) },
                    valueRange = 1000f..20000f,
                    steps = 18,
                )
                Text(
                    "Un délai plus long aide les câbles OTG instables. Un délai trop court provoque des " +
                        "erreurs de lecture sur les gros blocs.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            NandCard(title = "Politique de sécurité", icon = Icons.Filled.Lock, accent = NandTeal) {
                InfoBanner(
                    text = "Le mode lecture seule est verrouillé dans cette version.",
                    color = NandTeal,
                    icon = Icons.Filled.Lock,
                )
                listOf(
                    "Aucune commande WRITE, FORMAT ou UNMAP SCSI n'est implémentée.",
                    "Aucune écriture dans une partition physique ou une image NAND.",
                    "Les sauvegardes vont uniquement dans le stockage privé de l'application.",
                    "Les clés et le PRODINFO ne sont jamais journalisés ni transmis.",
                ).forEach { rule ->
                    Text("• $rule", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            NandCard(title = "Stockage", icon = Icons.Filled.Storage) {
                StatRow("Espace applicatif libre", NandSize.human(viewModel.storageFreeSpace()))
                StatRow("Dossier des sauvegardes", "Android/data/com.nandfixpro.android/files/backups", mono = true)
            }

            NandCard(title = "Réinitialisation", icon = Icons.Filled.Refresh, accent = NandAmber) {
                SecondaryAction(
                    text = "RÉINITIALISER LES PARAMÈTRES",
                    icon = Icons.Filled.Refresh,
                    onClick = { showReset = true },
                )
                Text(
                    "La réinitialisation ne supprime ni les sauvegardes ni les journaux exportés.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "L'application est exclusivement sombre, conformément au design du projet NANDFixPro.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }

    if (showReset) {
        ConfirmDialog(
            title = "Réinitialiser les paramètres ?",
            message = "Les options USB, journal et confirmations reviendront à leurs valeurs par défaut.",
            confirmLabel = "Réinitialiser",
            onConfirm = viewModel::resetSettings,
            onDismiss = { showReset = false },
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
