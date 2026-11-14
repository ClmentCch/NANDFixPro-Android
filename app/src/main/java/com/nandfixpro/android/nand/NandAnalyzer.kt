package com.nandfixpro.android.nand

import java.security.MessageDigest
import java.util.concurrent.CancellationException

enum class IssueSeverity { OK, INFO, WARNING, ERROR }

data class IntegrityIssue(
    val severity: IssueSeverity,
    val title: String,
    val detail: String,
)

data class PartitionScan(
    val name: String,
    val index: Int,
    val startBytes: Long,
    val sizeBytes: Long,
    val typeGuid: String,
    val firstBytes: String,
    val signature: String,
    val sampleSha256: String?,
)

data class BootInfo(
    val name: String,
    val size: Long,
    val expectedSize: Long,
    val firstBytes: String,
    val blank: Boolean,
    val sha256: String,
)

data class NandAnalysis(
    val sourceLabel: String,
    val sourceSize: Long,
    val kind: NandFileKind,
    val kindDetail: String,
    val capacity: NandCapacity,
    val headSha256: String?,
    val gpt: GptParser.Table?,
    val gptError: String?,
    val partitions: List<PartitionScan>,
    val prodinfo: ProdinfoEngine.Summary?,
    val prodinfoVerification: ProdinfoEngine.Verification?,
    val keys: ProdKeysSummary?,
    val boot: BootInfo?,
    val split: SplitNandDetector.SplitSet?,
    val issues: List<IntegrityIssue>,
    val notes: List<String>,
    val durationMs: Long,
) {
    val errors: Int get() = issues.count { it.severity == IssueSeverity.ERROR }
    val warnings: Int get() = issues.count { it.severity == IssueSeverity.WARNING }
    val healthy: Boolean get() = errors == 0
}

/**
 * Analysis pipeline shared by local files and USB sources. It is strictly
 * read-only: the only writes happen when the user explicitly saves a backup in
 * another module.
 */
object NandAnalyzer {

    private const val SAMPLE_BYTES = 4 * 1024 * 1024
    private const val PRODINFO_MAX = 1024 * 1024

