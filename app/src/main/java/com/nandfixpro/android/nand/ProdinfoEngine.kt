package com.nandfixpro.android.nand

import java.security.MessageDigest
import java.util.Locale

/**
 * PRODINFO (CAL0) engine ported from NANDFixPro's Python `ProdinfoEngine`.
 *
 * The block map, CRC16 algorithm and SHA-256 verification range are kept identical
 * to the original implementation so analysis results stay comparable between the
 * Windows and Android tools. The Android app never writes to a live console: edited
 * data can only be exported to a new local file chosen by the user.
 */
class ProdinfoEngine {

    enum class Severity { OK, WARNING, ERROR }

    data class Check(val name: String, val severity: Severity, val detail: String)

    data class Verification(val valid: Boolean, val checks: List<Check>) {
        val errors: Int get() = checks.count { it.severity == Severity.ERROR }
        val warnings: Int get() = checks.count { it.severity == Severity.WARNING }
    }

    data class Summary(
        val fileSize: Int,
        val declaredBodySize: Long,
        val updateCount: Int,
        val productModelId: Int,
        val modelName: String,
        val serialNumber: String,
        val wifiRegion: String,
        val wifiMac: String,
        val bluetoothMac: String,
        val configurationId: String,
        val colors: Map<String, String>,
    )

    sealed interface LoadResult {
        data class Success(val summary: Summary) : LoadResult
        data class Failure(val message: String) : LoadResult
    }

    data class Edit(val serial: String? = null, val wifiRegion: String? = null, val color: String? = null)

    private var data: ByteArray? = null
    var fileName: String? = null
        private set

    val isLoaded: Boolean get() = data != null

    fun load(bytes: ByteArray, name: String): LoadResult {
        if (bytes.size < 0x1000) {
            return LoadResult.Failure("Fichier trop petit pour être un PRODINFO valide (${bytes.size} octets)")
        }
        if (bytes.size < 4 || String(bytes, 0, 4, Charsets.US_ASCII) != MAGIC) {
            return LoadResult.Failure("Signature CAL0 absente. Sélectionnez un PRODINFO déchiffré.")
        }
        data = bytes.copyOf()
        fileName = name
        return LoadResult.Success(summarize(bytes))
    }

    fun unload() {
        data = null
        fileName = null
    }

    fun summary(): Summary = data?.let { summarize(it) }
        ?: throw IllegalStateException("Aucun PRODINFO chargé")

    private fun summarize(bytes: ByteArray): Summary {
        val colors = BLOCK_COLORS.associateWith { block ->
            val (offset, _) = BLOCKS.getValue(block)
            if (bytes.size >= offset + 3) {
                bytes.copyOfRange(offset, offset + 3).toHex()
            } else {
                "000000"
            }
        }
        return Summary(
            fileSize = bytes.size,
            declaredBodySize = declaredBodySize(bytes),
            updateCount = readLe16(bytes, 0x10),
            productModelId = productModelId(bytes),
            modelName = modelName(productModelId(bytes)),
            serialNumber = readSerial(bytes),
            wifiRegion = readWifiRegion(bytes),
            wifiMac = readMac(bytes, 0x210),
            bluetoothMac = readMac(bytes, 0x220),
            configurationId = if (bytes.size >= 0x60) bytes.copyOfRange(0x40, 0x60).toHex() else "",
            colors = colors,
        )
    }

    // ---------------------------------------------------------------- verification

