package com.nandfixpro.android

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nandfixpro.android.data.AppSettings
import com.nandfixpro.android.data.StorageRepository.BackupArtifact
import com.nandfixpro.android.data.StorageRepository.VolumeInfo
import com.nandfixpro.android.data.LogRepository
import com.nandfixpro.android.data.NotificationHelper
import com.nandfixpro.android.data.SettingsRepository
import com.nandfixpro.android.data.StorageRepository
import com.nandfixpro.android.diagnostics.SelfTest
import com.nandfixpro.android.diagnostics.SelfTestReport
import com.nandfixpro.android.nand.NandAnalyzer
import com.nandfixpro.android.nand.NandCapacity
import com.nandfixpro.android.nand.NandFileDetector
import com.nandfixpro.android.nand.NandFileKind
import com.nandfixpro.android.nand.NandSize
import com.nandfixpro.android.nand.ProdKeysParser
import com.nandfixpro.android.nand.ProdKeysSummary
import com.nandfixpro.android.nand.ProdinfoEngine
import com.nandfixpro.android.usb.CompatibilityVerdict
import com.nandfixpro.android.usb.HekateAssessment
import com.nandfixpro.android.usb.UsbOpenResult
import com.nandfixpro.android.usb.UsbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException

data class OperationResult(val success: Boolean, val title: String, val detail: String)

data class OperationState(
    val running: Boolean = false,
    val label: String = "",
    val progress: Float = 0f,
    val result: OperationResult? = null,
)

sealed interface UsbUiState {
    data object Idle : UsbUiState
    data class RequestingPermission(val deviceName: String) : UsbUiState
    data class Probing(val deviceName: String) : UsbUiState
    data class Connected(val result: UsbOpenResult) : UsbUiState
    data class Failed(val message: String, val deviceName: String? = null) : UsbUiState
}

sealed interface AnalysisState {
    data object Empty : AnalysisState
    data class Loaded(val analysis: com.nandfixpro.android.nand.NandAnalysis) : AnalysisState
    data class Failed(val message: String) : AnalysisState
}

sealed interface ProdinfoState {
    data object Empty : ProdinfoState
    data class Loaded(
        val summary: ProdinfoEngine.Summary,
        val verification: ProdinfoEngine.Verification,
    ) : ProdinfoState

    data class Failed(val message: String) : ProdinfoState
}

data class FirmwareInventory(
    val rootName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val ncaCount: Int,
    val cnmtCount: Int,
    val registeredCount: Int,
    val placeholderCount: Int,
    val hasBootloader: Boolean,
    val hasAtmosphere: Boolean,
    val folders: List<String>,
    val notes: List<String>,
)

sealed interface FirmwareState {
    data object Empty : FirmwareState
    data class Loaded(val inventory: FirmwareInventory) : FirmwareState
    data class Failed(val message: String) : FirmwareState
}

sealed interface ProdKeysState {
    data object Empty : ProdKeysState
    data class Loaded(val summary: ProdKeysSummary) : ProdKeysState
    data class Failed(val message: String) : ProdKeysState
}

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidRelease: String,
    val sdk: Int,
    val abis: List<String>,
    val isArm64: Boolean,
    val appVersion: String,
    val usbHostSupported: Boolean,
    val freeStorage: Long,
)

