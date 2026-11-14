package com.nandfixpro.android.nand

import java.nio.charset.Charset
import java.util.zip.CRC32

/**
 * GUID Partition Table parsing for Nintendo Switch eMMC images.
 *
 * Layout mirrors the original NANDFixPro checks: sector size 512, primary header at
 * LBA 1, backup header at the last LBA. The backup LBA also gives the real size of
 * a raw NAND dump, which is how split backups are validated against their GPT.
 */
object GptParser {

    const val SECTOR_SIZE = 512
    private const val HEADER_LBA = 1L
    private const val SIGNATURE = "EFI PART"
    private const val MAX_ENTRIES = 512

    /** Partition names that identify a genuine Switch eMMC GPP layout. */
    val SWITCH_PARTITION_NAMES = setOf(
        "PRODINFO",
        "PRODINFOF",
        "BCPKG2-1-Boot",
        "BCPKG2-2-Normal",
        "BCPKG2-3-SafeMode",
        "SAFE",
        "SYSTEM",
        "USER",
    )

    data class Header(
        val revision: Int,
        val headerSize: Int,
        val headerCrc32: Long,
        val computedHeaderCrc32: Long,
        val currentLba: Long,
        val backupLba: Long,
        val firstUsableLba: Long,
        val lastUsableLba: Long,
        val diskGuid: String,
        val entryLba: Long,
        val entryCount: Int,
        val entrySize: Int,
        val entryArrayCrc32: Long,
        val computedEntryArrayCrc32: Long,
    ) {
        val headerCrcValid: Boolean = headerCrc32 == computedHeaderCrc32
        val entryArrayCrcValid: Boolean = entryArrayCrc32 == computedEntryArrayCrc32

        /** Total image size implied by the backup header location. */
        val imageSizeBytes: Long get() = (backupLba + 1) * SECTOR_SIZE
    }

    data class Partition(
        val index: Int,
        val name: String,
        val typeGuid: String,
        val uniqueGuid: String,
        val firstLba: Long,
        val lastLba: Long,
        val attributes: Long,
    ) {
        val offsetBytes: Long get() = firstLba * SECTOR_SIZE
        val sizeBytes: Long get() = (lastLba - firstLba + 1) * SECTOR_SIZE
        val validBounds: Boolean get() = firstLba > 0 && lastLba >= firstLba
    }

    data class Table(
        val header: Header,
        val partitions: List<Partition>,
        val totalSectors: Long,
    ) {
        val partitionNames: Set<String> = partitions.map { it.name }.toSet()
        val looksLikeSwitch: Boolean =
            partitionNames.containsAll(listOf("PRODINFO", "SYSTEM", "USER")) ||
                partitionNames.count { it in SWITCH_PARTITION_NAMES } >= 5
        val knownPartitionCount: Int = partitions.count { it.name in SWITCH_PARTITION_NAMES }
        val gapBytes: Long
            get() {
                var cursor = 0L
                var gaps = 0L
                partitions.sortedBy { it.firstLba }.forEach { partition ->
                    if (partition.firstLba * SECTOR_SIZE > cursor) {
                        gaps += partition.firstLba * SECTOR_SIZE - cursor
                    }
                    cursor = maxOf(cursor, (partition.lastLba + 1) * SECTOR_SIZE)
                }
                val end = totalSectors * SECTOR_SIZE
                if (end > cursor) gaps += end - cursor
                return gaps
            }
    }

    sealed interface ParseResult {
        data class Success(val table: Table) : ParseResult
        data class Failure(val reason: String) : ParseResult
    }

    fun parse(source: RandomAccessSource): ParseResult {
        if (source.size < (HEADER_LBA + 1) * SECTOR_SIZE) {
            return ParseResult.Failure("Image trop petite pour contenir un en-tête GPT")
        }
        val headerBytes = try {
            source.read(HEADER_LBA * SECTOR_SIZE, SECTOR_SIZE)
        } catch (error: Exception) {
            return ParseResult.Failure("Lecture de l'en-tête GPT impossible: ${error.message}")
        }
        val parsed = parseHeader(headerBytes, source)
        if (parsed is ParseResult.Success && parsed.table.header.currentLba != HEADER_LBA) {
            return ParseResult.Failure(
                "En-tête GPT primaire attendu au LBA 1, trouvé au LBA ${parsed.table.header.currentLba}",
            )
        }
        return parsed
    }