    fun verify(): Verification {
        val bytes = data ?: return Verification(
            false,
            listOf(Check("Chargement", Severity.ERROR, "Aucun PRODINFO chargé")),
        )
        val results = mutableListOf<Check>()
        val bodySize = declaredBodySize(bytes)
        results += Check(
            "En-tête",
            Severity.OK,
            "body_size=${bodySize} octets (0x${bodySize.toString(16).uppercase()}), fichier=${bytes.size} octets",
        )

        // 1. Header CRC16 over bytes 0x00..0x1D, stored at 0x1E.
        val storedHeader = readLe16(bytes, 0x1E)
        val computedHeader = calculateCrc16(bytes, 0, 0x1E)
        results += if (storedHeader == computedHeader) {
            Check("CRC16 en-tête", Severity.OK, "VALIDE (0x%04X)".format(computedHeader))
        } else {
            Check(
                "CRC16 en-tête",
                Severity.ERROR,
                "INVALIDE (stocké 0x%04X, calculé 0x%04X)".format(storedHeader, computedHeader),
            )
        }

        // 2. Body SHA-256 over exactly body_size bytes starting at 0x40.
        val storedHash = bytes.copyOfRange(0x20, 0x40)
        val computedHash = computeBodySha256(bytes)
        results += if (computedHash == null) {
            Check("SHA-256 corps", Severity.ERROR, "Calcul impossible (taille d'en-tête invalide)")
        } else if (storedHash.contentEquals(computedHash)) {
            Check(
                "SHA-256 corps",
                Severity.OK,
                "VALIDE sur %d octets (0x40 → 0x%X)".format(bodySize, 0x40 + bodySize),
            )
        } else {
            Check(
                "SHA-256 corps",
                Severity.ERROR,
                "INVALIDE — Atmosphère signalerait INVALID_PRODINFO\n  stocké:   ${storedHash.toHex()}\n  calculé:  ${computedHash.toHex()}",
            )
        }

        // 3. Per-block CRC16.
        for (block in CRC_BLOCKS) {
            val (offset, size) = BLOCKS.getValue(block)
            if (offset + size > bytes.size) {
                results += Check(block, Severity.WARNING, "Bloc hors du fichier (tronqué ?)")
                continue
            }
            val stored = readLe16(bytes, offset + size - 2)
            val computed = calculateCrc16(bytes, offset, size - 2)
            if ((block == BLOCK_BEZEL || block == BLOCK_MAIN) &&
                bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte() &&
                bytes[offset + 2] == 0.toByte() && stored == 0
            ) {
                results += Check(block, Severity.WARNING, "NON INITIALISÉ (couleur 000000) — normal sur certains dumps")
                continue
            }
            results += if (stored == computed) {
                Check(block, Severity.OK, "VALIDE (0x%04X)".format(computed))
            } else {
                Check(
                    block,
                    Severity.ERROR,
                    "INVALIDE (stocké 0x%04X, calculé 0x%04X)".format(stored, computed),
                )
            }
        }

        return Verification(valid = results.none { it.severity == Severity.ERROR }, checks = results)
    }

    /**
     * Recomputes every checksum exactly like the original tool. Used only when the
     * user exports an edited copy to a new file.
     */
    fun applyEditsAndRecalculate(edit: Edit): Result<ByteArray> {
        val current = data ?: return Result.failure(IllegalStateException("Aucun PRODINFO chargé"))
        val copy = current.copyOf()
        if (copy.size < 0x8002) {
            return Result.failure(IllegalArgumentException("PRODINFO trop petit pour recalculer les sommes de contrôle"))
        }

        edit.serial?.let { serial ->
            if (serial.length != 14 || !serial.all { it.code in 0x20..0x7E }) {
                return Result.failure(IllegalArgumentException("Le numéro de série doit contenir exactement 14 caractères ASCII"))
            }
            for (i in 0 until 30) copy[0x250 + i] = 0
            serial.toByteArray(Charsets.US_ASCII).copyInto(copy, 0x250)
        }

        edit.wifiRegion?.let { region ->
            val code = REGION_CODES[region]
                ?: return Result.failure(IllegalArgumentException("Région WiFi inconnue: $region"))
            code.copyInto(copy, 0x88)
        }

        edit.color?.let { hex ->
            val normalized = hex.removePrefix("#").uppercase(Locale.ROOT)
            if (normalized.length != 6 || normalized.any { it !in "0123456789ABCDEF" }) {
                return Result.failure(IllegalArgumentException("Couleur attendue au format RRGGBB"))
            }
            val (offset, _) = BLOCKS.getValue(BLOCK_BEZEL)
            if (copy.size < offset + 4) {
                return Result.failure(IllegalArgumentException("Bloc couleur hors du fichier"))
            }
            val rgb = ByteArray(3) { index ->
                normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            rgb.copyInto(copy, offset)
            copy[offset + 3] = 0xFF.toByte()
        }

        // Header update counter + header CRC.
        val updateCount = (readLe16(copy, 0x10) + 1) and 0xFFFF
        copy[0x10] = (updateCount and 0xFF).toByte()
        copy[0x11] = ((updateCount shr 8) and 0xFF).toByte()
        writeLe16(copy, 0x1E, calculateCrc16(copy, 0, 0x1E))

        // Block CRCs.
        for (block in CRC_BLOCKS) {
            val (offset, size) = BLOCKS.getValue(block)
            if (offset + size <= copy.size) {
                writeLe16(copy, offset + size - 2, calculateCrc16(copy, offset, size - 2))
            }
        }

        // Body hash.
        val sha = computeBodySha256(copy)
            ?: return Result.failure(IllegalStateException("Taille de corps invalide"))
        sha.copyInto(copy, 0x20)

        // Full first-block CRC at 0x8000.
        writeLe16(copy, 0x8000, calculateCrc16(copy, 0, 0x8000))
        return Result.success(copy)
    }

    // ---------------------------------------------------------------- helpers

    fun computeBodySha256(bytes: ByteArray): ByteArray? {
        val bodySize = declaredBodySize(bytes)
        if (bodySize <= 0) return null
        val available = bytes.size - 0x40
        val effective = if (bodySize > available) available.toLong() else bodySize
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes.copyOfRange(0x40, 0x40 + effective.toInt()))
    }

