package com.nandfixpro.android.usb

import com.nandfixpro.android.nand.GptParser
import com.nandfixpro.android.nand.NandCapacity
import com.nandfixpro.android.nand.NandSize
import com.nandfixpro.android.nand.PartitionProbe
import java.util.Locale

/**
 * Builds a compatibility report from things the app actually observed: USB
 * interface class, SCSI INQUIRY, READ CAPACITY and the content of the first GPT
 * sectors. A device is only reported as GPP-compatible when a Switch partition
 * layout is genuinely readable; nothing is inferred from marketing names.
 */
object HekateProbe {

    private const val BOOT_PARTITION_MAX = 16L * 1024 * 1024

    fun probe(
        session: MassStorageSession,
        device: UsbDeviceSnapshot,
        logger: (String) -> Unit = {},
    ): HekateAssessment {
        val reasons = mutableListOf<String>()
        val inquiry = session.inquiry
        val capacity = session.capacity

        if (device.bulkOnlyInterface != null) {
            reasons += "Interface USB Mass Storage Bulk-Only présente (classe 0x08, sous-classe 0x06)"
        }
        device.manufacturer?.takeIf { it.isNotBlank() }?.let { reasons += "Fabricant USB : $it" }
        device.productName?.takeIf { it.isNotBlank() }?.let { reasons += "Produit USB : $it" }
        if (device.isNintendoVendor) {
            reasons += "VID Nintendo détecté (0x057E)"
        }
        inquiry?.let {
            reasons += "SCSI INQUIRY : ${it.vendor.ifBlank { "—" }} / ${it.product.ifBlank { "—" }} " +
                "rév. ${it.revision.ifBlank { "—" }}"
            if (isHekateBranding(it)) {
                reasons += "INQUIRY identifie explicitement hekate"
            }
        }
        capacity?.let {
            reasons += "Capacité annoncée : ${NandSize.human(it.totalBytes)} " +
                "(${it.totalSectors} secteurs de ${it.blockSize} octets)"
        }

        val capacityClass = capacity?.let { NandSize.classify(it.totalBytes) } ?: NandCapacity.OTHER

        if (capacity == null) {
            return HekateAssessment(
                verdict = CompatibilityVerdict.INCOMPATIBLE,
                headline = "Capacité inaccessible",
                reasons = reasons + "READ CAPACITY n'a retourné aucune valeur exploitable.",
                inquiry = inquiry,
                capacity = null,
                capacityClass = NandCapacity.OTHER,
                gpt = null,
                gptError = null,
                volumeSignature = null,
                switchLayout = false,
            )
        }

        val bootSector = runCatching { session.read(0, 512) }.getOrNull()
        val volumeSignature = bootSector?.let { PartitionProbe.describe(it) }
        volumeSignature?.let { reasons += "Premier secteur : $it" }

        val gptResult = runCatching { GptParser.parse(session) }.getOrNull()
        val gpt = (gptResult as? GptParser.ParseResult.Success)?.table
        val gptError = (gptResult as? GptParser.ParseResult.Failure)?.reason

        if (gpt != null) {
            reasons += "GPT lisible : ${gpt.partitions.size} partitions, CRC en-tête " +
                if (gpt.header.headerCrcValid) "valide" else "invalide"
            if (gpt.header.imageSizeBytes != capacity.totalBytes) {
                reasons += "Note : la GPT déclare ${NandSize.human(gpt.header.imageSizeBytes)}, " +
                    "le périphérique annonce ${NandSize.human(capacity.totalBytes)}"
            }
            val missing = GptParser.SWITCH_PARTITION_NAMES - gpt.partitionNames
            if (gpt.looksLikeSwitch) {
                reasons += "Partitions Switch : " + gpt.partitions.joinToString(", ") { it.name }
            } else {
                reasons += "Partitions non Nintendo : " + gpt.partitions.take(6).joinToString(", ") { it.name } +
                    if (missing.isNotEmpty()) " (manquantes : ${missing.take(4).joinToString(", ")})" else ""
            }
        } else if (gptError != null) {
            reasons += "GPT non lisible : $gptError"
        }

        val switchLayout = gpt?.looksLikeSwitch == true && gpt.header.headerCrcValid
        val bootCandidate = capacity.totalBytes in 512L..BOOT_PARTITION_MAX && gpt == null

        val verdict = when {
            switchLayout && capacityClass != NandCapacity.OTHER -> CompatibilityVerdict.HEKATE_GPP
            switchLayout -> CompatibilityVerdict.HEKATE_GPP
            bootCandidate -> CompatibilityVerdict.HEKATE_BOOT
            volumeSignature != null && (volumeSignature.contains("FAT") || volumeSignature.contains("exFAT")) ->
                CompatibilityVerdict.GENERIC_UMS
            gpt != null -> CompatibilityVerdict.GENERIC_UMS
            volumeSignature == "zone vide (0x00)" || volumeSignature == "zone effacée (0xFF)" ->
                CompatibilityVerdict.UNKNOWN
            else -> CompatibilityVerdict.INCOMPATIBLE
        }

        val headline = when (verdict) {
            CompatibilityVerdict.HEKATE_GPP ->
                "Cible Hekate GPP lisible en lecture seule (${capacityClass.label})"
            CompatibilityVerdict.HEKATE_BOOT ->
                "Partition BOOT exposée en USB (${NandSize.human(capacity.totalBytes)})"
            CompatibilityVerdict.GENERIC_UMS ->
                "Périphérique Mass Storage standard — pas une image GPP Nintendo"
            CompatibilityVerdict.INCOMPATIBLE ->
                "Lecture SCSI possible mais aucune structure NAND identifiée"
            CompatibilityVerdict.UNKNOWN ->
                "Périphérique lisible mais contenu non identifiable"
        }

        reasons += if (verdict == CompatibilityVerdict.HEKATE_GPP) {
            "Analyse NAND locale disponible : GPT et partitions peuvent être inspectées sans écriture."
        } else {
            "Aucune écriture ne sera tentée sur ce périphérique."
        }

        if (gpt == null && !device.hasPermission) {
            reasons += "Permission USB non accordée : relancez la détection."
        }
        logger("Hekate probe: ${verdict.name} · ${capacityClass.label} · switchLayout=$switchLayout")
        return HekateAssessment(
            verdict = verdict,
            headline = headline,
            reasons = reasons,
            inquiry = inquiry,
            capacity = capacity,
            capacityClass = capacityClass,
            gpt = gpt,
            gptError = gptError,
            volumeSignature = volumeSignature,
            switchLayout = switchLayout,
        )
    }

    private fun isHekateBranding(inquiry: ScsiProtocol.Inquiry): Boolean {
        val text = "${inquiry.vendor} ${inquiry.product}".lowercase(Locale.ROOT)
        return text.contains("hekate") || text.contains("switch") || text.contains("nx-")
    }
}
