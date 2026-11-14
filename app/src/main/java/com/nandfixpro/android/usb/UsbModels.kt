package com.nandfixpro.android.usb

import com.nandfixpro.android.nand.GptParser
import com.nandfixpro.android.nand.NandCapacity

data class UsbEndpointInfo(
    val address: Int,
    val type: String,
    val direction: String,
    val maxPacketSize: Int,
    val interval: Int,
) {
    val isBulkIn: Boolean get() = type == "Bulk" && direction == "IN"
    val isBulkOut: Boolean get() = type == "Bulk" && direction == "OUT"
}

data class UsbInterfaceInfo(
    val id: Int,
    val alternateSetting: Int,
    val name: String?,
    val interfaceClass: Int,
    val subClass: Int,
    val protocol: Int,
    val endpoints: List<UsbEndpointInfo>,
) {
    val isMassStorage: Boolean get() = interfaceClass == 0x08
    val isBulkOnlyScsi: Boolean
        get() = interfaceClass == 0x08 && subClass == 0x06 && (protocol == 0x50 || protocol == 0x00)

    val description: String
        get() = when {
            isBulkOnlyScsi -> "USB Mass Storage · SCSI Bulk-Only"
            isMassStorage -> "USB Mass Storage (subclass $subClass, protocole $protocol)"
            else -> name ?: "Interface $id (classe 0x%02X)".format(interfaceClass)
        }
}

data class UsbDeviceSnapshot(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val manufacturer: String?,
    val productName: String?,
    val serialNumber: String?,
    val deviceVersion: String,
    val deviceClass: Int,
    val deviceSubClass: Int,
    val deviceProtocol: Int,
    val interfaces: List<UsbInterfaceInfo>,
    val hasPermission: Boolean,
    val isNintendoVendor: Boolean,
) {
    val vendorHex: String get() = "0x%04X".format(vendorId)
    val productHex: String get() = "0x%04X".format(productId)
    val massStorageInterfaces: List<UsbInterfaceInfo> get() = interfaces.filter { it.isMassStorage }
    val isMassStorage: Boolean get() = massStorageInterfaces.isNotEmpty()
    val bulkOnlyInterface: UsbInterfaceInfo?
        get() = interfaces.firstOrNull { it.isBulkOnlyScsi } ?: interfaces.firstOrNull { it.isMassStorage }
    val displayName: String
        get() = productName?.takeIf { it.isNotBlank() }
            ?: massStorageInterfaces.firstOrNull()?.name
            ?: deviceName
}

enum class CompatibilityVerdict(val label: String, val short: String) {
    HEKATE_GPP("Lisible en lecture seule — disposition NAND Switch", "GPP compatible"),
    HEKATE_BOOT("Partition BOOT détectée — lecture et sauvegarde possibles", "BOOT Hekate"),
    GENERIC_UMS("Stockage USB Mass Storage standard", "UMS standard"),
    INCOMPATIBLE("Communication USB Mass Storage impossible", "Incompatible"),
    UNKNOWN("Compatibilité non déterminée", "Inconnu"),
}

data class HekateAssessment(
    val verdict: CompatibilityVerdict,
    val headline: String,
    val reasons: List<String>,
    val inquiry: ScsiProtocol.Inquiry?,
    val capacity: ScsiProtocol.Capacity?,
    val capacityClass: NandCapacity,
    val gpt: GptParser.Table?,
    val gptError: String?,
    val volumeSignature: String?,
    val switchLayout: Boolean,
    val readOnly: Boolean = true,
    val probedAt: Long = System.currentTimeMillis(),
) {
    val capacityLabel: String
        get() = capacity?.let { com.nandfixpro.android.nand.NandSize.human(it.totalBytes) } ?: "Non accessible"
    val mode: String get() = "USB Mass Storage · Bulk-Only Transport (SCSI) · lecture seule"
}

data class UsbOpenResult(
    val device: UsbDeviceSnapshot,
    val assessment: HekateAssessment,
    val session: MassStorageSession?,
)
