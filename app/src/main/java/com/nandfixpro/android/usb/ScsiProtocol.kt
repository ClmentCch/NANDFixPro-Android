package com.nandfixpro.android.usb

import java.util.Locale

/**
 * SCSI command set used by USB Mass Storage Bulk-Only Transport (BOT).
 *
 * This file contains no Android dependency so the CBW/CSW encoding and response
 * parsing can be verified by JVM unit tests. The original NANDFixPro relies on the
 * operating system to mount Hekate's UMS target; Android has no UMS driver, so the
 * Android port issues the SCSI commands directly over USB Host API.
 */
object ScsiProtocol {

    const val OP_TEST_UNIT_READY = 0x00
    const val OP_REQUEST_SENSE = 0x03
    const val OP_INQUIRY = 0x12
    const val OP_MODE_SENSE_6 = 0x1A
    const val OP_READ_CAPACITY_10 = 0x25
    const val OP_READ_10 = 0x28
    const val OP_READ_16 = 0x88
    const val OP_READ_CAPACITY_16 = 0x9E

    const val CBW_SIGNATURE = 0x43425355
    const val CSW_SIGNATURE = 0x53425355

    const val STATUS_PASSED = 0
    const val STATUS_FAILED = 1
    const val STATUS_PHASE_ERROR = 2

    data class Capacity(val lastLba: Long, val blockSize: Int) {
        val totalBytes: Long get() = (lastLba + 1) * blockSize
        val totalSectors: Long get() = lastLba + 1
    }

    data class Inquiry(
        val peripheralType: Int,
        val vendor: String,
        val product: String,
        val revision: String,
    ) {
        val removalMedia: Boolean get() = (peripheralType and 0x80) != 0
        val displayName: String get() = listOf(vendor, product).filter { it.isNotBlank() }.joinToString(" ").trim()
    }

    data class Csw(val signature: Long, val tag: Long, val residue: Int, val status: Int) {
        val passed: Boolean get() = status == STATUS_PASSED && residue == 0
        val statusText: String
            get() = when (status) {
                STATUS_PASSED -> "réussie"
                STATUS_FAILED -> "échec"
                STATUS_PHASE_ERROR -> "erreur de phase"
                else -> "statut inconnu ($status)"
            }
    }

    data class Sense(val key: Int, val additionalCode: Int, val description: String)

    // ------------------------------------------------------------------ CDBs

    fun testUnitReady(): ByteArray = ByteArray(6)

    fun requestSense(allocationLength: Int = 18): ByteArray = ByteArray(6).apply {
        this[0] = OP_REQUEST_SENSE.toByte()
        this[4] = allocationLength.coerceIn(0, 0xFF).toByte()
    }

    fun inquiry(allocationLength: Int = 96): ByteArray = ByteArray(6).apply {
        this[0] = OP_INQUIRY.toByte()
        this[1] = 0 // EVPD = 0
        this[2] = 0 // page code
        this[3] = ((allocationLength shr 8) and 0xFF).toByte()
        this[4] = (allocationLength and 0xFF).toByte()
    }

    fun readCapacity10(): ByteArray = ByteArray(10).apply {
        this[0] = OP_READ_CAPACITY_10.toByte()
    }

    fun readCapacity16(): ByteArray = ByteArray(16).apply {
        this[0] = OP_READ_CAPACITY_16.toByte()
        this[1] = 0x10 // SERVICE ACTION = READ CAPACITY(16)
        this[10] = 32 // allocation length
    }

    fun read10(lba: Long, blocks: Int): ByteArray = ByteArray(10).apply {
        this[0] = OP_READ_10.toByte()
        putInt32BigEndian(this, 2, lba)
        this[7] = ((blocks shr 8) and 0xFF).toByte()
        this[8] = (blocks and 0xFF).toByte()
    }

    fun read16(lba: Long, blocks: Int): ByteArray = ByteArray(16).apply {
        this[0] = OP_READ_16.toByte()
        putInt64BigEndian(this, 2, lba)
        this[10] = ((blocks shr 24) and 0xFF).toByte()
        this[11] = ((blocks shr 16) and 0xFF).toByte()
        this[12] = ((blocks shr 8) and 0xFF).toByte()
        this[13] = (blocks and 0xFF).toByte()
    }

    fun modeSense6(page: Int = 0x3F, allocationLength: Int = 192): ByteArray = ByteArray(6).apply {
        this[0] = OP_MODE_SENSE_6.toByte()
        this[2] = page.toByte()
        this[4] = allocationLength.coerceIn(0, 0xFF).toByte()
    }

    // ------------------------------------------------------------ transport

    /** 31-byte Command Block Wrapper, little-endian header. */
    fun buildCommandBlockWrapper(
        tag: Int,
        transferLength: Int,
        directionIn: Boolean,
        lun: Int = 0,
        cdb: ByteArray,
    ): ByteArray {
        require(cdb.size <= 16) { "CDB limité à 16 octets" }
        require(lun in 0..15) { "LUN invalide" }
        val cbw = ByteArray(31)
        putInt32LittleEndian(cbw, 0, CBW_SIGNATURE)
        putInt32LittleEndian(cbw, 4, tag)
        putInt32LittleEndian(cbw, 8, transferLength)
        cbw[12] = if (directionIn) 0x80.toByte() else 0x00
        cbw[13] = lun.toByte()
        cbw[14] = cdb.size.toByte()
        cdb.copyInto(cbw, 15)
        return cbw
    }