    fun declaredBodySize(bytes: ByteArray): Long {
        if (bytes.size < 0x0C) return 0
        return GptParser.readLe32(bytes, 0x08)
    }

    fun productModelId(bytes: ByteArray): Int {
        if (bytes.size < 0x3744) return 0
        return GptParser.readLe32(bytes, 0x3740).toInt()
    }

    fun modelName(id: Int): String = MODEL_NAMES[id] ?: "Modèle inconnu ($id)"

    private fun readSerial(bytes: ByteArray): String {
        if (bytes.size < 0x25E) return ""
        return String(bytes, 0x250, 14, Charsets.US_ASCII).trim('\u0000', ' ')
    }

    private fun readWifiRegion(bytes: ByteArray): String {
        if (bytes.size < 0x8C) return "Inconnue"
        val code = String(bytes, 0x88, 2, Charsets.US_ASCII)
        return REGION_CODES.entries.firstOrNull {
            String(it.value, 0, 2, Charsets.US_ASCII) == code
        }?.key ?: "Inconnue ($code)"
    }

    private fun readMac(bytes: ByteArray, offset: Int): String {
        if (bytes.size < offset + 6) return ""
        return (0 until 6).joinToString(":") { "%02X".format(bytes[offset + it]) }
    }

    companion object {
        const val MAGIC = "CAL0"
        const val BLOCK_BEZEL = "HousingBezelColor"
        const val BLOCK_MAIN = "HousingMainColor1"

        /** Block map copied verbatim from the original ProdinfoEngine. */
        val BLOCKS: Map<String, Pair<Int, Int>> = linkedMapOf(
            "ConfigurationId1" to (0x40 to 0x20),
            "WlanCountryCodes" to (0x80 to 0x190),
            "WlanMacAddress" to (0x210 to 0x08),
            "BtMacAddress" to (0x220 to 0x08),
            "SerialNumber" to (0x250 to 0x20),
            "ProductModel" to (0x3740 to 0x10),
            "ColorVariation" to (0x3750 to 0x10),
            "HousingBezelColor" to (0x4230 to 0x10),
            "HousingMainColor1" to (0x4240 to 0x10),
        )

        val CRC_BLOCKS: List<String> = listOf(
            "ConfigurationId1",
            "WlanCountryCodes",
            "WlanMacAddress",
            "BtMacAddress",
            "SerialNumber",
            "ProductModel",
            "ColorVariation",
            BLOCK_BEZEL,
            BLOCK_MAIN,
        )

        val BLOCK_COLORS: List<String> = listOf(BLOCK_BEZEL, BLOCK_MAIN, "ColorVariation")

        val MODEL_NAMES: Map<Int, String> = mapOf(
            1 to "Erista (V1 patchée / non patchée)",
            3 to "V2 (Mariko)",
            4 to "Lite",
            6 to "OLED",
        )

        val REGION_CODES: Map<String, ByteArray> = linkedMapOf(
            "Amérique" to byteArrayOf(0x52, 0x32, 0x00, 0x00),
            "Asie (Singapour)" to byteArrayOf(0x55, 0x31, 0x00, 0x00),
            "Asie (Malaisie)" to byteArrayOf(0x55, 0x34, 0x00, 0x00),
            "Australie" to byteArrayOf(0x55, 0x32, 0x00, 0x00),
            "Europe" to byteArrayOf(0x52, 0x31, 0x00, 0x00),
            "Japon" to byteArrayOf(0x54, 0x31, 0x00, 0x00),
        )

        private val CRC_TABLE = intArrayOf(
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
        )

        /** CRC16 implementation kept byte-for-byte compatible with the original. */
        fun calculateCrc16(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int {
            var crc = 0x55AA
            val end = (offset + length).coerceAtMost(buffer.size)
            for (index in offset until end) {
                val byte = buffer[index].toInt() and 0xFF
                var r = CRC_TABLE[crc and 0x0F]
                crc = ((crc shr 4) and 0x0FFF) xor r xor CRC_TABLE[byte and 0x0F]
                r = CRC_TABLE[crc and 0x0F]
                crc = ((crc shr 4) and 0x0FFF) xor r xor CRC_TABLE[(byte shr 4) and 0x0F]
            }
            return crc and 0xFFFF
        }

        fun readLe16(bytes: ByteArray, offset: Int): Int {
            if (offset + 1 >= bytes.size) return 0
            return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }

        fun writeLe16(bytes: ByteArray, offset: Int, value: Int) {
            if (offset + 1 >= bytes.size) return
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
    }
}
