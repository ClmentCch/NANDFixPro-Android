package com.nandfixpro.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Usb
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.UsbUiState
import com.nandfixpro.android.ui.components.InfoBanner
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.NandDivider
import com.nandfixpro.android.ui.components.OperationBanner
import com.nandfixpro.android.ui.components.PrimaryAction
import com.nandfixpro.android.ui.components.ScreenScaffold
import com.nandfixpro.android.ui.components.SecondaryAction
import com.nandfixpro.android.ui.components.StatRow
import com.nandfixpro.android.ui.components.StatusPill
import com.nandfixpro.android.ui.components.formatTimestamp
import com.nandfixpro.android.ui.theme.NandAmber
import com.nandfixpro.android.ui.theme.NandBlue
import com.nandfixpro.android.ui.theme.NandRed
import com.nandfixpro.android.ui.theme.NandTeal
import com.nandfixpro.android.ui.theme.NandTextFaint
import com.nandfixpro.android.usb.CompatibilityVerdict
import com.nandfixpro.android.usb.UsbDeviceSnapshot
import com.nandfixpro.android.usb.UsbEndpointInfo
import com.nandfixpro.android.usb.UsbInterfaceInfo

@Composable
fun UsbScreen(viewModel: AppViewModel) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val usbState by viewModel.usbState.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val assessment by viewModel.usbAssessment.collectAsStateWithLifecycle()
    val lastActivity by viewModel.lastUsbActivity.collectAsStateWithLifecycle()
    val info = remember { viewModel.deviceInfo() }

    ScreenScaffold(title = "Connexion USB", subtitle = "USB Host · Mass Storage · Hekate") { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!info.usbHostSupported) {
                InfoBanner(
                    text = "Cet appareil n'expose pas USB Host : la connexion OTG est impossible. " +
                        "Utilisez un téléphone compatible OTG.",
                    color = NandRed,
                    icon = Icons.Filled.Warning,
                )
            }

            PrimaryAction(
                text = "DÉTECTER UN PÉRIPHÉRIQUE USB",
                icon = Icons.Filled.Usb,
                onClick = { viewModel.detectUsb(autoProbe = false) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryAction(
                    text = "Détecter Hekate",
                    icon = Icons.Filled.PlayArrow,
                    onClick = { viewModel.detectUsb(autoProbe = true) },
                    modifier = Modifier.weight(1f),
                )
                SecondaryAction(
                    text = "Actualiser",
                    icon = Icons.Filled.Refresh,
                    onClick = { viewModel.detectUsb(autoProbe = false) },
                    modifier = Modifier.weight(1f),
                )
            }

            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            ConnectionStateCard(
                state = usbState,
                lastActivity = lastActivity,
                onDisconnect = viewModel::disconnectUsb,
            )

            NandCard(title = "Périphériques USB détectés", icon = Icons.Filled.Usb) {
                if (devices.isEmpty()) {
                    Text(
                        "Aucun périphérique énuméré.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InfoBanner(
                        text = "1. Branchez un câble USB-C OTG compatible données.\n" +
                            "2. Sur la Switch : Hekate → Tools → USB Tools.\n" +
                            "3. Choisissez eMMC RAW GPP (ou BOOT0 / BOOT1).\n" +
                            "4. Revenez ici et lancez la détection.",
                        color = NandBlue,
                    )
                } else {
                    devices.forEach { device ->
                        DeviceCard(device = device, onConnect = { viewModel.connectDevice(device.deviceId) })
                    }
                }
            }

            HekateSection(
                assessment = assessment,
                state = usbState,
                lastActivity = lastActivity,
                onReadTest = viewModel::usbReadTest,
                onSaveBoot = viewModel::saveUsbBootPartition,
                onDisconnect = viewModel::disconnectUsb,
            )

            InfoBanner(
                text = "Android ne monte pas les volumes UMS : l'application dialogue directement en SCSI " +
                    "Bulk-Only. Aucune commande WRITE n'est implémentée, le support reste en lecture seule.",
                color = NandTeal,
            )
        }
    }
}

