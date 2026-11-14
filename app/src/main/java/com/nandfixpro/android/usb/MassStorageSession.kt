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
 *
 * Real devices are not perfectly standards-compliant. This implementation:
 *  - sends INQUIRY before TEST UNIT READY (some bridges only answer after it);
 *  - retries TEST UNIT READY while the medium reports NOT READY / UNIT ATTENTION;
 *  - accepts a short INQUIRY / READ CAPACITY(16) response, which is legal;
 *  - performs the Bulk-Only Mass Storage Reset + endpoint halt clearing after a
 *    transport error and retries the command once;
 *  - never treats a short READ as success, so a truncated dump cannot be saved.
 */
class MassStorageSession(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val lun: Int = 0,
    private val timeoutMs: Int = 10_000,
    private val logger: (String) -> Unit = {},
) : RandomAccessSource {

    private data class CommandOutcome(
        val success: Boolean,
        val data: ByteArray,
        val received: Int,
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

    var maxLun: Int = 0
        private set

    var initialized: Boolean = false
        private set

    private var claimed = false

    private val maxTransferBytes: Int =
        (bulkIn.maxPacketSize * 16).coerceIn(512, 16 * 1024)

    private val effectiveTimeout: Int = timeoutMs.coerceIn(1_000, 30_000)

    override val label: String = "USB UMS"
    override val size: Long get() = capacity?.totalBytes ?: 0L
    val blockSize: Int get() = capacity?.blockSize ?: 512

    /** INQUIRY, TEST UNIT READY and READ CAPACITY; safe to call once after opening. */
    fun initialize(): Result<Unit> {
        if (!claimed) {
            val claimedNow = runCatching { connection.claimInterface(usbInterface, true) }.getOrDefault(false)
            if (!claimedNow) {
                return Result.failure(IOException("Interface UMS verrouillée par le système (claim refusé)"))
            }
            claimed = true
        }

        maxLun = queryMaxLun()
        logger(
            "UMS: interface ${usbInterface.id}, max LUN $maxLun, " +
                "Bulk IN 0x%02X / OUT 0x%02X, paquet %d o".format(
                    bulkIn.address,
                    bulkOut.address,
                    bulkIn.maxPacketSize,
                ),
        )

        // INQUIRY first: several UMS bridges ignore TEST UNIT READY until they
        // have answered at least one INQUIRY.
        var inquiryError = "aucune réponse"
        for (attempt in 1..3) {
            val outcome = execute(
                cdb = ScsiProtocol.inquiry(36),
                transferLength = 36,
                directionIn = true,
                label = "INQUIRY",
                allowShortData = true,
            )
            if (outcome.success) {
                val bytes = outcome.data.copyOf(minOf(outcome.received, outcome.data.size))
                if (bytes.size < 36) {
                    inquiryError = "réponse trop courte (${bytes.size} octets)"
                } else {
                    val parsed = ScsiProtocol.parseInquiry(bytes)
                    if (parsed.isSuccess) {
                        inquiry = parsed.getOrNull()
                        logger("INQUIRY: ${inquiry?.displayName} rév. ${inquiry?.revision}")
                        break
                    }
                    inquiryError = parsed.exceptionOrNull()?.message ?: "réponse illisible"
                }
            } else {
                inquiryError = outcome.error ?: "échec inconnu"
            }
            logger("INQUIRY tentative $attempt : $inquiryError")
            if (attempt < 3) {
                resetRecovery()
                sleepQuietly(300)
            }
        }
        if (inquiry == null) {
            return Result.failure(IOException("INQUIRY a échoué : $inquiryError"))
        }

        // A Hekate eMMC target can answer NOT READY for a short while after the
        // USB link is established. Retry instead of declaring the device broken.
        var readyError = "aucune réponse"
        var ready = false
        for (attempt in 1..8) {
            val outcome = execute(
                cdb = ScsiProtocol.testUnitReady(),
                transferLength = 0,
                directionIn = false,
                label = "TEST UNIT READY",
            )
            if (outcome.success) {
                ready = true
                break
            }
            val sense = outcome.sense ?: requestSense()
            readyError = buildString {
                append(outcome.error ?: "échec")
                if (sense != null) append(" · sense: ${sense.description}")
            }
            logger("TEST UNIT READY tentative $attempt : $readyError")
            if (attempt < 8) sleepQuietly(350)
        }
        if (!ready) {
            return Result.failure(IOException("Périphérique non prêt : $readyError"))
        }

        var capacityError = "aucune réponse"
        for (attempt in 1..3) {
            val capacity10 = execute(
                cdb = ScsiProtocol.readCapacity10(),
                transferLength = 8,
                directionIn = true,
                label = "READ CAPACITY(10)",
            )
            if (capacity10.success) {
                ScsiProtocol.parseCapacity10(capacity10.data.copyOf(capacity10.received))
                    .onSuccess { capacity = it }
                    .onFailure { capacityError = it.message ?: "réponse invalide" }
            } else {
                capacityError = capacity10.error ?: "échec"
            }

            if (capacity == null || capacity?.lastLba == 0xFFFFFFFFL) {
                val capacity16 = execute(
                    cdb = ScsiProtocol.readCapacity16(),
                    transferLength = 32,
                    directionIn = true,
                    label = "READ CAPACITY(16)",
                    allowShortData = true,
                )
                if (capacity16.success && capacity16.received >= 12) {
                    ScsiProtocol.parseCapacity16(capacity16.data.copyOf(capacity16.received))
                        .onSuccess { capacity = it }
                        .onFailure { capacityError = it.message ?: "réponse invalide" }
                } else if (capacity == null) {
                    capacityError = capacity16.error ?: "réponse trop courte"
                }
            }

            if (capacity != null) break
            if (attempt < 3) {
                resetRecovery()
                sleepQuietly(400)
            }
        }

        val resolved = capacity
            ?: return Result.failure(IOException("Capacité illisible : $capacityError"))

        if (resolved.blockSize != 512 && resolved.blockSize != 4096) {
            logger("Taille de bloc inhabituelle : ${resolved.blockSize} octets")
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
                label = if (cdb.size == 10) "READ(10)" else "READ(16)",
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
            label = "READ direct",
        )
        if (!outcome.success) throw IOException("Lecture directe échouée: ${outcome.error}")
        return outcome.data.copyOf(outcome.received)
    }

    fun requestSense(): ScsiProtocol.Sense? {
        val outcome = executeOnce(
            cdb = ScsiProtocol.requestSense(18),
            transferLength = 18,
            directionIn = true,
            label = "REQUEST SENSE",
            allowShortData = true,
        )
        if (!outcome.success || outcome.received < 3) return null
        return runCatching { ScsiProtocol.parseSense(outcome.data.copyOf(outcome.received)) }.getOrNull()
    }

    /**
     * Sends one command. Transport failures (missing/invalid CSW, bulk error) get
     * one BOT reset + retry; SCSI CHECK CONDITION results are returned as-is so
     * callers can decide whether to retry.
     */
    private fun execute(
        cdb: ByteArray,
        transferLength: Int,
        directionIn: Boolean,
        destination: ByteArray? = null,
        destinationOffset: Int = 0,
        label: String,
        allowShortData: Boolean = false,
    ): CommandOutcome {
        val first = executeOnce(
            cdb, transferLength, directionIn, destination, destinationOffset, label, allowShortData,
        )
        if (first.success) return first

        val transportFailure =
            first.csw == null ||
                first.error?.contains("CSW") == true ||
                first.error?.contains("CBW") == true ||
                first.error?.contains("Transfert IN") == true
        if (!transportFailure) return first

        logger("$label : récupération BOT après « ${first.error} »")
        resetRecovery()
        val second = executeOnce(
            cdb, transferLength, directionIn, destination, destinationOffset, label, allowShortData,
        )
        if (!second.success) logger("$label : échec après récupération — ${second.error}")
        return second
    }

    private fun executeOnce(
        cdb: ByteArray,
        transferLength: Int,
        directionIn: Boolean,
        destination: ByteArray? = null,
        destinationOffset: Int = 0,
        label: String,
        allowShortData: Boolean,
    ): CommandOutcome {
        val tag = nextTag++
        val dataIn = directionIn && transferLength > 0
        val cbw = ScsiProtocol.buildCommandBlockWrapper(tag, transferLength, dataIn, lun, cdb)
        val sent = runCatching { connection.bulkTransfer(bulkOut, cbw, cbw.size, effectiveTimeout) }
            .getOrElse {
                return CommandOutcome(false, ByteArray(0), 0, null, "$label : envoi CBW impossible (${it.message})")
            }
        if (sent != cbw.size) {
            return CommandOutcome(
                false, ByteArray(0), 0, null,
                "$label : envoi CBW incomplet ($sent/${cbw.size} octets)",
            )
        }

        var data: ByteArray = ByteArray(0)
        var received = 0
        if (dataIn) {
            data = destination ?: ByteArray(transferLength)
            val limit = minOf(transferLength, data.size - destinationOffset)
            while (received < limit) {
                val chunk = minOf(maxTransferBytes, limit - received)
                val read = runCatching {
                    connection.bulkTransfer(
                        bulkIn,
                        data,
                        destinationOffset + received,
                        chunk,
                        effectiveTimeout,
                    )
                }.getOrElse {
                    return CommandOutcome(
                        false, data, received, null,
                        "$label : transfert IN impossible (${it.message})",
                    )
                }
                if (read < 0) {
                    return CommandOutcome(
                        false, data, received, null,
                        "$label : transfert IN interrompu après $received octets (code $read)",
                    )
                }
                if (read == 0) {
                    consecutiveZeroReads++
                    if (consecutiveZeroReads >= 3) break
                    continue
                }
                consecutiveZeroReads = 0
                received += read
            }
        }

        val cswBytes = readCsw()
            ?: return CommandOutcome(false, data, received, null, "$label : CSW absent (déconnecté ?)")
        val csw = ScsiProtocol.parseCommandStatusWrapper(cswBytes, tag).getOrElse {
            return CommandOutcome(false, data, received, null, "$label : ${it.message}")
        }

        if (csw.status != ScsiProtocol.STATUS_PASSED) {
            val sense = requestSense()
            return CommandOutcome(
                false, data, received, csw,
                "$label : CSW ${csw.statusText}, résidu ${csw.residue}",
                sense,
            )
        }

        if (received < transferLength) {
            if (!allowShortData) {
                return CommandOutcome(
                    false, data, received, csw,
                    "$label : transfert incomplet ($received/$transferLength octets)",
                )
            }
            if (received <= 0) {
                return CommandOutcome(false, data, 0, csw, "$label : réponse vide")
            }
            data = data.copyOf(received)
        }

        if (csw.residue != 0 && received == transferLength) {
            logger("$label : résidu CSW ${csw.residue} ignoré (données complètes reçues)")
        }
        logger(
            "$label : OK · ${if (dataIn) "$received octets" else "sans données"} · " +
                "CSW=${csw.statusText} résidu=${csw.residue}",
        )
        return CommandOutcome(true, data, received, csw, null)
    }

    private fun readCsw(): ByteArray? {
        val csw = ByteArray(13)
        var received = 0
        var attempts = 0
        while (received < csw.size && attempts < 8) {
            val read = runCatching {
                connection.bulkTransfer(
                    bulkIn,
                    csw,
                    received,
                    csw.size - received,
                    effectiveTimeout.coerceAtMost(4_000),
                )
            }.getOrDefault(-1)
            if (read <= 0) {
                attempts++
                continue
            }
            received += read
        }
        return if (received == csw.size) csw else null
    }

    /** Bulk-Only Mass Storage Reset + CLEAR_FEATURE(ENDPOINT_HALT). */
    private fun resetRecovery() {
        runCatching {
            connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, null, 0, 2_000)
        }
        runCatching {
            connection.controlTransfer(0x02, 0x01, 0, bulkIn.address, null, 0, 1_000)
            connection.controlTransfer(0x02, 0x01, 0, bulkOut.address, null, 0, 1_000)
        }
        runCatching {
            val scratch = ByteArray(64)
            connection.bulkTransfer(bulkIn, scratch, scratch.size, 150)
        }
    }

    private fun queryMaxLun(): Int {
        val buffer = ByteArray(1)
        val read = runCatching {
            connection.controlTransfer(0xA1, 0xFE, 0, usbInterface.id, buffer, 1, 1_500)
        }.getOrDefault(-1)
        return if (read >= 1) buffer[0].toInt() and 0xFF else 0
    }

    private fun sleepQuietly(milliseconds: Long) {
        runCatching { Thread.sleep(milliseconds) }
    }

    override fun close() {
        if (claimed) {
            runCatching { connection.releaseInterface(usbInterface) }
            claimed = false
        }
        runCatching { connection.close() }
    }
}