    /** 13-byte Command Status Wrapper. */
    fun parseCommandStatusWrapper(bytes: ByteArray, expectedTag: Int? = null): Result<Csw> {
        if (bytes.size < 13) {
            return Result.failure(IllegalArgumentException("CSW incomplet (${bytes.size} octets)"))
        }
        val signature = readInt32LittleEndian(bytes, 0)
        if (signature != CSW_SIGNATURE.toLong()) {
            return Result.failure(IllegalStateException("Signature CSW invalide: 0x%08X".format(signature)))
        }
        val tag = readInt32LittleEndian(bytes, 4)
        if (expectedTag != null && (tag.toInt() != expectedTag)) {
            return Result.failure(IllegalStateException("Tag CSW incohérent"))
        }
        val residue = readInt32LittleEndian(bytes, 8).toInt()
        val status = bytes[12].toInt() and 0xFF
        if (status !in 0..2) {
            return Result.failure(IllegalStateException("Statut CSW inconnu: $status"))
        }
        return Result.success(Csw(signature, tag, residue, status))
    }

    // -------------------------------------------------------------- parsing

    fun parseCapacity10(bytes: ByteArray): Result<Capacity> {
        if (bytes.size < 8) return Result.failure(IllegalArgumentException("Réponse READ CAPACITY(10) trop courte"))
        val lastLba = readInt32BigEndian(bytes, 0)
        val blockSize = readInt32BigEndian(bytes, 4)
        if (blockSize <= 0 || blockSize > 0x10000) {
            return Result.failure(IllegalStateException("Taille de bloc invalide: $blockSize"))
        }
        return Result.success(Capacity(lastLba, blockSize.toInt()))
    }

    fun parseCapacity16(bytes: ByteArray): Result<Capacity> {
        if (bytes.size < 12) return Result.failure(IllegalArgumentException("Réponse READ CAPACITY(16) trop courte"))
        val lastLba = readInt64BigEndian(bytes, 0)
        val blockSize = readInt32BigEndian(bytes, 8)
        if (blockSize <= 0 || blockSize > 0x10000) {
            return Result.failure(IllegalStateException("Taille de bloc invalide: $blockSize"))
        }
        return Result.success(Capacity(lastLba, blockSize.toInt()))
    }

    fun parseInquiry(bytes: ByteArray): Result<Inquiry> {
        if (bytes.size < 36) return Result.failure(IllegalArgumentException("Réponse INQUIRY trop courte"))
        val peripheral = bytes[0].toInt() and 0x1F
        fun field(offset: Int, length: Int) =
            String(bytes, offset, length, Charsets.US_ASCII).trim().trim('\u0000')
        val inquiry = Inquiry(
            peripheralType = bytes[0].toInt() and 0xFF,
            vendor = field(8, 8),
            product = field(16, 16),
            revision = field(32, 4),
        )
        if (inquiry.vendor.isEmpty() && inquiry.product.isEmpty() && peripheral == 0x1F) {
            return Result.failure(IllegalStateException("Réponse INQUIRY non exploitable"))
        }
        return Result.success(inquiry)
    }

    fun parseSense(bytes: ByteArray): Sense {
        if (bytes.size < 3) return Sense(0, 0, "réponse de sense vide")
        val responseCode = bytes[0].toInt() and 0x7F
        val key = bytes[2].toInt() and 0x0F
        val additional = if (bytes.size > 12) bytes[12].toInt() and 0xFF else 0
        val description = when (key) {
            0x0 -> "aucune erreur"
            0x1 -> "erreur récupérée"
            0x2 -> "non prêt"
            0x3 -> "erreur média"
            0x4 -> "erreur matérielle"
            0x5 -> "requête illégale"
            0x6 -> "unité attention requise"
            0x7 -> "protection des données"
            0xB -> "commande abandonnée"
            else -> "clé de sense 0x%X".format(key)
        }
        return Sense(key, additional, if (responseCode == 0x70 || responseCode == 0x71) description else description)
    }

    fun describeCapacity(capacity: Capacity): String =
        String.format(
            Locale.US,
            "%d secteurs de %d octets (%.2f Gio)",
            capacity.totalSectors,
            capacity.blockSize,
            capacity.totalBytes / (1024.0 * 1024.0 * 1024.0),
        )

    // -------------------------------------------------------------- helpers

    fun putInt32LittleEndian(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    fun putInt32BigEndian(buffer: ByteArray, offset: Int, value: Long) {
        val v = value.toInt()
        buffer[offset] = ((v ushr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        buffer[offset + 3] = (v and 0xFF).toByte()
    }

    fun putInt64BigEndian(buffer: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            buffer[offset + i] = ((value ushr (56 - 8 * i)) and 0xFF).toByte()
        }
    }

    fun readInt32LittleEndian(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 3 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }

    fun readInt32BigEndian(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 4) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }

    fun readInt64BigEndian(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