@Composable
private fun ConnectionStateCard(
    state: UsbUiState,
    lastActivity: Long?,
    onDisconnect: () -> Unit,
) {
    val (label, color) = when (state) {
        is UsbUiState.Idle -> "Déconnecté" to NandTextFaint
        is UsbUiState.RequestingPermission -> "Demande de permission…" to NandAmber
        is UsbUiState.Probing -> "Analyse du périphérique…" to NandBlue
        is UsbUiState.Connected -> "Connecté" to NandTeal
        is UsbUiState.Failed -> "Échec" to NandRed
    }
    NandCard(title = "État de la connexion", icon = Icons.Filled.Usb) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            StatusPill(label, color)
        }
        StatRow("Dernière activité USB", formatTimestamp(lastActivity))
        when (state) {
            is UsbUiState.RequestingPermission ->
                Text(
                    "Acceptez la demande d'accès USB affichée par Android pour ${state.deviceName}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            is UsbUiState.Probing ->
                Text(
                    "Initialisation SCSI de ${state.deviceName} : TEST UNIT READY, INQUIRY, READ CAPACITY, GPT.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            is UsbUiState.Connected -> {
                val result = state.result
                StatRow("Périphérique", result.device.displayName)
                StatRow("Identifiants", "${result.device.vendorHex}:${result.device.productHex}", mono = true)
                StatRow(
                    "Compatibilité",
                    result.assessment.verdict.short,
                    valueColor = verdictColor(result.assessment.verdict),
                )
                if (result.assessment.verdict == CompatibilityVerdict.INCOMPATIBLE) {
                    Text(
                        "Le périphérique répond en USB mais aucune structure NAND n'a pu être lue. " +
                            "Vérifiez le mode choisi dans Hekate (eMMC RAW GPP).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NandAmber,
                    )
                }
                TextButton(onClick = onDisconnect) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.height(16.dp))
                    Text("  Fermer la connexion")
                }
            }
            is UsbUiState.Failed ->
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NandRed,
                )
            else -> Unit
        }
    }
}

@Composable
private fun DeviceCard(device: UsbDeviceSnapshot, onConnect: () -> Unit) {
    var expanded by remember(device.deviceId) { mutableStateOf(false) }
    NandCard(
        title = if (device.isMassStorage) "Périphérique Mass Storage" else "Périphérique USB",
        icon = Icons.Filled.Usb,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${device.vendorHex}:${device.productHex} · ${device.deviceName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = if (device.isMassStorage) "UMS" else "USB",
                color = if (device.isMassStorage) NandTeal else NandTextFaint,
            )
        }

        StatRow("Fabricant", device.manufacturer ?: "Non exposé")
        StatRow("Produit", device.productName ?: "Non exposé")
        StatRow("Numéro de série", device.serialNumber ?: "Non exposé (ou permission requise)")
        StatRow("Version USB", device.deviceVersion)
        StatRow("Permission", if (device.hasPermission) "Accordée" else "À demander")
        if (device.isNintendoVendor) {
            StatusPill("VID Nintendo", NandTeal)
        }

        TextButton(onClick = { expanded = !expanded }) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
            Text(if (expanded) "  Masquer les interfaces" else "  Voir les interfaces USB")
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                device.interfaces.forEach { usbInterface ->
                    InterfaceBlock(usbInterface)
                }
            }
        }

        NandDivider()
        PrimaryAction(
            text = "AUTORISER ET ANALYSER",
            icon = Icons.Filled.PlayArrow,
            onClick = onConnect,
        )
        if (!device.isMassStorage) {
            Text(
                "Aucune interface de stockage de masse : ce périphérique ne peut pas contenir une NAND Hekate.",
                style = MaterialTheme.typography.bodyMedium,
                color = NandAmber,
            )
        }
    }
}