    fun analyze(
        source: RandomAccessSource,
        name: String,
        splitSet: SplitNandDetector.SplitSet? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): NandAnalysis {
        val startedAt = System.currentTimeMillis()
        val issues = mutableListOf<IntegrityIssue>()
        val notes = mutableListOf<String>()

        val headLength = minOf(source.size, 4096L).toInt()
        val head = if (headLength > 0) source.read(0, headLength) else ByteArray(0)
        if (isCancelled()) throw CancellationException("Analyse annulée")
        val detected = NandFileDetector.detect(name, source.size, head)
        onProgress(0.1f, "Fichier identifié: ${detected.kind.label}")

        var gpt: GptParser.Table? = null
        var gptError: String? = null
        var partitions = emptyList<PartitionScan>()
        var prodinfo: ProdinfoEngine.Summary? = null
        var prodinfoVerification: ProdinfoEngine.Verification? = null
        var keys: ProdKeysSummary? = null
        var boot: BootInfo? = null

        if (source.size <= 0) {
            issues += IntegrityIssue(IssueSeverity.ERROR, "Fichier vide", "Aucune donnée lisible.")
        }

        when (detected.kind) {
            NandFileKind.PRODINFO -> {
                onProgress(0.3f, "Lecture du PRODINFO")
                val length = minOf(source.size, PRODINFO_MAX.toLong()).toInt()
                val bytes = source.read(0, length)
                val engine = ProdinfoEngine()
                when (val loaded = engine.load(bytes, name)) {
                    is ProdinfoEngine.LoadResult.Success -> {
                        prodinfo = loaded.summary
                        prodinfoVerification = engine.verify()
                        issues += verificationIssues(prodinfoVerification!!)
                    }
                    is ProdinfoEngine.LoadResult.Failure -> {
                        issues += IntegrityIssue(IssueSeverity.ERROR, "PRODINFO invalide", loaded.message)
                    }
                }
            }

            NandFileKind.PRODINFO_RAW -> {
                notes += "PRODINFO sans signature CAL0 : probablement chiffré. Le déchiffrement exige " +
                    "les clés de la console et n'est pas réalisé par l'application."
                issues += IntegrityIssue(
                    IssueSeverity.WARNING,
                    "PRODINFO chiffré",
                    "Le contenu n'est pas analysable sans déchiffrement (clés requises).",
                )
            }

            NandFileKind.PROD_KEYS -> {
                onProgress(0.3f, "Analyse du fichier de clés")
                val length = minOf(source.size, 2L * 1024 * 1024).toInt()
                val text = source.read(0, length).toString(Charsets.UTF_8)
                keys = ProdKeysParser.parse(text, name)
                issues += IntegrityIssue(
                    IssueSeverity.INFO,
                    "Fichier de clés",
                    "${keys.validKeys} clés valides, ${keys.masterKeys.size} générations de master keys.",
                )
                if (!keys.looksComplete) {
                    issues += IntegrityIssue(
                        IssueSeverity.WARNING,
                        "Jeu de clés potentiellement incomplet",
                        "Un prod.keys complet contient généralement plus de 100 clés.",
                    )
                }
            }

            NandFileKind.BOOT0, NandFileKind.BOOT1 -> {
                onProgress(0.3f, "Analyse ${detected.kind.label}")
                val length = minOf(source.size, 8L * 1024 * 1024).toInt()
                val bytes = source.read(0, length)
                val blank = bytes.all { it == 0.toByte() } || bytes.all { it == 0xFF.toByte() }
                boot = BootInfo(
                    name = detected.kind.label,
                    size = source.size,
                    expectedSize = NandFileDetector.BOOT_PARTITION_SIZE,
                    firstBytes = bytes.take(16).toByteArray().toHex(),
                    blank = blank,
                    sha256 = sha256(bytes),
                )
                if (source.size != NandFileDetector.BOOT_PARTITION_SIZE) {
                    issues += IntegrityIssue(
                        IssueSeverity.WARNING,
                        "Taille BOOT inattendue",
                        "Taille ${NandSize.human(source.size)} au lieu de 4 Mio. " +
                            "Hekate peut exposer une taille réelle supérieure en USB; vérifiez le dump.",
                    )
                }
                if (blank) {
                    issues += IntegrityIssue(
                        IssueSeverity.ERROR,
                        "Partition BOOT vide",
                        "Tous les octets sont 0x00 ou 0xFF : dump invalide ou partition effacée.",
                    )
                }
            }

            NandFileKind.RAWNAND,
            NandFileKind.HEKATE_SPLIT_PART,
            NandFileKind.EMUMMC_PART,
            NandFileKind.UNKNOWN,
            -> {
                if (detected.kind == NandFileKind.UNKNOWN) {
                    notes += "Type non reconnu : analyse GPT tentée malgré tout."
                }
                onProgress(0.2f, "Lecture de la table GPT")
                when (val parsed = GptParser.parse(source)) {
                    is GptParser.ParseResult.Success -> {
                        gpt = parsed.table
                        onProgress(0.4f, "Analyse des partitions")
                        partitions = scanPartitions(source, parsed.table, onProgress)
                        issues += gptIssues(parsed.table, source)
                        checkBackupGpt(source, parsed.table, issues)
                        checkProdinfoPartition(source, parsed.table, issues, notes).let { result ->
                            prodinfo = result?.first
                            prodinfoVerification = result?.second
                        }
                        if (parsed.table.looksLikeSwitch) {
                            issues += IntegrityIssue(
                                IssueSeverity.OK,
                                "Disposition Switch reconnue",
                                "${parsed.table.knownPartitionCount} partitions eMMC standards détectées.",
                            )
                        } else {
                            issues += IntegrityIssue(
                                IssueSeverity.WARNING,
                                "Disposition Switch non confirmée",
                                "La GPT est valide mais ne correspond pas au jeu de partitions eMMC attendu.",
                            )
                        }
                    }
                    is GptParser.ParseResult.Failure -> {
                        gptError = parsed.reason
                        issues += IntegrityIssue(
                            IssueSeverity.ERROR,
                            "Table GPT illisible",
                            "${parsed.reason}. Une image NAND brute commence par une GPT au secteur 1.",
                        )
                    }
                }

                if (splitSet != null) {
                    onProgress(0.85f, "Vérification du jeu découpé")
                    issues += splitIssues(splitSet, gpt, source.size)
                    if (splitSet.warnings.isNotEmpty()) {
                        splitSet.warnings.forEach { notes += it }
                    }
                }
            }
        }

        onProgress(0.9f, "Calcul des empreintes")
        if (isCancelled()) throw CancellationException("Analyse annulée")
        val headHash = if (source.size > 0) {
            val length = minOf(source.size, SAMPLE_BYTES.toLong()).toInt()
            sha256(source.read(0, length))
        } else {
            null
        }

        if (detected.kind == NandFileKind.RAWNAND || detected.kind == NandFileKind.HEKATE_SPLIT_PART ||
            detected.kind == NandFileKind.EMUMMC_PART
        ) {
            if (!NandSize.isSectorAligned(source.size)) {
                issues += IntegrityIssue(
                    IssueSeverity.ERROR,
                    "Taille non alignée",
                    "La taille n'est pas un multiple de 512 octets : image tronquée ou corrompue.",
                )
            }
        }

        onProgress(1f, "Analyse terminée")
        return NandAnalysis(
            sourceLabel = name,
            sourceSize = source.size,
            kind = detected.kind,
            kindDetail = detected.detail,
            capacity = NandSize.classify(source.size),
            headSha256 = headHash,
            gpt = gpt,
            gptError = gptError,
            partitions = partitions,
            prodinfo = prodinfo,
            prodinfoVerification = prodinfoVerification,
            keys = keys,
            boot = boot,
            split = splitSet,
            issues = issues,
            notes = notes,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    private fun scanPartitions(
        source: RandomAccessSource,
        table: GptParser.Table,
        onProgress: (Float, String) -> Unit,
    ): List<PartitionScan> {
        val result = mutableListOf<PartitionScan>()
        table.partitions.forEachIndexed { position, partition ->
            if (!partition.validBounds || partition.offsetBytes >= source.size) {
                result += PartitionScan(
                    name = partition.name,
                    index = partition.index,
                    startBytes = partition.offsetBytes,
                    sizeBytes = partition.sizeBytes,
                    typeGuid = partition.typeGuid,
                    firstBytes = "—",
                    signature = "hors de l'image",
                    sampleSha256 = null,
                )
                return@forEachIndexed
            }
            val available = minOf(partition.sizeBytes, source.size - partition.offsetBytes)
            val probeLength = minOf(available, 4096L).toInt()
            val bytes = runCatching { source.read(partition.offsetBytes, probeLength) }.getOrNull()
            result += PartitionScan(
                name = partition.name,
                index = partition.index,
                startBytes = partition.offsetBytes,
                sizeBytes = partition.sizeBytes,
                typeGuid = partition.typeGuid,
                firstBytes = bytes?.take(16)?.toByteArray()?.toHex() ?: "—",
                signature = bytes?.let { PartitionProbe.describe(it) } ?: "lecture impossible",
                sampleSha256 = bytes?.let { sha256(it) },
            )
            onProgress(0.4f + 0.4f * (position + 1) / table.partitions.size, "Partition ${partition.name}")
        }
        return result
    }

    private fun checkProdinfoPartition(
        source: RandomAccessSource,
        table: GptParser.Table,
        issues: MutableList<IntegrityIssue>,
        notes: MutableList<String>,
    ): Pair<ProdinfoEngine.Summary, ProdinfoEngine.Verification>? {
        val partition = table.partitions.firstOrNull { it.name.equals("PRODINFO", ignoreCase = true) }
            ?: return null
        if (partition.offsetBytes >= source.size) return null
        return runCatching {
            val length = minOf(partition.sizeBytes, PRODINFO_MAX.toLong(), source.size - partition.offsetBytes).toInt()
            val bytes = source.read(partition.offsetBytes, length)
            if (bytes.size < 4 || String(bytes, 0, 4, Charsets.US_ASCII) != ProdinfoEngine.MAGIC) {
                issues += IntegrityIssue(
                    IssueSeverity.INFO,
                    "PRODINFO chiffré",
                    "La partition PRODINFO de cette image n'est pas déchiffrée : le modèle et le numéro " +
                        "de série ne peuvent pas être lus sans les clés de la console.",
                )
                return null
            }
            val engine = ProdinfoEngine()
            when (val loaded = engine.load(bytes, "PRODINFO")) {
                is ProdinfoEngine.LoadResult.Success -> {
                    issues += verificationIssues(engine.verify())
                    loaded.summary to engine.verify()
                }
                is ProdinfoEngine.LoadResult.Failure -> {
                    notes += loaded.message
                    null
                }
            }
        }.getOrNull()
    }

    private fun verificationIssues(verification: ProdinfoEngine.Verification): List<IntegrityIssue> =
        verification.checks.map { check ->
            IntegrityIssue(
                severity = when (check.severity) {
                    ProdinfoEngine.Severity.OK -> IssueSeverity.OK
                    ProdinfoEngine.Severity.WARNING -> IssueSeverity.WARNING
                    ProdinfoEngine.Severity.ERROR -> IssueSeverity.ERROR
                },
                title = "PRODINFO · ${check.name}",
                detail = check.detail,
            )
        }

    private fun gptIssues(table: GptParser.Table, source: RandomAccessSource): List<IntegrityIssue> {
        val issues = mutableListOf<IntegrityIssue>()
        issues += if (table.header.headerCrcValid) {
            IntegrityIssue(IssueSeverity.OK, "CRC32 en-tête GPT", "Valide.")
        } else {
            IntegrityIssue(IssueSeverity.ERROR, "CRC32 en-tête GPT", "Invalide : en-tête altéré.")
        }
        issues += if (table.header.entryArrayCrcValid) {
            IntegrityIssue(IssueSeverity.OK, "CRC32 table de partitions", "Valide.")
        } else {
            IntegrityIssue(IssueSeverity.ERROR, "CRC32 table de partitions", "Invalide : entrées altérées.")
        }

        val declared = table.header.imageSizeBytes
        if (source.size != declared) {
            issues += IntegrityIssue(
                IssueSeverity.ERROR,
                "Taille incohérente avec la GPT",
                "Image de ${NandSize.human(source.size)} mais la GPT déclare ${NandSize.human(declared)} " +
                    "(dernier LBA ${table.header.backupLba}).",
            )
        } else {
            issues += IntegrityIssue(
                IssueSeverity.OK,
                "Taille cohérente avec la GPT",
                "${NandSize.human(source.size)} (dernier LBA ${table.header.backupLba}).",
            )
        }

        val outOfBounds = table.partitions.filter {
            it.offsetBytes + it.sizeBytes > source.size || !it.validBounds
        }
        if (outOfBounds.isNotEmpty()) {
            issues += IntegrityIssue(
                IssueSeverity.ERROR,
                "Partitions hors limites",
                outOfBounds.joinToString(", ") { it.name } + " dépasse(nt) la taille de l'image.",
            )
        }

        val sorted = table.partitions.sortedBy { it.firstLba }
        val overlaps = sorted.zipWithNext().filter { (first, second) -> second.firstLba <= first.lastLba }
        if (overlaps.isNotEmpty()) {
            issues += IntegrityIssue(
                IssueSeverity.ERROR,
                "Chevauchement de partitions",
                overlaps.joinToString(", ") { (first, second) -> "${first.name}/${second.name}" },
            )
        }

        return issues
    }

    private fun checkBackupGpt(
        source: RandomAccessSource,
        table: GptParser.Table,
        issues: MutableList<IntegrityIssue>,
    ) {
        val backupOffset = table.header.backupLba * GptParser.SECTOR_SIZE
        if (backupOffset + GptParser.SECTOR_SIZE > source.size) {
            issues += IntegrityIssue(
                IssueSeverity.ERROR,
                "GPT de secours hors de l'image",
                "L'image est plus courte que le dernier LBA déclaré.",
            )
            return
        }
        val backup = runCatching { source.read(backupOffset, GptParser.SECTOR_SIZE) }.getOrNull()
        if (backup == null) {
            issues += IntegrityIssue(IssueSeverity.WARNING, "GPT de secours", "Lecture impossible.")
            return
        }
        val parsed = GptParser.parseHeader(backup)
        when (parsed) {
            is GptParser.ParseResult.Success -> {
                val sameDisk = parsed.table.header.diskGuid == table.header.diskGuid
                if (sameDisk && parsed.table.header.headerCrcValid) {
                    issues += IntegrityIssue(
                        IssueSeverity.OK,
                        "GPT de secours",
                        "En-tête de secours valide et identique au disque ${table.header.diskGuid}.",
                    )
                } else {
                    issues += IntegrityIssue(
                        IssueSeverity.WARNING,
                        "GPT de secours incohérente",
                        "Le disque ou le CRC de l'en-tête de secours ne correspond pas à l'en-tête primaire.",
                    )
                }
            }
            is GptParser.ParseResult.Failure -> issues += IntegrityIssue(
                IssueSeverity.WARNING,
                "GPT de secours illisible",
                parsed.reason,
            )
        }
    }

    private fun splitIssues(
        set: SplitNandDetector.SplitSet,
        gpt: GptParser.Table?,
        totalSize: Long,
    ): List<IntegrityIssue> {
        val issues = mutableListOf<IntegrityIssue>()
        issues += IntegrityIssue(
            IssueSeverity.OK,
            "Jeu découpé complet",
            "${set.partNames.size} parties, ${NandSize.human(set.totalSize)} au total (${set.format.label}).",
        )
        if (!SplitNandDetector.isHekateChunkSize(set.chunkSize) && set.format == SplitNandDetector.Format.HEKATE_SPLIT) {
            issues += IntegrityIssue(
                IssueSeverity.WARNING,
                "Taille de partie inhabituelle",
                "Chaque partie fait ${NandSize.human(set.chunkSize)} ; Hekate utilise ~4 Gio par partie.",
            )
        }
        if (gpt != null && gpt.header.imageSizeBytes != totalSize) {
            issues += IntegrityIssue(
                IssueSeverity.ERROR,
                "Jeu découpé incohérent",
                "La somme des parties (${NandSize.human(totalSize)}) diffère de la taille déclarée par la GPT " +
                    "(${NandSize.human(gpt.header.imageSizeBytes)}).",
            )
        }
        if (set.format == SplitNandDetector.Format.EMUMMC_SD_FILES && !(set.hasBoot0 && set.hasBoot1)) {
            issues += IntegrityIssue(
                IssueSeverity.WARNING,
                "BOOT0/BOOT1 manquants",
                "Un emuMMC en fichiers SD exige BOOT0 et BOOT1 dans le même dossier.",
            )
        }
        return issues
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}

/** Identifies the leading structure of a partition without pretending to mount it. */
object PartitionProbe {

    fun describe(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "vide"
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == ProdinfoEngine.MAGIC) {
            return "PRODINFO déchiffré (CAL0)"
        }
        if (bytes.size >= 8 && String(bytes, 0, 8, Charsets.US_ASCII) == "EFI PART") {
            return "en-tête GPT"
        }
        if (bytes.size >= 11 && String(bytes, 3, 8, Charsets.US_ASCII) == "EXFAT   ") {
            return "système de fichiers exFAT"
        }
        if (bytes.size >= 11 && String(bytes, 3, 8, Charsets.US_ASCII) == "NTFS    ") {
            return "système de fichiers NTFS"
        }
        if (bytes.size >= 90 && String(bytes, 82, 8, Charsets.US_ASCII).startsWith("FAT32")) {
            return "système de fichiers FAT32"
        }
        if (bytes.size >= 62) {
            val label = String(bytes, 54, 8, Charsets.US_ASCII)
            if (label.startsWith("FAT1") || label.startsWith("FAT")) {
                return "système de fichiers FAT"
            }
        }
        if (bytes.all { it == 0.toByte() }) return "zone vide (0x00)"
        if (bytes.all { it == 0xFF.toByte() }) return "zone effacée (0xFF)"
        return "données non formatées"
    }
}
