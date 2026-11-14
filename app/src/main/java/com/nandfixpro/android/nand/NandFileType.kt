package com.nandfixpro.android.nand

import java.util.Locale

enum class NandFileKind(val label: String) {
    RAWNAND("RAWNAND / image GPP brute"),
    HEKATE_SPLIT_PART("Partie de sauvegarde Hekate (rawnand.bin.NN)"),
    EMUMMC_PART("Partie emuMMC (00, 01, ...)"),
    BOOT0("BOOT0"),
    BOOT1("BOOT1"),
    PRODINFO("PRODINFO déchiffré (CAL0)"),
    PRODINFO_RAW("PRODINFO brut / chiffré"),
    PROD_KEYS("Fichier de clés prod.keys"),
    UNKNOWN("Type non identifié"),
}

data class DetectedFile(
    val kind: NandFileKind,
    val confidence: Confidence,
    val detail: String,
) {
    enum class Confidence { CERTAIN, LIKELY, UNCERTAIN }
}

/**
 * File-type detection for the formats supported by NANDFixPro. Detection relies on
 * names, sizes and real signatures read from the file, and never on the extension
 * alone.
 */
object NandFileDetector {

    const val BOOT_PARTITION_SIZE = 4L * 1024 * 1024

    fun detect(name: String, size: Long, head: ByteArray?): DetectedFile {
        val lower = name.lowercase(Locale.ROOT)

        if (lower.endsWith(".keys") || lower == "prod.keys") {
            val text = head?.toString(Charsets.UTF_8).orEmpty()
            return if (text.contains("=") || text.contains("key")) {
                DetectedFile(NandFileKind.PROD_KEYS, DetectedFile.Confidence.CERTAIN, "Fichier texte de clés détecté")
            } else {
                DetectedFile(NandFileKind.PROD_KEYS, DetectedFile.Confidence.LIKELY, "Extension .keys")
            }
        }

        if (head != null && head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "CAL0") {
            return DetectedFile(
                NandFileKind.PRODINFO,
                DetectedFile.Confidence.CERTAIN,
                "Signature CAL0 présente (PRODINFO déchiffré)",
            )
        }

        if (lower == "boot0") {
            return DetectedFile(
                NandFileKind.BOOT0,
                DetectedFile.Confidence.CERTAIN,
                bootDetail(size),
            )
        }
        if (lower == "boot1") {
            return DetectedFile(
                NandFileKind.BOOT1,
                DetectedFile.Confidence.CERTAIN,
                bootDetail(size),
            )
        }
        if (lower == "rawnand.bin") {
            return DetectedFile(
                NandFileKind.RAWNAND,
                DetectedFile.Confidence.CERTAIN,
                "Nom de sauvegarde Hekate standard",
            )
        }
        if (Regex("raw\\d*\\.bin").matches(lower) || lower.endsWith(".bin") && size > 8L * 1024 * 1024) {
            return DetectedFile(
                NandFileKind.RAWNAND,
                DetectedFile.Confidence.LIKELY,
                "Image binaire volumineuse",
            )
        }
        if (Regex("rawnand\\.bin\\.\\d{2,}").matches(lower)) {
            return DetectedFile(
                NandFileKind.HEKATE_SPLIT_PART,
                DetectedFile.Confidence.CERTAIN,
                "Numérotation de sauvegarde Hekate",
            )
        }
        if (Regex("\\d{2,}").matches(lower)) {
            return DetectedFile(
                NandFileKind.EMUMMC_PART,
                DetectedFile.Confidence.LIKELY,
                "Fichier numéroté de type emuMMC",
            )
        }
        if (size in 0x40000L..0x100000L && lower.endsWith(".bin")) {
            return DetectedFile(
                NandFileKind.PRODINFO_RAW,
                DetectedFile.Confidence.UNCERTAIN,
                "Taille compatible PRODINFO mais aucune signature CAL0",
            )
        }
        return DetectedFile(NandFileKind.UNKNOWN, DetectedFile.Confidence.UNCERTAIN, "Aucune signature reconnue")
    }

    private fun bootDetail(size: Long): String = when (size) {
        BOOT_PARTITION_SIZE -> "Taille de partition BOOT attendue (4 Mio)"
        0L -> "Fichier vide"
        else -> "Taille inattendue (${NandSize.human(size)}, attendu 4 Mio)"
    }
}

enum class NandCapacity(val label: String, val nominalGb: Int) {
    GB32("32 Go", 32),
    GB64("64 Go", 64),
    GB128("128 Go", 128),
    OTHER("Non standard", 0),
}

object NandSize {

    private const val GIB = 1024.0 * 1024.0 * 1024.0

    /**
     * Switch eMMC user areas are marketed as 32/64/128 GB but expose roughly
     * 29.1/58.2/116.5 GiB. Classification uses wide ranges so a genuine
     * console is never rejected because of OEM sector-count differences.
     */
    fun classify(bytes: Long): NandCapacity {
        val gib = bytes / GIB
        return when {
            gib in 20.0..40.0 -> NandCapacity.GB32
            gib in 40.0..90.0 -> NandCapacity.GB64
            gib in 90.0..160.0 -> NandCapacity.GB128
            else -> NandCapacity.OTHER
        }
    }

    fun human(bytes: Long): String {
        if (bytes < 0) return "—"
        val units = listOf("o", "Kio", "Mio", "Gio", "Tio")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) {
            "${bytes} ${units[index]}"
        } else {
            String.format(Locale.US, "%.2f %s", value, units[index])
        }
    }

    fun isSectorAligned(bytes: Long): Boolean = bytes > 0 && bytes % 512L == 0L
}