    fun parseHeader(headerBytes: ByteArray, source: RandomAccessSource? = null): ParseResult {
        if (headerBytes.size < SECTOR_SIZE) {
            return ParseResult.Failure("En-tête GPT incomplet")
        }
        if (String(headerBytes, 0, 8, Charsets.US_ASCII) != SIGNATURE) {
            return ParseResult.Failure("Signature GPT absente (attendu: \"EFI PART\")")
        }
        val headerSize = readLe32(headerBytes, 12).toInt()
        if (headerSize !in 92..SECTOR_SIZE) {
            return ParseResult.Failure("Taille d'en-tête GPT invalide: $headerSize")
        }
        val storedCrc = readLe32(headerBytes, 16)
        val headerCopy = headerBytes.copyOfRange(0, headerSize)
        for (i in 16 until 20) headerCopy[i] = 0
        val computedCrc = crc32(headerCopy)

        val revision = readLe32(headerBytes, 8).toInt()
        val currentLba = readLe64(headerBytes, 24)
        val backupLba = readLe64(headerBytes, 32)
        val firstUsable = readLe64(headerBytes, 40)
        val lastUsable = readLe64(headerBytes, 48)
        val diskGuid = formatGuid(headerBytes.copyOfRange(56, 72))
        val entryLba = readLe64(headerBytes, 72)
        val entryCount = readLe32(headerBytes, 80).toInt()
        val entrySize = readLe32(headerBytes, 84).toInt()
        val entryCrc = readLe32(headerBytes, 88)

        if (entryCount !in 1..MAX_ENTRIES) {
            return ParseResult.Failure("Nombre d'entrées GPT invalide: $entryCount")
        }
        if (entrySize != 128 && entrySize !in 128..4096) {
            return ParseResult.Failure("Taille d'entrée GPT invalide: $entrySize")
        }
        if (backupLba < 1) {
            return ParseResult.Failure("LBA alternatif GPT invalide: $backupLba")
        }

        var computedEntryCrc = 0L
        val entries = mutableListOf<Partition>()
        if (source != null) {
            val arraySize = entryCount * entrySize
            if (entryLba * SECTOR_SIZE + arraySize > source.size) {
                return ParseResult.Failure("Table de partitions GPT hors de l'image")
            }
            val entryBytes = try {
                source.read(entryLba * SECTOR_SIZE, arraySize)
            } catch (error: Exception) {
                return ParseResult.Failure("Lecture de la table GPT impossible: ${error.message}")
            }
            computedEntryCrc = crc32(entryBytes)
            for (index in 0 until entryCount) {
                val start = index * entrySize
                val entry = entryBytes.copyOfRange(start, start + entrySize)
                if (entry.take(16).all { it == 0.toByte() }) continue
                entries += Partition(
                    index = index,
                    name = decodeName(entry.copyOfRange(56, 128)),
                    typeGuid = formatGuid(entry.copyOfRange(0, 16)),
                    uniqueGuid = formatGuid(entry.copyOfRange(16, 32)),
                    firstLba = readLe64(entry, 32),
                    lastLba = readLe64(entry, 40),
                    attributes = readLe64(entry, 48),
                )
            }
        }

        val header = Header(
            revision = revision,
            headerSize = headerSize,
            headerCrc32 = storedCrc,
            computedHeaderCrc32 = computedCrc,
            currentLba = currentLba,
            backupLba = backupLba,
            firstUsableLba = firstUsable,
            lastUsableLba = lastUsable,
            diskGuid = diskGuid,
            entryLba = entryLba,
            entryCount = entryCount,
            entrySize = entrySize,
            entryArrayCrc32 = entryCrc,
            computedEntryArrayCrc32 = computedEntryCrc,
        )

        return ParseResult.Success(
            Table(
                header = header,
                partitions = entries.sortedBy { it.firstLba },
                totalSectors = backupLba + 1,
            ),
        )
    }

    private fun decodeName(bytes: ByteArray): String {
        val text = String(bytes, Charset.forName("UTF-16LE"))
        return text.substringBefore('\u0000').trim()
    }

    fun formatGuid(bytes: ByteArray): String {
        if (bytes.size < 16) return ""
        fun hex(value: Byte) = "%02X".format(value.toInt() and 0xFF)
        // GPT stores the first three fields little-endian.
        return buildString {
            append(hex(bytes[3])); append(hex(bytes[2]))
            append(hex(bytes[1])); append(hex(bytes[0]))
            append('-')
            append(hex(bytes[5])); append(hex(bytes[4]))
            append('-')
            append(hex(bytes[7])); append(hex(bytes[6]))
            append('-')
            append(hex(bytes[8])); append(hex(bytes[9]))
            append('-')
            for (i in 10 until 16) append(hex(bytes[i]))
        }
    }

    fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    fun readLe32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    fun readLe64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }
}
