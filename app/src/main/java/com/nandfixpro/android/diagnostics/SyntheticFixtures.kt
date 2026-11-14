package com.nandfixpro.android.diagnostics

import com.nandfixpro.android.nand.GptParser
import com.nandfixpro.android.nand.ProdinfoEngine
import java.nio.charset.Charset

/**
 * Builds valid synthetic structures so the analysis engines can be exercised on a
 * phone without a console or a multi-gigabyte dump. The fixtures contain no real
 * console data.
 */
object SyntheticFixtures {

    val SWITCH_PARTITION_LAYOUT: List<String> = listOf(
        "PRODINFO", "PRODINFOF", "BCPKG2-1-Boot", "BCPKG2-2-Normal",
        "BCPKG2-3-SafeMode", "SAFE", "SYSTEM", "USER",
    )

    /**
     * GPT image whose size matches its backup header, as required by the split NAND
     * validation. Default: 4096 sectors = 2 MiB.
     */
    fun gptImage(
        totalSectors: Long = 4096,
        partitionNames: List<String> = SWITCH_PARTITION_LAYOUT,
    ): ByteArray {
        require(totalSectors >= 64) { "Image GPT trop petite" }
        val image = ByteArray((totalSectors * 512).toInt())
        val entryCount = 16
        val entrySize = 128
        val entryArray = ByteArray(entryCount * entrySize)

        var cursorLba = 40L
        val partitionGuids = mutableListOf<ByteArray>()
        partitionNames.forEachIndexed { index, name ->
            val typeGuid = ByteArray(16) { 0x10 }
            typeGuid[15] = index.toByte()
            val uniqueGuid = ByteArray(16) { 0x20 }
            uniqueGuid[15] = index.toByte()
            partitionGuids += typeGuid

            val entry = ByteArray(entrySize)
            typeGuid.copyInto(entry, 0)
            uniqueGuid.copyInto(entry, 16)
            putLe64(entry, 32, cursorLba)
            putLe64(entry, 40, cursorLba + 7)
            putLe64(entry, 48, 0)
            name.toByteArray(Charset.forName("UTF-16LE")).copyInto(entry, 56)
            entry.copyInto(entryArray, index * entrySize)
            cursorLba += 8
        }
        val entryCrc = GptParser.crc32(entryArray)
        entryArray.copyInto(image, 1024)

        fun buildHeader(currentLba: Long, backupLba: Long, entryLba: Long): ByteArray {
            val header = ByteArray(512)
            "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
            putLe32(header, 8, 0x00010000)
            putLe32(header, 12, 92)
            putLe64(header, 24, currentLba)
            putLe64(header, 32, backupLba)
            putLe64(header, 40, 34)
            putLe64(header, 48, totalSectors - 34)
            ByteArray(16) { 0x5A }.copyInto(header, 56)
            putLe64(header, 72, entryLba)
            putLe32(header, 80, entryCount)
            putLe32(header, 84, entrySize)
            putLe32(header, 88, entryCrc.toInt())
            val crc = GptParser.crc32(header.copyOfRange(0, 92))
            putLe32(header, 16, crc.toInt())
            return header
        }

        buildHeader(currentLba = 1, backupLba = totalSectors - 1, entryLba = 2)
            .copyInto(image, 512)
        buildHeader(currentLba = totalSectors - 1, backupLba = 1, entryLba = totalSectors - 5)
            .copyInto(image, ((totalSectors - 1) * 512).toInt())
        return image
    }

    /**
     * Valid decrypted PRODINFO with CRC16 blocks and a matching body SHA-256.
     * Uses a fictional serial so no console identity is present.
     */
    fun prodinfo(serial: String = "XAW10000000001", region: String = "Europe"): ByteArray {
        val bytes = ByteArray(0x8004)
        "CAL0".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        putLe32(bytes, 0x08, 0x7000)
        putLe16(bytes, 0x10, 1)

        // Deterministic block payloads.
        for ((index, block) in ProdinfoEngine.BLOCKS.entries.withIndex()) {
            if (block.key == "SerialNumber") continue
            val (offset, size) = block.value
            if (offset + size > bytes.size) continue
            for (i in 0 until size - 2) {
                bytes[offset + i] = ((index * 31 + i * 7) and 0xFF).toByte()
            }
        }
        putLe32(bytes, 0x3740, 3) // Mariko
        serial.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x250)
        // Colors: a real RGB value so the uninitialised warning is not triggered.
        bytes[0x4230] = 0x12
        bytes[0x4231] = 0x34
        bytes[0x4232] = 0x56
        bytes[0x4233] = 0xFF.toByte()
        bytes[0x4240] = 0x65
        bytes[0x4241] = 0x43
        bytes[0x4242] = 0x21
        bytes[0x4243] = 0xFF.toByte()

        ProdinfoEngine.REGION_CODES[region]?.copyInto(bytes, 0x88)
        // MAC blocks: set stable values so their CRCs are meaningful.
        for (i in 0 until 6) {
            bytes[0x210 + i] = (0x30 + i).toByte()
            bytes[0x220 + i] = (0x40 + i).toByte()
        }

        for (block in ProdinfoEngine.CRC_BLOCKS) {
            val (offset, size) = ProdinfoEngine.BLOCKS.getValue(block)
            if (offset + size <= bytes.size) {
                putLe16(bytes, offset + size - 2, ProdinfoEngine.calculateCrc16(bytes, offset, size - 2))
            }
        }
        putLe16(bytes, 0x1E, ProdinfoEngine.calculateCrc16(bytes, 0, 0x1E))

        val bodySize = GptParser.readLe32(bytes, 0x08)
        val shaSource = bytes.copyOfRange(0x40, (0x40 + bodySize).toInt().coerceAtMost(bytes.size))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(shaSource)
        digest.copyInto(bytes, 0x20)
        putLe16(bytes, 0x8000, ProdinfoEngine.calculateCrc16(bytes, 0, 0x8000))
        return bytes
    }

    /** 4 MiB zero-filled image that looks like an unformatted BOOT partition. */
    fun bootPartition(): ByteArray = ByteArray(4 * 1024 * 1024)

    fun putLe16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    fun putLe32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    fun putLe64(buffer: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            buffer[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }
}
