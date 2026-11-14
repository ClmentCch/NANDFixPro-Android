package com.nandfixpro.android.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single owner of USB Host interactions. Enumerates devices, requests permission
 * through a mutable PendingIntent (required since Android 12) and opens read-only
 * Mass Storage sessions. No WRITE SCSI command exists in the app.
 */
class UsbRepository(
    private val context: Context,
    private val logger: (String) -> Unit,
) {

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _devices = MutableStateFlow<List<UsbDeviceSnapshot>>(emptyList())
    val devices: StateFlow<List<UsbDeviceSnapshot>> = _devices.asStateFlow()

    private val _lastActivity = MutableStateFlow<Long?>(null)
    val lastActivity: StateFlow<Long?> = _lastActivity.asStateFlow()

    private val _assessment = MutableStateFlow<HekateAssessment?>(null)
    val assessment: StateFlow<HekateAssessment?> = _assessment.asStateFlow()

    private val pendingPermissions = mutableMapOf<Int, CompletableDeferred<Boolean>>()
    private var currentSession: MassStorageSession? = null
    private var receiver: BroadcastReceiver? = null
    private var started = false

    val isUsbHostSupported: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDevice()
                    _lastActivity.value = System.currentTimeMillis()
                    logger("USB attaché : ${device?.let { describeForLog(it) } ?: "périphérique"}")
                    refresh()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDevice()
                    _lastActivity.value = System.currentTimeMillis()
                    logger("USB détaché : ${device?.let { describeForLog(it) } ?: "périphérique"}")
                    closeSession()
                    pendingPermissions.values.forEach { it.cancel() }
                    pendingPermissions.clear()
                    refresh()
                }

                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.usbDevice()
                    val id = device?.deviceId ?: pendingPermissions.keys.firstOrNull()
                    logger(
                        "Permission USB ${if (granted) "accordée" else "refusée"}" +
                            (device?.let { " pour ${describeForLog(it)}" } ?: ""),
                    )
                    id?.let { pendingPermissions.remove(it)?.complete(granted) }
                    refresh()
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        receiver = usbReceiver
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        if (!isUsbHostSupported) {
            logger("USB Host non pris en charge par cet appareil : la détection USB restera indisponible.")
        }
        refresh()
    }

    fun stop() {
        if (!started) return
        closeSession()
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        started = false
    }

    fun refresh() {
        val manager = usbManager ?: run {
            _devices.value = emptyList()
            return
        }
        val snapshots = runCatching {
            manager.deviceList.values.map { toSnapshot(it, manager.hasPermission(it)) }
                .sortedBy { it.deviceName }
        }.getOrElse {
            logger("Énumération USB impossible : ${it.message}")
            emptyList()
        }
        _devices.value = snapshots
        if (snapshots.isNotEmpty()) {
            _lastActivity.value = _lastActivity.value ?: System.currentTimeMillis()
        }
    }

    suspend fun ensurePermission(deviceId: Int): Boolean = withContext(Dispatchers.IO) {
        val manager = usbManager
            ?: return@withContext false
        val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId }
            ?: return@withContext false
        if (manager.hasPermission(device)) return@withContext true

        val deferred = CompletableDeferred<Boolean>()
        pendingPermissions[deviceId] = deferred
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            deviceId,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.requestPermission(device, pendingIntent)
        val result = withTimeoutOrNull(30_000) {
            runCatching { deferred.await() }.getOrDefault(false)
        } ?: false
        pendingPermissions.remove(deviceId)
        refresh()
        result
    }

    /**
     * Opens the device's Mass Storage interface, initialises SCSI and probes it.
     * The returned session is owned by the repository and must be used from a
     * background thread.
     */
    suspend fun openMassStorage(
        deviceId: Int,
        timeoutMs: Int = 5_000,
    ): Result<UsbOpenResult> = withContext(Dispatchers.IO) {
        val manager = usbManager
            ?: return@withContext Result.failure(IllegalStateException("USB Manager indisponible"))
        val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId }
            ?: return@withContext Result.failure(IllegalStateException("Périphérique USB introuvable"))

        closeSession()

        if (!manager.hasPermission(device)) {
            return@withContext Result.failure(
                IllegalStateException("Permission USB absente : relancez la détection et acceptez la demande."),
            )
        }

        val usbInterface = findMassStorageInterface(device)
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "Aucune interface USB Mass Storage (classe 0x08) sur ce périphérique. " +
                        "Utilisez Hekate → USB Tools → eMMC RAW GPP.",
                ),
            )

        val endpoints = usbInterface.usbInterface.endpoints()
        val inEndpoint = endpoints.firstOrNull { it.isBulkIn }
        val outEndpoint = endpoints.firstOrNull { it.isBulkOut }
        if (inEndpoint == null || outEndpoint == null) {
            return@withContext Result.failure(
                IllegalStateException("Points de terminaison Bulk IN/OUT absents sur l'interface UMS."),
            )
        }

        val connection = manager.openDevice(device)
            ?: return@withContext Result.failure(
                IllegalStateException("Ouverture USB refusée (périphérique déjà utilisé par le système ?)"),
            )

        val session = MassStorageSession(
            connection = connection,
            usbInterface = usbInterface.usbInterface,
            bulkIn = inEndpoint.usbEndpoint,
            bulkOut = outEndpoint.usbEndpoint,
            timeoutMs = timeoutMs.coerceAtLeast(10_000),
            logger = logger,
        )

        val snapshot = toSnapshot(device, hasPermission = true)
        logger(
            "Ouverture UMS : ${snapshot.displayName} (${snapshot.vendorHex}:${snapshot.productHex}) " +
                "interface ${usbInterface.usbInterface.id}",
        )

        val init = runCatching { session.initialize() }.getOrElse { Result.failure(it) }
        if (init.isFailure) {
            val error = init.exceptionOrNull()
            val assessment = HekateAssessment(
                verdict = CompatibilityVerdict.INCOMPATIBLE,
                headline = "Communication SCSI impossible",
                reasons = listOfNotNull(
                    error?.message ?: "Échec SCSI sans message",
                    "Étape échouée : ${error?.message?.substringBefore(':') ?: "initialisation"}",
                    "Vérifiez que Hekate affiche « Started USB » pour eMMC RAW GPP, puis réessayez.",
                    "Un câble OTG de mauvaise qualité ou une alimentation insuffisante peut couper la session.",
                ),
                inquiry = session.inquiry,
                capacity = session.capacity,
                capacityClass = com.nandfixpro.android.nand.NandCapacity.OTHER,
                gpt = null,
                gptError = null,
                volumeSignature = null,
                switchLayout = false,
            )
            session.close()
            _assessment.value = assessment
            return@withContext Result.success(UsbOpenResult(snapshot, assessment, null))
        }

        val assessment = HekateProbe.probe(session, snapshot, logger)
        currentSession = session
        _assessment.value = assessment
        logger("Probe Hekate : ${assessment.verdict.label}")
        Result.success(UsbOpenResult(snapshot, assessment, session))
    }

    fun closeSession() {
        runCatching { currentSession?.close() }
        currentSession = null
        _assessment.value = null
    }

    suspend fun readWindow(offset: Long, length: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        val session = currentSession
            ?: return@withContext Result.failure(IllegalStateException("Aucune connexion UMS active"))
        runCatching { session.read(offset, length) }
    }

    suspend fun readWholeDevice(
        maxBytes: Long,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val session = currentSession
            ?: return@withContext Result.failure(IllegalStateException("Aucune connexion UMS active"))
        if (session.size <= 0L) {
            return@withContext Result.failure(IllegalStateException("Capacité du périphérique inconnue"))
        }
        if (session.size > maxBytes) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Lecture complète refusée : ${com.nandfixpro.android.nand.NandSize.human(session.size)} " +
                        "dépasse la limite de ${com.nandfixpro.android.nand.NandSize.human(maxBytes)}.",
                ),
            )
        }
        val total = session.size.toInt()
        val bytes = ByteArray(total)
        var offset = 0L
        while (offset < total) {
            if (isCancelled()) {
                return@withContext Result.failure(IllegalStateException("Lecture annulée par l'utilisateur"))
            }
            val chunk = minOf(1024L * 1024L, total - offset).toInt()
            val data = runCatching { session.read(offset, chunk) }.getOrElse {
                return@withContext Result.failure(
                    IllegalStateException("Lecture USB échouée à l'offset 0x${offset.toString(16)}: ${it.message}"),
                )
            }
            data.copyInto(bytes, offset.toInt())
            offset += chunk
            onProgress(
                offset.toFloat() / total,
                "Lecture ${com.nandfixpro.android.nand.NandSize.human(offset)} / " +
                    com.nandfixpro.android.nand.NandSize.human(total.toLong()),
            )
        }
        Result.success(bytes)
    }

    val hasOpenSession: Boolean get() = currentSession != null

    // ------------------------------------------------------------------ helpers

    private fun findMassStorageInterface(device: UsbDevice): InterfaceHandle? {
        val candidates = (0 until device.interfaceCount).map { index ->
            val intf = device.getInterface(index)
            InterfaceHandle(
                usbInterface = intf,
                interfaceClass = intf.interfaceClass,
                subClass = intf.interfaceSubclass,
                protocol = intf.interfaceProtocol,
            )
        }
        val preferred = candidates.firstOrNull {
            it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                it.subClass == 0x06 && (it.protocol == 0x50 || it.protocol == 0x00)
        }
        return preferred ?: candidates.firstOrNull {
            it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
        }
    }

    private data class InterfaceHandle(
        val usbInterface: android.hardware.usb.UsbInterface,
        val interfaceClass: Int,
        val subClass: Int,
        val protocol: Int,
    )

    private class EndpointHandle(
        val usbEndpoint: android.hardware.usb.UsbEndpoint,
        val isBulkIn: Boolean,
        val isBulkOut: Boolean,
    )

    private fun android.hardware.usb.UsbInterface.endpoints(): List<EndpointHandle> =
        (0 until endpointCount).map { index ->
            val endpoint = getEndpoint(index)
            EndpointHandle(
                usbEndpoint = endpoint,
                isBulkIn = endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_IN,
                isBulkOut = endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT,
            )
        }

    private fun toSnapshot(device: UsbDevice, hasPermission: Boolean): UsbDeviceSnapshot {
        val interfaces = (0 until device.interfaceCount).map { index ->
            val intf = device.getInterface(index)
            val endpoints = (0 until intf.endpointCount).map { endpointIndex ->
                val endpoint = intf.getEndpoint(endpointIndex)
                UsbEndpointInfo(
                    address = endpoint.address,
                    type = endpointTypeName(endpoint.type),
                    direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
                    maxPacketSize = endpoint.maxPacketSize,
                    interval = endpoint.interval,
                )
            }
            UsbInterfaceInfo(
                id = intf.id,
                alternateSetting = intf.alternateSetting,
                name = runCatching { intf.name }.getOrNull(),
                interfaceClass = intf.interfaceClass,
                subClass = intf.interfaceSubclass,
                protocol = intf.interfaceProtocol,
                endpoints = endpoints,
            )
        }
        val vendorId = device.vendorId
        return UsbDeviceSnapshot(
            deviceId = device.deviceId,
            vendorId = vendorId,
            productId = device.productId,
            deviceName = device.deviceName,
            manufacturer = runCatching { device.manufacturerName }.getOrNull(),
            productName = runCatching { device.productName }.getOrNull(),
            serialNumber = runCatching { device.serialNumber }.getOrNull(),
            deviceVersion = device.version,
            deviceClass = device.deviceClass,
            deviceSubClass = device.deviceSubclass,
            deviceProtocol = device.deviceProtocol,
            interfaces = interfaces,
            hasPermission = hasPermission,
            isNintendoVendor = vendorId == NINTENDO_VENDOR_ID,
        )
    }

    private fun Intent.usbDevice(): UsbDevice? {
        val extra = getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
        return extra ?: usbManager?.deviceList?.values?.firstOrNull {
            it.deviceId == getIntExtra("deviceId", -1)
        }
    }

    private fun describeForLog(device: UsbDevice): String =
        "VID 0x%04X PID 0x%04X (%s)".format(
            device.vendorId,
            device.productId,
            runCatching { device.productName }.getOrNull() ?: device.deviceName,
        )

    private fun endpointTypeName(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "Bulk"
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "Control"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "Interrupt"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "Isochrone"
        else -> "Inconnu"
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.nandfixpro.android.action.USB_PERMISSION"
        const val NINTENDO_VENDOR_ID = 0x057E
    }
}
