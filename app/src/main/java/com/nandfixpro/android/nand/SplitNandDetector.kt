package com.nandfixpro.android.nand

import java.util.Locale

/**
 * Detects complete split NAND sets, mirroring `detect_split_nand()` from the
 * original Python tool:
 *
 *  - Hekate backups: `rawnand.bin.00`, `rawnand.bin.01`, ...
 *  - emuMMC SD files: `00`, `01`, ... plus `BOOT0` and `BOOT1` beside them.
 *
 * Only metadata is validated here. The GPT cross-check (total size must equal
 * `(backup_lba + 1) * 512`) is performed by [NandAnalyzer] on the assembled set.
 */
object SplitNandDetector {

    enum class Format(val label: String) {
        HEKATE_SPLIT("Sauvegarde Hekate découpée"),
        EMUMMC_SD_FILES("emuMMC (fichiers SD)"),
    }

    data class FileRef(val name: String, val size: Long)

    data class SplitSet(
        val format: Format,
        val partNames: List<String>,
        val chunkSize: Long,
        val totalSize: Long,
        val hasBoot0: Boolean,
        val hasBoot1: Boolean,
        val warnings: List<String>,
    )

    sealed interface Result {
        data class Success(val set: SplitSet) : Result
        data class Failure(val reason: String) : Result
    }

    private val hekatePattern = Regex("^rawnand\\.bin\\.(\\d{2,})$")
    private val emummcPattern = Regex("^(\\d{2,})$")

    fun detect(selectedName: String, siblings: List<FileRef>): Result {
        val lower = selectedName.lowercase(Locale.ROOT)
        if (lower == "rawnand.bin") {
            return Result.Failure("Fichier unique : aucune partie découpée à assembler")
        }

        val hekate = hekatePattern.find(lower)
        val emummc = emummcPattern.find(lower)
        val format: Format
        val pattern: Regex
        if (hekate != null) {
            format = Format.HEKATE_SPLIT
            pattern = hekatePattern
        } else if (emummc != null) {
            format = Format.EMUMMC_SD_FILES
            pattern = emummcPattern
        } else {
            return Result.Failure(
                "Sélectionnez RAWNAND.bin, une partie rawnand.bin.NN ou une partie emuMMC NN.",
            )
        }

        val indices = siblings.mapNotNull { file ->
            pattern.find(file.name.lowercase(Locale.ROOT))?.groupValues?.get(1)?.toIntOrNull()
        }.toSortedSet()

        if (indices.size < 2) {
            return Result.Failure("Les parties du jeu découpé sont manquantes ou incomplètes.")
        }
        val expected = (0..indices.max()).toSet()
        if (!indices.containsAll(expected)) {
            val missing = expected - indices
            return Result.Failure(
                "Parties manquantes : " + missing.joinToString(", ") { "%02d".format(it) },
            )
        }

        val ordered = buildList {
            for (index in 0..indices.max()) {
                val name = partName(format, index)
                val ref = siblings.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: return Result.Failure("Partie introuvable : $name")
                add(ref)
            }
        }

        val sizes = ordered.map { it.size }
        if (sizes.first() == 0L || sizes.first() > 0xFFFFFFFFL) {
            return Result.Failure("Taille de partie invalide (${sizes.first()} octets).")
        }
        val reference = sizes.first()
        if (sizes.dropLast(1).any { it != reference }) {
            return Result.Failure("Les parties n'ont pas une taille homogène.")
        }
        if (sizes.last() <= 0 || sizes.last() > reference) {
            return Result.Failure("La dernière partie est vide ou plus grande que les autres.")
        }
        if (reference % 512L != 0L || sizes.last() % 512L != 0L) {
            return Result.Failure("Les parties ne sont pas alignées sur des secteurs de 512 octets.")
        }

        val hasBoot0 = siblings.any { it.name.equals("BOOT0", ignoreCase = true) }
        val hasBoot1 = siblings.any { it.name.equals("BOOT1", ignoreCase = true) }
        if (format == Format.EMUMMC_SD_FILES && !(hasBoot0 && hasBoot1)) {
            return Result.Failure("emuMMC SD Files exige BOOT0 et BOOT1 à côté des parties numérotées.")
        }

        val warnings = buildList {
            if (format == Format.HEKATE_SPLIT && !hasBoot0) {
                add("BOOT0 absent du dossier : les boot files ne sont pas analysables.")
            }
            if (format == Format.HEKATE_SPLIT && !hasBoot1) {
                add("BOOT1 absent du dossier : les boot files ne sont pas analysables.")
            }
        }

        return Result.Success(
            SplitSet(
                format = format,
                partNames = ordered.map { it.name },
                chunkSize = reference,
                totalSize = sizes.sum(),
                hasBoot0 = hasBoot0,
                hasBoot1 = hasBoot1,
                warnings = warnings,
            ),
        )
    }

    fun partName(format: Format, index: Int): String = when (format) {
        Format.HEKATE_SPLIT -> "rawnand.bin.%02d".format(index)
        Format.EMUMMC_SD_FILES -> "%02d".format(index)
    }

    /** Hekate's FAT32-friendly chunk size is 4 GiB minus one sector. */
    fun isHekateChunkSize(chunkSize: Long): Boolean =
        chunkSize in (4L * 1024 * 1024 * 1024 - 512 * 1024)..(4L * 1024 * 1024 * 1024)
}
