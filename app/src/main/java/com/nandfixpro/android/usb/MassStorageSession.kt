package com.nandfixpro.android.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.nandfixpro.android.nand.RandomAccessSource
import java.io.IOException

/**
 * Read-only USB Mass Storage session implementing the Bulk-Only Transport (BOT)
 * state machine.
 *
 * Android has no UMS kernel driver, so a Hekate target never appears as a mounted
 * volume. This class talks SCSI directly over the USB Host API:
 * CBW → data phase → CSW, with READ(10)/READ(16) as the only data-producing
 * commands. There is deliberately no WRITE command anywhere in this class: the
 * physical medium cannot be modified by the app.
 */
class MassStorageSession(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val lun: Int = 0,
    private val timeoutMs: Int = 5_000,
    private val logger: (String) -> Unit = {},
) : RandomAccessSource {

    private data class CommandOutcome(
        val success: Boolean,
        val data: ByteArray,
        val csw: ScsiProtocol.Csw?,
        val error: String?,
        val sense: ScsiProtocol.Sense? = null,
    )

    private var nextTag = 1
    private var consecutiveZeroReads = 0

    var inquiry: ScsiProtocol.Inquiry? = null
        private set

    var capacity: ScsiProtocol.Capacity? = null
        private set

    var initialized: Boolean = false
        private set

    private var claimed = false

    private val maxTransferBytes: Int =
        (bulkIn.maxPacketSize * 16).coerceIn(512, 16 * 1024)

    override val label: String = "USB UMS"
    override val size: Long get() = capacity?.totalBytes ?: 0L
    val blockSize: Int get() = capacity?.blockSize ?: 512

    /** TEST UNIT READY, INQUIRY and READ CAPACITY; safe to call once after opening. */
    fun initialize(): Result<Unit> {
        if (!claimed) {
            val claimedNow = runCatching { connection.claimInterface(usbInterface, true) }.getOrDefault(false)
            if (!claimedNow) {
                return Result.failure(IOException("Interface UMS verrouillée par le système (claim refusé)"))
            }
            claimed = true
        }

        var ready = execute(ScsiProtocol.testUnitReady(), 0, true)
        if (!ready.success) {
            val sense = requestSense()
            logger(
                "TEST UNIT READY: ${ready.error ?: "échec"} " +
                    (sense?.let { "· sense: ${it.description}" } ?: ""),
            )
            // Many bridges answer UNIT ATTENTION on the very first command.
            ready = execute(ScsiProtocol.testUnitReady(), 0, true)
            if (!ready.success) {
                return Result.failure(
                    IOException("Périphérique non prêt (${ready.error ?: "sans détail"})"),
                )
            }
        }

        val inquiryOutcome = execute(ScsiProtocol.inquiry(96), 96, true)
        if (!inquiryOutcome.success) {
            return Result.failure(IOException("INQUIRY a échoué: ${inquiryOutcome.error}"))
        }
        inquiry = ScsiProtocol.parseInquiry(inquiryOutcome.data).getOrElse {
            return Result.failure(IOException("Réponse INQUIRY illisible: ${it.message}"))
        }

        val capacity10 = execute(ScsiProtocol.readCapacity10(), 8, true)
        if (capacity10.success) {
            ScsiProtocol.parseCapacity10(capacity10.data).onSuccess { capacity = it }
        }
        if (capacity == null || capacity?.lastLba == 0xFFFFFFFFL) {
            val capacity16 = execute(ScsiProtocol.readCapacity16(), 32, true)
            if (capacity16.success) {
                ScsiProtocol.parseCapacity16(capacity16.data).onSuccess { capacity = it }
            }
        }

        val resolved = capacity
            ?: return Result.failure(IOException("Capacité illisible (READ CAPACITY a échoué)"))

        if (resolved.blockSize != 512 && resolved.blockSize != 4096) {
            logger("Taille de bloc inhabituelle: ${resolved.blockSize} octets")
        }
        initialized = true
        logger(
            "UMS initialisé · ${inquiry?.displayName ?: "périphérique inconnu"} · " +
                ScsiProtocol.describeCapacity(resolved),
        )
        return Result.success(Unit)
    }

    override fun read(offset: Long, length: Int): ByteArray {
        if (!initialized) throw IllegalStateException("Session UMS non initialisée")
        if (length < 0) throw IllegalArgumentException("Longueur négative")
        if (length == 0) return ByteArray(0)
        if (offset < 0 || offset + length > size) {
            throw IndexOutOfBoundsException(
                "Lecture hors du périphérique: 0x${offset.toString(16)} + $length > $size",
            )
        }

        val block = blockSize
        val firstBlock = offset / block
        val leading = (offset % block).toInt()
        val neededBytes = leading + length
        val totalBlocks = (neededBytes + block - 1) / block
        val buffer = ByteArray(totalBlocks * block)

        val blocksPerChunk = (maxTransferBytes / block).coerceAtLeast(1)
        var completed = 0L
        while (completed < totalBlocks) {
            val chunkBlocks = minOf(totalBlocks - completed, blocksPerChunk.toLong()).toInt()
            val lba = firstBlock + completed
            val cdb = if (lba + chunkBlocks <= 0xFFFFFFFFL && chunkBlocks <= 0xFFFF) {
                ScsiProtocol.read10(lba, chunkBlocks)
            } else {
                ScsiProtocol.read16(lba, chunkBlocks)
            }
            val expected = chunkBlocks * block
            val outcome = execute(
                cdb = cdb,
                transferLength = expected,
                directionIn = true,
                destination = buffer,
                destinationOffset = (completed * block).toInt(),
            )
            if (!outcome.success) {
                throw IOException(
                    "Lecture SCSI échouée au LBA $lba: ${outcome.error ?: "erreur inconnue"}" +
                        (outcome.sense?.let { " (${it.description})" } ?: ""),
                )
            }
            completed += chunkBlocks
        }

        return buffer.copyOfRange(leading, leading + length)
    }

    /** Reads raw sectors for diagnostics, bypassing the block cache. */
    fun readSectors(lba: Long, count: Int): ByteArray {
        val block = blockSize
        val outcome = execute(
            cdb = if (lba + count <= 0xFFFFFFFFL) ScsiProtocol.read10(lba, count) else ScsiProtocol.read16(lba, count),
            transferLength = count * block,
            directionIn = true,
        )
        if (!outcome.success) throw IOException("Lecture directe échouée: ${outcome.error}")
        return outcome.data
    }

    fun requestSense(): ScsiProtocol.Sense? {
        val outcome = execute(ScsiProtocol.requestSense(18), 18, true)
        if (!outcome.success) return null
        return ScsiProtocol.parseSense(outcome.data)
    }

    private fun execute(
        cdb: ByteArray,
        transferLength: Int,
        directionIn: Boolean,
        destination: ByteArray? = null,
        destinationOffset: Int = 0,
    ): CommandOutcome {
        val tag = nextTag++
        val cbw = ScsiProtocol.buildCommandBlockWrapper(tag, transferLength, directionIn, lun, cdb)
        val sent = connection.bulkTransfer(bulkOut, cbw, cbw.size, timeoutMs)
        if (sent != cbw.size) {
            recoverFromError()
            return CommandOutcome(
                success = false,
                data = ByteArray(0),
                csw = null,
                error = "Envoi du CBW incomplet ($sent/${cbw.size} octets)",
            )
        }

        var data: ByteArray = ByteArray(0)
        var received = 0
        if (transferLength > 0 && directionIn) {
            data = destination ?: ByteArray(transferLength)
            val limit = minOf(transferLength, data.size - destinationOffset)
            while (received < limit) {
                val chunk = minOf(maxTransferBytes, limit - received)
                val read = connection.bulkTransfer(
                    bulkIn,
                    data,
                    destinationOffset + received,
                    chunk,
                    timeoutMs,
                )
                if (read < 0) {
                    recoverFromError()
                    return CommandOutcome(
                        success = false,
                        data = data,
                        csw = null,
                        error = "Transfert IN interrompu après $received octets (code $read)",
                    )
                }
                if (read == 0) {
                    consecutiveZeroReads++
                    if (consecutiveZeroReads >= 2) break
                    continue
                }
                consecutiveZeroReads = 0
                received += read
            }
        }

        val cswBytes = readCsw()
            ?: run {
                recoverFromError()
                return CommandOutcome(false, data, null, "CSW absent (périphérique déconnecté ?)")
            }
        val csw = ScsiProtocol.parseCommandStatusWrapper(cswBytes, tag).getOrElse {
            recoverFromError()
            return CommandOutcome(false, data, null, it.message)
        }

        if (csw.status != ScsiProtocol.STATUS_PASSED) {
            val sense = requestSense()
            return CommandOutcome(
                success = false,
                data = data,
                csw = csw,
                error = "CSW ${csw.statusText}, résidu ${csw.residue}",
                sense = sense,
            )
        }
        if (received < transferLength && transferLength > 0) {
            val sense = requestSense()
            return CommandOutcome(
                success = false,
                data = data,
                csw = csw,
                error = "Transfert incomplet ($received/$transferLength octets)",
                sense = sense,
            )
        }
        if (csw.residue != 0) {
            return CommandOutcome(
                success = false,
                data = data,
                csw = csw,
                error = "Résidu CSW non nul (${csw.residue})",
            )
        }
        return CommandOutcome(true, data, csw, null)
    }

    private fun readCsw(): ByteArray? {
        val csw = ByteArray(13)
        var received = 0
        var attempts = 0
        while (received < csw.size && attempts < 5) {
            val read = connection.bulkTransfer(bulkIn, csw, received, csw.size - received, timeoutMs)
            if (read <= 0) {
                attempts++
                continue
            }
            received += read
        }
        return if (received == csw.size) csw else null
    }

    /** CLEAR_FEATURE(ENDPOINT_HALT) on both bulk endpoints, then drain a stale CSW. */
    private fun recoverFromError() {
        runCatching {
            connection.controlTransfer(0x02, 0x01, 0, bulkIn.address, null, 0, 1_000)
            connection.controlTransfer(0x02, 0x01, 0, bulkOut.address, null, 0, 1_000)
        }
        runCatching {
            val scratch = ByteArray(13)
            connection.bulkTransfer(bulkIn, scratch, scratch.size, 100)
        }
    }

    override fun close() {
        if (claimed) {
            runCatching { connection.releaseInterface(usbInterface) }
            claimed = false
        }
        runCatching { connection.close() }
    }
}