enum class AnalysisTarget { ANALYZER, BOOT, PARTITIONS }

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    val logRepository = LogRepository()
    private val settingsRepository = SettingsRepository(app)
    private val storageRepository = StorageRepository(app)
    private val usbRepository = UsbRepository(app) { message ->
        logRepository.usb("USB", message)
    }

    val logEntries = logRepository.entries
    val devices = usbRepository.devices
    val lastUsbActivity = usbRepository.lastActivity
    val usbAssessment = usbRepository.assessment
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages = _messages.asSharedFlow()

    private val _usbState = MutableStateFlow<UsbUiState>(UsbUiState.Idle)
    val usbState: StateFlow<UsbUiState> = _usbState.asStateFlow()

    private val _operation = MutableStateFlow(OperationState())
    val operation: StateFlow<OperationState> = _operation.asStateFlow()

    private val _analyzerState = MutableStateFlow<AnalysisState>(AnalysisState.Empty)
    val analyzerState: StateFlow<AnalysisState> = _analyzerState.asStateFlow()

    private val _bootState = MutableStateFlow<AnalysisState>(AnalysisState.Empty)
    val bootState: StateFlow<AnalysisState> = _bootState.asStateFlow()

    private val _partitionState = MutableStateFlow<AnalysisState>(AnalysisState.Empty)
    val partitionState: StateFlow<AnalysisState> = _partitionState.asStateFlow()

    private val _prodinfoState = MutableStateFlow<ProdinfoState>(ProdinfoState.Empty)
    val prodinfoState: StateFlow<ProdinfoState> = _prodinfoState.asStateFlow()

    private val _firmwareState = MutableStateFlow<FirmwareState>(FirmwareState.Empty)
    val firmwareState: StateFlow<FirmwareState> = _firmwareState.asStateFlow()

    private val _backups = MutableStateFlow<List<BackupArtifact>>(emptyList())
    val backups: StateFlow<List<BackupArtifact>> = _backups.asStateFlow()

    private val _selfTest = MutableStateFlow<SelfTestReport?>(null)
    val selfTest: StateFlow<SelfTestReport?> = _selfTest.asStateFlow()

    private val _prodKeys = MutableStateFlow<ProdKeysState>(ProdKeysState.Empty)
    val prodKeys: StateFlow<ProdKeysState> = _prodKeys.asStateFlow()

    private var prodinfoEngine: ProdinfoEngine? = null
    private var lastUsbDeviceId: Int? = null
    private var cancelRequested = false

    init {
        usbRepository.start()
        refreshBackups()
        logRepository.info(
            "Application",
            "NAND Fix Pro Android ${BuildConfig.VERSION_NAME} démarré · " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "ABI ${Build.SUPPORTED_ABIS.joinToString()}",
        )
        if (!usbRepository.isUsbHostSupported) {
            logRepository.warn("USB", "USB Host non disponible sur cet appareil")
        }
    }

    override fun onCleared() {
        usbRepository.stop()
        super.onCleared()
    }

    // ------------------------------------------------------------------ USB

    fun detectUsb(autoProbe: Boolean = false) {
        logRepository.usb("Détection", "Exploration des périphériques USB connectés")
        usbRepository.refresh()
        val found = usbRepository.devices.value
        if (found.isEmpty()) {
            logRepository.warn("Détection", "Aucun périphérique USB visible")
            message(
                if (usbRepository.isUsbHostSupported) {
                    "Aucun périphérique USB détecté. Vérifiez le câble OTG et le mode USB Tools de Hekate."
                } else {
                    "Cet appareil Android ne prend pas en charge USB Host (OTG)."
                },
            )
            if (autoProbe) _usbState.value = UsbUiState.Failed("Aucun périphérique USB détecté")
            return
        }
        logRepository.usb(
            "Détection",
            "${found.size} périphérique(s) : " + found.joinToString { "${it.vendorHex}:${it.productHex}" },
        )
        if (autoProbe) {
            val candidate = found.firstOrNull { it.isMassStorage } ?: found.firstOrNull()
            if (candidate != null) {
                // Hekate needs a moment after "Started USB" before its SCSI target answers.
                viewModelScope.launch {
                    delay(900)
                    if (usbRepository.devices.value.any { it.deviceId == candidate.deviceId }) {
                        connectDevice(candidate.deviceId)
                    }
                }
            }
        }
    }

    fun connectDevice(deviceId: Int) {
        lastUsbDeviceId = deviceId
        val device = devices.value.firstOrNull { it.deviceId == deviceId }
        if (device == null) {
            message("Périphérique USB introuvable, actualisez la liste.")
            return
        }
        viewModelScope.launch {
            _usbState.value = UsbUiState.RequestingPermission(device.displayName)
            logRepository.usb("Permission", "Demande d'accès à ${device.displayName}")
            val granted = usbRepository.ensurePermission(deviceId)
            if (!granted) {
                _usbState.value = UsbUiState.Failed("Permission USB refusée par l'utilisateur", device.displayName)
                logRepository.warn("Permission", "Refusée pour ${device.displayName}")
                message("Permission USB refusée. Relancez la détection pour réessayer.")
                return@launch
            }
            _usbState.value = UsbUiState.Probing(device.displayName)
            logRepository.usb("Probe", "Ouverture de l'interface UMS de ${device.displayName}")
            val opened = usbRepository.openMassStorage(deviceId, settings.value.usbTimeoutMs)
            opened.onSuccess { result ->
                // A null session means SCSI initialisation failed: the link is open but the
                // target is not usable. Never report that as a live connection.
                _usbState.value = if (result.session == null) {
                    UsbUiState.Failed(result.assessment.headline, device.displayName)
                } else {
                    UsbUiState.Connected(result)
                }
                logRepository.usb(
                    "Compatibilité",
                    "${result.assessment.verdict.label} · ${result.assessment.capacityLabel}",
                )
                NotificationHelper.notify(
                    app,
                    NotificationHelper.ID_USB_STATUS,
                    "Périphérique USB analysé",
                    "${device.displayName} · ${result.assessment.verdict.label}",
                )
            }.onFailure { error ->
                _usbState.value = UsbUiState.Failed(error.message ?: "Échec d'ouverture", device.displayName)
                logRepository.error("Probe", "Échec : ${error.message}")
            }
        }
    }

    fun retryUsb() {
        val id = lastUsbDeviceId
        if (id != null) connectDevice(id) else detectUsb()
    }

    fun disconnectUsb() {
        usbRepository.closeSession()
        _usbState.value = UsbUiState.Idle
        NotificationHelper.cancel(app, NotificationHelper.ID_USB_STATUS)
        logRepository.info("USB", "Connexion libérée par l'utilisateur")
    }

    fun usbReadTest() {
        val assessment = usbAssessment.value
        if (assessment == null) {
            message("Aucune connexion UMS active.")
            return
        }
        launchOperation("Test de lecture USB") { report, cancelled ->
            val length = minOf(assessment.capacity?.totalBytes ?: 0L, 4L * 1024 * 1024).toInt()
            if (length <= 0) {
                return@launchOperation OperationResult(false, "Lecture impossible", "Capacité inconnue.")
            }
            report(0.2f, "Lecture de ${NandSize.human(length.toLong())} en lecture seule")
            val started = System.currentTimeMillis()
            val bytes = usbRepository.readWindow(0, length).getOrNull()
                ?: return@launchOperation OperationResult(false, "Lecture USB échouée", "La lecture de test n'a pas abouti.")
            if (cancelled()) return@launchOperation OperationResult(false, "Lecture annulée", "")
            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
            val hash = NandAnalyzer.sha256(bytes)
            val speed = length.toDouble() / (elapsed / 1000.0) / (1024 * 1024)
            logRepository.usb(
                "Lecture",
                "%d octets lus en %d ms (%.2f Mio/s), SHA-256 %s".format(length, elapsed, speed, hash.take(16)),
            )
            report(1f, "Lecture vérifiée")
            OperationResult(
                true,
                "Lecture USB validée",
                "${NandSize.human(length.toLong())} lus en $elapsed ms (%.1f Mio/s) · SHA-256 ${hash.take(20)}…".format(speed),
            )
        }
    }

    fun saveUsbBootPartition() {
        val assessment = usbAssessment.value
        if (assessment == null || assessment.verdict != CompatibilityVerdict.HEKATE_BOOT) {
            message("Cette action est disponible uniquement pour une partition BOOT exposée en USB.")
            return
        }
        launchOperation("Sauvegarde de la partition BOOT") { report, cancelled ->
            val bytes = usbRepository.readWholeDevice(16L * 1024 * 1024, report, cancelled).getOrNull()
                ?: return@launchOperation OperationResult(
                    false,
                    "Sauvegarde BOOT impossible",
                    "La lecture complète n'a pas abouti. Vérifiez la connexion.",
                )
            if (cancelled()) return@launchOperation OperationResult(false, "Sauvegarde annulée", "")
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "Hekate-BOOT-$stamp.bin"
            val saved = storageRepository.saveBytesAsBackup(
                bytes,
                name,
                "Partition BOOT USB (Hekate)",
                "Lu en lecture seule depuis un périphérique UMS Hekate. Aucune écriture sur la console.",
            ).getOrNull()
            refreshBackups()
            if (saved == null) {
                OperationResult(false, "Sauvegarde BOOT échouée", "Écriture locale impossible.")
            } else {
                logRepository.info(
                    "Sauvegarde",
                    "$name · ${NandSize.human(bytes.size.toLong())} · SHA-256 ${saved.manifest.sha256.take(16)}",
                )
                OperationResult(
                    true,
                    "Partition BOOT sauvegardée",
                    "$name · ${NandSize.human(bytes.size.toLong())} · SHA-256 ${saved.manifest.sha256.take(20)}…",
                )
            }
        }
    }

    // ------------------------------------------------------------ analysis

    fun analyzeDocument(uri: Uri, target: AnalysisTarget = AnalysisTarget.ANALYZER) {
        setAnalysis(target, AnalysisState.Empty)
        launchOperation("Analyse du fichier") { report, cancelled ->
            val handle = storageRepository.openDocument(uri).getOrNull()
                ?: return@launchOperation OperationResult(
                    false,
                    "Fichier inaccessible",
                    "Le fournisseur de fichiers ne permet pas un accès aléatoire. Copiez le fichier dans le stockage interne.",
                )
            try {
                if (Regex("(rawnand\\.bin\\.\\d{2,}|\\d{2,})", RegexOption.IGNORE_CASE).matches(handle.displayName)) {
                    return@launchOperation OperationResult(
                        false,
                        "Partie découpée détectée",
                        "Sélectionnez le dossier complet avec « Analyser un jeu découpé » pour vérifier l'ensemble.",
                    )
                }
                report(0.05f, "Lecture de ${handle.displayName}")
                val analysis = withContext(Dispatchers.IO) {
                    NandAnalyzer.analyze(
                        source = handle.source,
                        name = handle.displayName,
                        splitSet = null,
                        onProgress = { progress, label -> report(0.05f + progress * 0.9f, label) },
                        isCancelled = cancelled,
                    )
                }
                setAnalysis(target, AnalysisState.Loaded(analysis))
                logRepository.info(
                    "Analyse",
                    "${handle.displayName} · ${analysis.kind.label} · ${analysis.sourceSize} octets · " +
                        "${analysis.errors} erreur(s), ${analysis.warnings} avertissement(s)",
                )
                OperationResult(
                    true,
                    "Analyse terminée",
                    "${analysis.sourceLabel} · ${NandSize.human(analysis.sourceSize)} · " +
                        "${analysis.errors} erreur(s) / ${analysis.warnings} avertissement(s)",
                )
            } catch (error: CancellationException) {
                OperationResult(false, "Analyse annulée", "")
            } catch (error: Exception) {
                setAnalysis(target, AnalysisState.Failed(error.message ?: "Analyse impossible"))
                OperationResult(false, "Analyse échouée", error.message ?: "Erreur inconnue")
            } finally {
                handle.close()
            }
        }
    }

    fun analyzeSplitFolder(treeUri: Uri, target: AnalysisTarget = AnalysisTarget.ANALYZER) {
        setAnalysis(target, AnalysisState.Empty)
        launchOperation("Analyse d'un jeu découpé") { report, cancelled ->
            val directory = storageRepository.treeRoot(treeUri)
                ?: return@launchOperation OperationResult(false, "Dossier inaccessible", "Permission SAF refusée.")
            storageRepository.takePersistablePermission(treeUri)
            val children = storageRepository.listChildren(directory)
            val candidates = children.filter { !it.isDirectory }
                .map { it.name }
                .filter {
                    it.equals("rawnand.bin.00", ignoreCase = true) ||
                        Regex("\\d{2,}").matches(it)
                }
                .sortedWith(compareBy({ !it.equals("rawnand.bin.00", true) }, { it }))
            if (candidates.isEmpty()) {
                return@launchOperation OperationResult(
                    false,
                    "Aucun jeu découpé",
                    "Le dossier ne contient ni rawnand.bin.NN ni fichiers emuMMC NN.",
                )
            }
            report(0.05f, "Assemblage des parties")
            var lastError = "Aucun jeu complet trouvé"
            for (candidate in candidates) {
                val bundle = storageRepository.openSplitSet(directory, candidate).getOrNull()
                if (bundle == null) {
                    lastError = "Jeu incomplet autour de $candidate"
                    continue
                }
                try {
                    val analysis = withContext(Dispatchers.IO) {
                        NandAnalyzer.analyze(
                            source = bundle.handle.source,
                            name = bundle.handle.displayName,
                            splitSet = bundle.split,
                            onProgress = { progress, label -> report(0.05f + progress * 0.9f, label) },
                            isCancelled = cancelled,
                        )
                    }
                    setAnalysis(target, AnalysisState.Loaded(analysis))
                    logRepository.info(
                        "Analyse",
                        "Jeu découpé ${bundle.split.format.label} · ${bundle.split.partNames.size} parties · " +
                            "${analysis.errors} erreur(s)",
                    )
                    return@launchOperation OperationResult(
                        true,
                        "Jeu découpé analysé",
                        "${bundle.split.partNames.size} parties · ${NandSize.human(bundle.split.totalSize)} · " +
                            "${analysis.errors} erreur(s)",
                    )
                } catch (error: CancellationException) {
                    return@launchOperation OperationResult(false, "Analyse annulée", "")
                } catch (error: Exception) {
                    lastError = error.message ?: "Analyse impossible"
                } finally {
                    bundle.handle.close()
                }
            }
            setAnalysis(target, AnalysisState.Failed(lastError))
            OperationResult(false, "Jeu découpé illisible", lastError)
        }
    }

    fun analyzeSplitAt(treeUri: Uri, relativePath: List<String>, selectedName: String) {
        setAnalysis(AnalysisTarget.ANALYZER, AnalysisState.Empty)
        launchOperation("Analyse du jeu découpé") { report, cancelled ->
            val root = storageRepository.treeRoot(treeUri)
                ?: return@launchOperation OperationResult(false, "Dossier inaccessible", "Permission SAF absente.")
            var directory = root
            for (segment in relativePath) {
                directory = directory.findFile(segment)?.takeIf { it.isDirectory }
                    ?: return@launchOperation OperationResult(false, "Dossier introuvable", segment)
            }
            val bundle = storageRepository.openSplitSet(directory, selectedName).getOrNull()
                ?: return@launchOperation OperationResult(
                    false,
                    "Jeu incomplet",
                    "Toutes les parties $selectedName et les fichiers BOOT requis sont nécessaires.",
                )
            try {
                val analysis = withContext(Dispatchers.IO) {
                    NandAnalyzer.analyze(
                        source = bundle.handle.source,
                        name = bundle.handle.displayName,
                        splitSet = bundle.split,
                        onProgress = { progress, label -> report(0.05f + progress * 0.9f, label) },
                        isCancelled = cancelled,
                    )
                }
                setAnalysis(AnalysisTarget.ANALYZER, AnalysisState.Loaded(analysis))
                OperationResult(true, "Jeu découpé analysé", "${bundle.split.partNames.size} parties")
            } catch (error: Exception) {
                setAnalysis(AnalysisTarget.ANALYZER, AnalysisState.Failed(error.message ?: "Analyse impossible"))
                OperationResult(false, "Analyse échouée", error.message ?: "")
            } finally {
                bundle.handle.close()
            }
        }
    }

    fun clearAnalysis(target: AnalysisTarget) = setAnalysis(target, AnalysisState.Empty)

    private fun setAnalysis(target: AnalysisTarget, state: AnalysisState) {
        when (target) {
            AnalysisTarget.ANALYZER -> _analyzerState.value = state
            AnalysisTarget.BOOT -> _bootState.value = state
            AnalysisTarget.PARTITIONS -> _partitionState.value = state
        }
    }

    // -------------------------------------------------------------- PRODINFO

    fun loadProdinfo(uri: Uri) {
        _prodinfoState.value = ProdinfoState.Empty
        launchOperation("Chargement PRODINFO") { report, _ ->
            val handle = storageRepository.openDocument(uri).getOrNull()
                ?: return@launchOperation OperationResult(false, "Fichier inaccessible", "Accès aléatoire impossible.")
            try {
                report(0.2f, "Lecture du PRODINFO")
                val length = minOf(handle.source.size, 1024L * 1024L).toInt()
                val bytes = withContext(Dispatchers.IO) { handle.source.read(0, length) }
                val engine = ProdinfoEngine()
                when (val result = engine.load(bytes, handle.displayName)) {
                    is ProdinfoEngine.LoadResult.Success -> {
                        val verification = engine.verify()
                        prodinfoEngine = engine
                        _prodinfoState.value = ProdinfoState.Loaded(result.summary, verification)
                        logRepository.info(
                            "PRODINFO",
                            "${handle.displayName} · ${result.summary.modelName} · " +
                                "${verification.errors} erreur(s), ${verification.warnings} avertissement(s)",
                        )
                        OperationResult(
                            true,
                            if (verification.valid) "PRODINFO valide" else "PRODINFO chargé avec erreurs",
                            "${result.summary.modelName} · série ${result.summary.serialNumber} · " +
                                "${verification.errors} erreur(s) de checksum",
                        )
                    }
                    is ProdinfoEngine.LoadResult.Failure -> {
                        _prodinfoState.value = ProdinfoState.Failed(result.message)
                        OperationResult(false, "PRODINFO invalide", result.message)
                    }
                }
            } finally {
                handle.close()
            }
        }
    }

    fun exportProdinfo(destination: Uri, edit: ProdinfoEngine.Edit) {
        val engine = prodinfoEngine
        if (engine == null) {
            message("Chargez d'abord un PRODINFO.")
            return
        }
        launchOperation("Export du PRODINFO modifié") { report, _ ->
            report(0.2f, "Recalcul des checksums")
            val bytes = engine.applyEditsAndRecalculate(edit).getOrNull()
                ?: return@launchOperation OperationResult(
                    false,
                    "Édition refusée",
                    "Vérifiez le numéro de série (14 caractères) et la couleur (RRGGBB).",
                )
            val verifier = ProdinfoEngine()
            verifier.load(bytes, "copie-verifiee")
            val verification = verifier.verify()
            if (!verification.valid) {
                return@launchOperation OperationResult(
                    false,
                    "Export annulé",
                    "Les checksums recalculés ne sont pas valides : aucune copie n'a été écrite.",
                )
            }
            report(0.8f, "Écriture de la copie")
            val write = storageRepository.writeToUri(destination, bytes)
            if (write.isFailure) {
                return@launchOperation OperationResult(
                    false,
                    "Écriture impossible",
                    write.exceptionOrNull()?.message ?: "",
                )
            }
            logRepository.info("PRODINFO", "Copie éditée exportée (${bytes.size} octets), checksums valides")
            OperationResult(
                true,
                "Copie PRODINFO exportée",
                "Checksums recalculés et vérifiés. Le fichier d'origine n'a pas été modifié.",
            )
        }
    }

    // --------------------------------------------------------- file backing

    fun createBackup(uri: Uri) {
        launchOperation("Création d'une sauvegarde vérifiée") { report, cancelled ->
            val handle = storageRepository.openDocument(uri).getOrNull()
                ?: return@launchOperation OperationResult(false, "Fichier inaccessible", "Accès aléatoire impossible.")
            try {
                report(0.02f, "Lecture de ${handle.displayName}")
                val backup = storageRepository.createBackup(handle, report, cancelled).getOrNull()
                    ?: return@launchOperation OperationResult(
                        false,
                        "Sauvegarde impossible",
                        "Espace insuffisant, limite de 8 Gio atteinte ou opération annulée.",
                    )
                refreshBackups()
                logRepository.info(
                    "Sauvegarde",
                    "${backup.file.name} · ${NandSize.human(backup.manifest.size)} · SHA-256 ${backup.manifest.sha256.take(16)}",
                )
                OperationResult(
                    true,
                    "Sauvegarde créée et hachée",
                    "${backup.file.name} · ${NandSize.human(backup.manifest.size)} · " +
                        "SHA-256 ${backup.manifest.sha256.take(24)}…",
                )
            } finally {
                handle.close()
            }
        }
    }

    fun verifyBackup(artifact: BackupArtifact) {
        launchOperation("Vérification de ${artifact.file.name}") { report, cancelled ->
            val result = storageRepository.verifyBackup(artifact, report, cancelled)
            result.fold(
                onSuccess = { detail ->
                    logRepository.info("Vérification", detail)
                    OperationResult(true, "Sauvegarde intègre", detail)
                },
                onFailure = { error ->
                    logRepository.error("Vérification", error.message ?: "échec")
                    OperationResult(false, "Sauvegarde non conforme", error.message ?: "")
                },
            )
        }
    }

    fun deleteBackup(artifact: BackupArtifact) {
        val deleted = storageRepository.deleteBackup(artifact)
        refreshBackups()
        logRepository.info("Sauvegarde", "Suppression de ${artifact.file.name} : ${if (deleted) "OK" else "échec"}")
        message(if (deleted) "Sauvegarde supprimée" else "Suppression impossible")
    }

    fun hashFile(uri: Uri) {
        launchOperation("Calcul SHA-256") { report, cancelled ->
            val handle = storageRepository.openDocument(uri).getOrNull()
                ?: return@launchOperation OperationResult(false, "Fichier inaccessible", "Accès aléatoire impossible.")
            try {
                val hash = storageRepository.computeHash(handle, report, cancelled).getOrNull()
                    ?: return@launchOperation OperationResult(false, "Hachage interrompu", "")
                logRepository.info("SHA-256", "${handle.displayName} · $hash")
                OperationResult(true, "SHA-256 calculé", "${handle.displayName} · $hash")
            } finally {
                handle.close()
            }
        }
    }

    fun refreshBackups() {
        _backups.value = storageRepository.listBackups()
    }

    fun storageFreeSpace(): Long = storageRepository.usableSpace()

    // ------------------------------------------------------------- firmware

    fun loadProdKeys(uri: Uri) {
        _prodKeys.value = ProdKeysState.Empty
        launchOperation("Lecture de prod.keys") { _, _ ->
            storageRepository.takePersistablePermission(uri)
            val text = storageRepository.readText(uri).getOrNull()
                ?: return@launchOperation OperationResult(
                    false,
                    "prod.keys illisible",
                    "Fichier inaccessible ou supérieur à 256 Kio. Choisissez un fichier prod.keys texte.",
                )
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "prod.keys"
            val summary = ProdKeysParser.parse(text, name)
            if (summary.validKeys == 0) {
                _prodKeys.value = ProdKeysState.Failed("Aucune clé valide détectée")
                return@launchOperation OperationResult(
                    false,
                    "prod.keys invalide",
                    "Aucune entrée valide au format name=hexadécimal.",
                )
            }
            _prodKeys.value = ProdKeysState.Loaded(summary)
            logRepository.info(
                "prod.keys",
                "$name · ${summary.validKeys} clés valides · " +
                    "master ${summary.newestMasterKey ?: "non détectée"}",
            )
            OperationResult(
                true,
                "prod.keys validé",
                "${summary.validKeys} clés valides · ${summary.masterKeys.size} master key(s) · " +
                    "valeurs jamais affichées",
            )
        }
    }

    fun storageVolumes(): List<VolumeInfo> = storageRepository.storageVolumes()

    fun scanFirmware(treeUri: Uri) {
        _firmwareState.value = FirmwareState.Empty
        launchOperation("Inventaire du firmware") { report, cancelled ->
            val root = storageRepository.treeRoot(treeUri)
                ?: return@launchOperation OperationResult(false, "Dossier inaccessible", "Permission SAF refusée.")
            storageRepository.takePersistablePermission(treeUri)
            val inventory = withContext(Dispatchers.IO) { buildFirmwareInventory(root, report, cancelled) }
            _firmwareState.value = FirmwareState.Loaded(inventory)
            logRepository.info(
                "Firmware",
                "${inventory.rootName} · ${inventory.fileCount} fichiers · ${inventory.ncaCount} NCA",
            )
            OperationResult(
                true,
                "Inventaire terminé",
                "${inventory.fileCount} fichiers · ${NandSize.human(inventory.totalBytes)} · " +
                    "${inventory.ncaCount} NCA",
            )
        }
    }

    private fun buildFirmwareInventory(
        root: DocumentFile,
        report: (Float, String) -> Unit,
        cancelled: () -> Boolean,
    ): FirmwareInventory {
        var fileCount = 0
        var totalBytes = 0L
        var ncaCount = 0
        var cnmtCount = 0
        var registeredCount = 0
        var placeholderCount = 0
        var hasBootloader = false
        var hasAtmosphere = false
        val topFolders = mutableListOf<String>()
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.add(root to "")
        var visited = 0
        while (stack.isNotEmpty()) {
            if (cancelled()) throw CancellationException("Inventaire annulé")
            val (directory, path) = stack.removeLast()
            visited++
            if (visited > 400) break
            if (path.isEmpty()) {
                storageRepository.listChildren(directory).forEach { entry ->
                    if (entry.isDirectory) topFolders += entry.name
                }
            }
            if (path.startsWith("bootloader")) hasBootloader = true
            if (path.startsWith("atmosphere")) hasAtmosphere = true
            if (path.contains("Contents/registered")) {
                registeredCount += storageRepository.listChildren(directory).count { !it.isDirectory }
            }
            if (path.contains("Contents/placehld")) {
                placeholderCount += storageRepository.listChildren(directory).count { !it.isDirectory }
            }
            storageRepository.listChildren(directory).forEach { entry ->
                when {
                    entry.isDirectory -> stack.add(
                        (directory.findFile(entry.name) ?: directory) to
                            if (path.isEmpty()) entry.name else "$path/${entry.name}",
                    )
                    else -> {
                        fileCount++
                        totalBytes += entry.size
                        val lower = entry.name.lowercase(Locale.ROOT)
                        if (lower.endsWith(".nca")) ncaCount++
                        if (lower.endsWith(".cnmt.nca")) cnmtCount++
                    }
                }
                if (fileCount > 25_000) return@forEach
            }
            report(
                (visited / 400f).coerceIn(0f, 0.9f),
                "Inventaire : $fileCount fichiers, ${NandSize.human(totalBytes)}",
            )
        }
        val notes = buildList {
            add("La version du firmware n'est pas déduite : elle exige le parsing des NCA, non inclus dans cette version.")
            add("Aucun téléchargement de firmware n'est proposé : fournissez vos propres fichiers.")
            if (registeredCount == 0) add("Dossier Contents/registered absent : structure firmware incomplète.")
            if (ncaCount == 0) add("Aucun fichier .nca détecté à la racine analysée.")
        }
        return FirmwareInventory(
            rootName = root.name ?: "firmware",
            fileCount = fileCount,
            totalBytes = totalBytes,
            ncaCount = ncaCount,
            cnmtCount = cnmtCount,
            registeredCount = registeredCount,
            placeholderCount = placeholderCount,
            hasBootloader = hasBootloader,
            hasAtmosphere = hasAtmosphere,
            folders = topFolders.distinct().take(12),
            notes = notes,
        )
    }

    // ---------------------------------------------------------- diagnostics

    fun runSelfTest() {
        launchOperation("Auto-test des moteurs") { report, _ ->
            report(0.2f, "Exécution des contrôles internes")
            val result = withContext(Dispatchers.IO) { SelfTest.run() }
            _selfTest.value = result
            logRepository.info(
                "Auto-test",
                "${result.passed}/${result.results.size} contrôles réussis",
            )
            OperationResult(
                result.allPassed,
                if (result.allPassed) "Auto-test réussi" else "Auto-test partiel",
                "${result.passed}/${result.results.size} contrôles réussis",
            )
        }
    }

    fun exportLogs() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val result = storageRepository.saveText("nandfix-log-$stamp.txt", logRepository.exportText())
        result.onSuccess { file ->
            logRepository.info("Journal", "Exporté vers ${file.absolutePath}")
            message("Journal exporté : ${file.name}")
        }.onFailure {
            message("Export du journal impossible : ${it.message}")
        }
    }

    fun clearLogs() {
        logRepository.clear()
        message("Journal effacé")
    }

    fun deviceInfo(): DeviceInfo = DeviceInfo(
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        androidRelease = Build.VERSION.RELEASE,
        sdk = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
        isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64") },
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        usbHostSupported = usbRepository.isUsbHostSupported,
        freeStorage = storageRepository.usableSpace(),
    )

    // ------------------------------------------------------------- settings

    fun setNotifications(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setNotificationsEnabled(enabled)
    }

    fun setUsbTimeout(timeoutMs: Int) = viewModelScope.launch {
        settingsRepository.setUsbTimeout(timeoutMs)
    }

    fun setVerboseLogs(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setVerboseLogs(enabled)
    }

    fun setAutoHash(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setAutoHash(enabled)
    }

    fun setRequireConfirmation(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setRequireConfirmation(enabled)
    }

    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setKeepScreenOn(enabled)
    }

    fun resetSettings() = viewModelScope.launch {
        settingsRepository.resetPolicyDefaults()
        message("Paramètres réinitialisés")
    }

    // ------------------------------------------------------------- operation

    fun cancelOperation() {
        cancelRequested = true
        logRepository.warn("Opération", "Annulation demandée par l'utilisateur")
    }

    fun clearOperationResult() {
        _operation.update { it.copy(result = null) }
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    private fun launchOperation(
        title: String,
        block: suspend ((Float, String) -> Unit, () -> Boolean) -> OperationResult,
    ) {
        if (_operation.value.running) {
            message("Une opération est déjà en cours.")
            return
        }
        cancelRequested = false
        viewModelScope.launch {
            _operation.value = OperationState(running = true, label = title)
            val result = try {
                block(
                    { progress, label ->
                        _operation.update {
                            it.copy(progress = progress.coerceIn(0f, 1f), label = label)
                        }
                    },
                    { cancelRequested },
                )
            } catch (error: CancellationException) {
                OperationResult(false, "Opération annulée", "")
            } catch (error: Exception) {
                OperationResult(false, "Opération échouée", error.message ?: error.javaClass.simpleName)
            }
            _operation.value = OperationState(
                running = false,
                progress = if (result.success) 1f else 0f,
                result = result,
            )
            logRepository.info("Opération", "${result.title} — ${result.detail}")
            if (settings.value.notificationsEnabled) {
                NotificationHelper.notify(
                    app,
                    NotificationHelper.ID_OPERATION,
                    result.title,
                    result.detail.ifBlank { "Terminé" },
                )
            }
        }
    }

    companion object {
        val KNOWN_MODULE_LIMITS = listOf(
            "EmmcHaccGen est un binaire .NET x64 : impossible à exécuter sur Android ARM64.",
            "NxNandManager dépend de pilotes Windows et de Dokan : non portable tel quel.",
            "OSFMount est un pilote de disque virtuel Windows : aucune implémentation Android.",
            "La reconstruction/flash NAND exige une écriture brute : volontairement absente en version 1.0.",
        )
        const val MAX_USB_DUMP = 16L * 1024 * 1024
        val CAPACITY_ORDER = listOf(NandCapacity.GB32, NandCapacity.GB64, NandCapacity.GB128)
    }
}