@Composable
private fun InterfaceBlock(usbInterface: UsbInterfaceInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Interface ${usbInterface.id} · ${usbInterface.description}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (usbInterface.isBulkOnlyScsi) StatusPill("BOT", NandTeal)
        }
        Text(
            "Classe 0x%02X · sous-classe 0x%02X · protocole 0x%02X".format(
                usbInterface.interfaceClass,
                usbInterface.subClass,
                usbInterface.protocol,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        usbInterface.endpoints.forEach { endpoint -> EndpointLine(endpoint) }
    }
}

@Composable
private fun EndpointLine(endpoint: UsbEndpointInfo) {
    Text(
        "  • ${endpoint.direction} ${endpoint.type} · adresse 0x%02X · paquet %d o".format(
            endpoint.address,
            endpoint.maxPacketSize,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HekateSection(
    assessment: com.nandfixpro.android.usb.HekateAssessment?,
    state: UsbUiState,
    lastActivity: Long?,
    onReadTest: () -> Unit,
    onSaveBoot: () -> Unit,
    onDisconnect: () -> Unit,
) {
    NandCard(
        title = "Hekate USB connection",
        icon = Icons.Filled.CheckCircle,
        accent = if (assessment == null) NandTextFaint else verdictColor(assessment.verdict),
    ) {
        if (assessment == null) {
            Text(
                "Aucune cible Hekate analysée. Connectez la Switch, activez USB Tools puis lancez la détection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(assessment.headline, style = MaterialTheme.typography.titleMedium)
                StatusPill(assessment.verdict.short, verdictColor(assessment.verdict))
            }
            StatRow("État de connexion", if (state is UsbUiState.Connected) "Connecté" else "Inactif")
            StatRow("Périphérique détecté", assessment.inquiry?.displayName ?: "UMS sans INQUIRY")
            StatRow("Type de périphérique", assessment.verdict.label)
            StatRow("Capacité détectée", assessment.capacityLabel)
            StatRow("Mode de connexion", assessment.mode)
            StatRow(
                "Compatibilité détectée",
                assessment.verdict.short,
                valueColor = verdictColor(assessment.verdict),
            )
            StatRow("Dernière activité USB", formatTimestamp(lastActivity))
            StatRow("Lecture seule", "Oui — aucune écriture possible")

            NandDivider()
            Text(
                "ÉVIDENCE DE DÉTECTION",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            assessment.reasons.forEach { reason ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val gpt = assessment.gpt
            if (gpt != null) {
                NandDivider()
                Text(
                    "Partitions GPT detectees",
                    style = MaterialTheme.typography.titleMedium,
                )
                gpt.partitions.take(12).forEach { partition ->
                    Text(
                        "• ${partition.name} — ${com.nandfixpro.android.nand.NandSize.human(partition.sizeBytes)} " +
                            "@ 0x${partition.offsetBytes.toString(16).uppercase()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            NandDivider()
            SecondaryAction(
                text = "Tester la lecture (4 Mio, lecture seule)",
                icon = Icons.Filled.PlayArrow,
                onClick = onReadTest,
            )
            if (assessment.verdict == CompatibilityVerdict.HEKATE_BOOT) {
                SecondaryAction(
                    text = "Sauvegarder cette partition BOOT",
                    icon = Icons.Filled.Save,
                    onClick = onSaveBoot,
                )
            }
            TextButton(onClick = onDisconnect) { Text("Fermer la connexion USB") }
        }
    }
}

private fun verdictColor(verdict: CompatibilityVerdict): Color = when (verdict) {
    CompatibilityVerdict.HEKATE_GPP -> NandTeal
    CompatibilityVerdict.HEKATE_BOOT -> NandBlue
    CompatibilityVerdict.GENERIC_UMS -> NandAmber
    CompatibilityVerdict.INCOMPATIBLE -> NandRed
    CompatibilityVerdict.UNKNOWN -> NandTextFaint
}
