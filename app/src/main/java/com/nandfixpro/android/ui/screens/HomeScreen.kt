package com.nandfixpro.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.nandfixpro.android.AppViewModel
import com.nandfixpro.android.R
import com.nandfixpro.android.UsbUiState
import com.nandfixpro.android.ui.Routes
import com.nandfixpro.android.ui.components.EmptyState
import com.nandfixpro.android.ui.components.InfoBanner
import com.nandfixpro.android.ui.components.LogConsole
import com.nandfixpro.android.ui.components.NandCard
import com.nandfixpro.android.ui.components.OperationBanner
import com.nandfixpro.android.ui.components.PrimaryAction
import com.nandfixpro.android.ui.components.ScreenScaffold
import com.nandfixpro.android.ui.components.SecondaryAction
import com.nandfixpro.android.ui.components.StatRow
import com.nandfixpro.android.ui.components.StatusPill
import com.nandfixpro.android.ui.components.formatTimestamp
import com.nandfixpro.android.ui.theme.NandAmber
import com.nandfixpro.android.ui.theme.NandBlue
import com.nandfixpro.android.ui.theme.NandTeal
import com.nandfixpro.android.ui.theme.NandTextFaint

@Composable
fun HomeScreen(viewModel: AppViewModel, navController: NavHostController) {
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val usbState by viewModel.usbState.collectAsStateWithLifecycle()
    val lastActivity by viewModel.lastUsbActivity.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = "NAND Fix Pro",
        subtitle = "Android · NAND & Hekate toolkit",
        actions = {
            IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                Icon(Icons.Filled.Settings, contentDescription = "Paramètres")
            }
            IconButton(onClick = { navController.navigate(Routes.ABOUT) }) {
                Icon(Icons.Filled.Info, contentDescription = "À propos")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BrandHeader(version = viewModel.deviceInfo().appVersion)

            InfoBanner(
                text = "Lecture seule sur tout périphérique physique. Cette version n'écrit jamais sur une NAND " +
                    "ni sur une carte SD : aucune réparation simulée.",
                color = NandTeal,
                icon = Icons.Filled.Shield,
            )

            NandCard(title = "État du matériel", icon = Icons.Filled.Usb) {
                val connected = usbState is UsbUiState.Connected
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (connected) "Périphérique USB analysé" else "Aucune analyse USB active",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    StatusPill(
                        text = if (connected) "Connecté" else "Inactif",
                        color = if (connected) NandTeal else NandTextFaint,
                    )
                }
                StatRow("Périphériques détectés", "${devices.size}")
                StatRow(
                    "Dernière activité USB",
                    formatTimestamp(lastActivity),
                )
                StatRow("Notifications", if (settings.notificationsEnabled) "Activées" else "Désactivées")
            }

            PrimaryAction(
                text = "DÉTECTER UN PÉRIPHÉRIQUE USB",
                icon = Icons.Filled.Usb,
                onClick = {
                    navController.navigate(Routes.USB)
                    viewModel.detectUsb(autoProbe = false)
                },
            )
            SecondaryAction(
                text = "DÉTECTER HEKATE",
                icon = Icons.Filled.Bolt,
                onClick = {
                    navController.navigate(Routes.USB)
                    viewModel.detectUsb(autoProbe = true)
                },
            )

            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation,
                onDismiss = viewModel::clearOperationResult,
            )

            Text(
                "MODULES",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            ModuleRow(
                icon = Icons.Filled.Search,
                color = NandBlue,
                title = "NAND Analyzer",
                subtitle = "RAWNAND.bin · GPT · partitions · jeux découpés",
                onClick = { navController.navigate(Routes.ANALYZER) },
            )
            ModuleRow(
                icon = Icons.Filled.Save,
                color = NandTeal,
                title = "Backup Manager",
                subtitle = "Copies locales hachées, vérification et inventaire",
                onClick = { navController.navigate(Routes.BACKUPS) },
            )
            ModuleRow(
                icon = Icons.Filled.Folder,
                color = NandAmber,
                title = "File Manager",
                subtitle = "Navigateur SAF · cartes SD · dossiers NAND",
                onClick = { navController.navigate(Routes.FILES) },
            )
            ModuleRow(
                icon = Icons.Filled.Key,
                color = NandBlue,
                title = "PRODINFO Analyzer",
                subtitle = "CAL0 · CRC16 · SHA-256 · export vérifié",
                onClick = { navController.navigate(Routes.PRODINFO) },
            )
            ModuleRow(
                icon = Icons.Filled.Memory,
                color = NandTeal,
                title = "BOOT0 / BOOT1",
                subtitle = "Contrôle de taille, contenu et empreinte",
                onClick = { navController.navigate(Routes.BOOT) },
            )
            ModuleRow(
                icon = Icons.Filled.Storage,
                color = NandBlue,
                title = "SYSTEM · USER · RAW GPP",
                subtitle = "Analyse de partitions et signatures",
                onClick = { navController.navigate(Routes.PARTITIONS) },
            )
            ModuleRow(
                icon = Icons.Filled.SystemUpdate,
                color = NandAmber,
                title = "Informations firmware",
                subtitle = "Inventaire NCA et structure de dossier",
                onClick = { navController.navigate(Routes.FIRMWARE) },
            )
            ModuleRow(
                icon = Icons.Filled.BugReport,
                color = NandTeal,
                title = "Diagnostics",
                subtitle = "Auto-test des moteurs · appareil · journaux",
                onClick = { navController.navigate(Routes.DIAGNOSTICS) },
            )
            ModuleRow(
                icon = Icons.Filled.Description,
                color = NandTextFaint,
                title = "Journal & diagnostics",
                subtitle = "Console complète, filtres et export",
                onClick = { navController.navigate(Routes.LOGS) },
            )
            ModuleRow(
                icon = Icons.Filled.Insights,
                color = NandTextFaint,
                title = "À propos & crédits",
                subtitle = "Licence MIT · projet original · limites",
                onClick = { navController.navigate(Routes.ABOUT) },
            )

            NandCard(title = "Activité récente", icon = Icons.Filled.BugReport) {
                if (logEntries.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Lock,
                        title = "Journal vide",
                        text = "Les opérations USB et d'analyse apparaîtront ici.",
                    )
                } else {
                    LogConsole(entries = logEntries, modifier = Modifier.height(130.dp), maxEntries = 60)
                }
            }

            Text(
                "Dérivé de NANDFixPro (sthetix) · Licence MIT · Aucune affiliation avec Nintendo",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun BrandHeader(version: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "Logo NAND Fix Pro",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(52.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "NAND FIX PRO",
                style = MaterialTheme.typography.headlineMedium,
                letterSpacing = 0.5.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(NandTeal),
                )
                Text(
                    "ANDROID · $version",
                    style = MaterialTheme.typography.labelMedium,
                    color = NandTeal,
                    letterSpacing = 1.sp,
                )
            }
            Text(
                "Analyse NAND, USB OTG et compatibilité Hekate",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModuleRow(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = color.copy(alpha = 0.13f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(0.dp))
}
