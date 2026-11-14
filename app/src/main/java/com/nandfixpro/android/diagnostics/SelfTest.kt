package com.nandfixpro.android.diagnostics

import com.nandfixpro.android.nand.ByteArraySource
import com.nandfixpro.android.nand.GptParser
import com.nandfixpro.android.nand.NandAnalyzer
import com.nandfixpro.android.nand.ProdinfoEngine
import com.nandfixpro.android.nand.SplitNandDetector
import com.nandfixpro.android.usb.ScsiProtocol
import java.security.MessageDigest

data class SelfTestResult(val name: String, val passed: Boolean, val detail: String)

data class SelfTestReport(
    val results: List<SelfTestResult>,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val passed: Int get() = results.count { it.passed }
    val failed: Int get() = results.count { !it.passed }
    val allPassed: Boolean get() = failed == 0
}

/**
 * Verifies the analysis engines inside the installed app. This is not a hardware
 * test: it exercises the pure logic (GPT, PRODINFO, split detection, SCSI framing,
 * hashing) against synthetic structures so a user can prove the build is sound
 * before trusting it with a real dump.
 */
object SelfTest {

    fun run(): SelfTestReport {
        val results = mutableListOf<SelfTestResult>()

        results += runCatching {
            val image = SyntheticFixtures.gptImage()
            val source = ByteArraySource(image, "gpt-fixture")
            when (val parsed = GptParser.parse(source)) {
                is GptParser.ParseResult.Success -> {
                    val table = parsed.table
                    if (table.partitions.size != SyntheticFixtures.SWITCH_PARTITION_LAYOUT.size ||
                        !table.header.headerCrcValid || !table.looksLikeSwitch
                    ) {
                        SelfTestResult(
                            "GPT synthétique",
                            false,
                            "partitions=${table.partitions.size}, crc=${table.header.headerCrcValid}, switch=${table.looksLikeSwitch}",
                        )
                    } else {
                        SelfTestResult(
                            "GPT synthétique",
                            true,
                            "${table.partitions.size} partitions Switch, CRC en-tête et table valides.",
                        )
                    }
                }
                is GptParser.ParseResult.Failure -> SelfTestResult("GPT synthétique", false, parsed.reason)
            }
        }.getOrElse { SelfTestResult("GPT synthétique", false, it.message ?: "exception") }

        results += runCatching {
            val bytes = SyntheticFixtures.prodinfo()
            val engine = ProdinfoEngine()
            val loaded = engine.load(bytes, "fixture")
            if (loaded !is ProdinfoEngine.LoadResult.Success) {
                SelfTestResult("PRODINFO synthétique", false, "chargement refusé")
            } else {
                val verification = engine.verify()
                if (!verification.valid) {
                    val detail = verification.checks.filter { it.severity != ProdinfoEngine.Severity.OK }
                        .joinToString(" | ") { "${it.name}: ${it.detail}" }
                    SelfTestResult("PRODINFO synthétique", false, detail)
                } else {
                    SelfTestResult(
                        "PRODINFO synthétique",
                        true,
                        "CRC16, SHA-256 et blocs valides · modèle ${loaded.summary.modelName} · série ${loaded.summary.serialNumber}",
                    )
                }
            }
        }.getOrElse { SelfTestResult("PRODINFO synthétique", false, it.message ?: "exception") }

        results += runCatching {
            val edited = ProdinfoEngine().apply {
                load(SyntheticFixtures.prodinfo(), "fixture")
            }.applyEditsAndRecalculate(ProdinfoEngine.Edit(serial = "XAW10000000002", wifiRegion = "Japon"))
            val bytes = edited.getOrElse { return@runCatching SelfTestResult("Édition PRODINFO", false, it.message ?: "échec") }
            val engine = ProdinfoEngine()
            engine.load(bytes, "edited")
            val verification = engine.verify()
            if (verification.valid && engine.summary().serialNumber == "XAW10000000002") {
                SelfTestResult("Édition PRODINFO", true, "Checksums recalculés et vérifiés après édition.")
            } else {
                SelfTestResult("Édition PRODINFO", false, "Le recalcul des checksums n'est pas cohérent.")
            }
        }.getOrElse { SelfTestResult("Édition PRODINFO", false, it.message ?: "exception") }

        results += runCatching {
            val refs = listOf(
                SplitNandDetector.FileRef("rawnand.bin.00", 4L * 1024 * 1024),
                SplitNandDetector.FileRef("rawnand.bin.01", 4L * 1024 * 1024),
                SplitNandDetector.FileRef("rawnand.bin.02", 1024L * 1024),
                SplitNandDetector.FileRef("BOOT0", 4L * 1024 * 1024),
                SplitNandDetector.FileRef("BOOT1", 4L * 1024 * 1024),
            )
            when (val detection = SplitNandDetector.detect("rawnand.bin.01", refs)) {
                is SplitNandDetector.Result.Success -> {
                    val ok = detection.set.partNames.size == 3 &&
                        detection.set.totalSize == 9L * 1024 * 1024 &&
                        detection.set.hasBoot0 && detection.set.hasBoot1
                    SelfTestResult(
                        "Détection NAND découpée",
                        ok,
                        "3 parties, ${detection.set.totalSize} octets, BOOT0/BOOT1 présents.",
                    )
                }
                is SplitNandDetector.Result.Failure -> SelfTestResult("Détection NAND découpée", false, detection.reason)
            }
        }.getOrElse { SelfTestResult("Détection NAND découpée", false, it.message ?: "exception") }

        results += runCatching {
            val missing = listOf(
                SplitNandDetector.FileRef("rawnand.bin.00", 4L * 1024 * 1024),
                SplitNandDetector.FileRef("rawnand.bin.02", 1024L * 1024),
            )
            val detection = SplitNandDetector.detect("rawnand.bin.00", missing)
            val rejected = detection is SplitNandDetector.Result.Failure
            SelfTestResult(
                "Rejet d'un jeu incomplet",
                rejected,
                if (rejected) "Une partie manquante est correctement refusée." else "Un jeu incomplet a été accepté.",
            )
        }.getOrElse { SelfTestResult("Rejet d'un jeu incomplet", false, it.message ?: "exception") }

        results += runCatching {
            val cdb = ScsiProtocol.read10(0x1234, 8)
            val cbw = ScsiProtocol.buildCommandBlockWrapper(7, 4096, true, 0, cdb)
            val signatureOk = ScsiProtocol.readInt32LittleEndian(cbw, 0) == ScsiProtocol.CBW_SIGNATURE.toLong()
            val tagOk = ScsiProtocol.readInt32LittleEndian(cbw, 4) == 7L
            val lengthOk = ScsiProtocol.readInt32LittleEndian(cbw, 8) == 4096L
            val flagsOk = cbw[12].toInt() == 0x80
            val cdbOk = cbw[15].toInt() == ScsiProtocol.OP_READ_10 && cbw[14].toInt() == 10
            val csw = ByteArray(13).apply {
                ScsiProtocol.putInt32LittleEndian(this, 0, ScsiProtocol.CSW_SIGNATURE)
                ScsiProtocol.putInt32LittleEndian(this, 4, 7)
                this[12] = 0
            }
            val cswOk = ScsiProtocol.parseCommandStatusWrapper(csw, 7).isSuccess
            val capacityBytes = ByteArray(8).apply {
                ScsiProtocol.putInt32BigEndian(this, 0, 61_122_111)
                ScsiProtocol.putInt32BigEndian(this, 4, 512)
            }
            val capacityOk = ScsiProtocol.parseCapacity10(capacityBytes)
                .map { it.blockSize == 512 && it.totalSectors == 61_122_112L }
                .getOrDefault(false)
            val passed = signatureOk && tagOk && lengthOk && flagsOk && cdbOk && cswOk && capacityOk
            SelfTestResult(
                "Trame SCSI Bulk-Only",
                passed,
                "CBW/CSW et READ CAPACITY(10) conformes au protocole.",
            )
        }.getOrElse { SelfTestResult("Trame SCSI Bulk-Only", false, it.message ?: "exception") }

        results += runCatching {
            val hash = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            SelfTestResult("SHA-256", hash == expected, "Empreinte de contrôle conforme.")
        }.getOrElse { SelfTestResult("SHA-256", false, it.message ?: "exception") }

        results += runCatching {
            val image = SyntheticFixtures.gptImage()
            val analysis = NandAnalyzer.analyze(ByteArraySource(image, "fixture.img"), "fixture.img")
            val gptOk = analysis.gpt != null && analysis.gpt.looksLikeSwitch
            val noErrors = analysis.issues.none {
                it.severity == com.nandfixpro.android.nand.IssueSeverity.ERROR &&
                    it.title.contains("GPT")
            }
            SelfTestResult(
                "Pipeline d'analyse",
                gptOk && noErrors,
                "${analysis.partitions.size} partitions analysées, ${analysis.errors} erreur(s), ${analysis.warnings} avertissement(s).",
            )
        }.getOrElse { SelfTestResult("Pipeline d'analyse", false, it.message ?: "exception") }

        return SelfTestReport(results)
    }
}
