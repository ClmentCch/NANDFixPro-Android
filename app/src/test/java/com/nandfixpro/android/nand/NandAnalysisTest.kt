package com.nandfixpro.android.nand

import com.nandfixpro.android.diagnostics.SyntheticFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NandAnalysisTest {

    @Test
    fun `synthetic switch image is analysed without gpt errors`() {
        val image = SyntheticFixtures.gptImage()
        val analysis = NandAnalyzer.analyze(ByteArraySource(image, "RAWNAND.bin"), "RAWNAND.bin")

        assertNotNull(analysis.gpt)
        assertTrue(analysis.gpt!!.looksLikeSwitch)
        assertEquals(8, analysis.partitions.size)
        val gptErrors = analysis.issues.filter {
            it.severity == IssueSeverity.ERROR && it.title.contains("GPT")
        }
        assertTrue("GPT errors: ${gptErrors.map { it.title }}", gptErrors.isEmpty())
        assertTrue(analysis.issues.any { it.title == "Taille cohérente avec la GPT" })
        assertTrue(analysis.issues.any { it.title == "GPT de secours" && it.severity == IssueSeverity.OK })
        assertEquals(NandCapacity.OTHER, analysis.capacity)
    }

    @Test
    fun `truncated image is reported as inconsistent`() {
        val image = SyntheticFixtures.gptImage()
        val truncated = image.copyOfRange(0, image.size - 4096)
        val analysis = NandAnalyzer.analyze(ByteArraySource(truncated, "RAWNAND.bin"), "RAWNAND.bin")

        assertTrue(
            analysis.issues.any { it.severity == IssueSeverity.ERROR && it.title == "Taille incohérente avec la GPT" },
        )
        assertTrue(analysis.errors > 0)
    }

    @Test
    fun `prodinfo analysis runs verification`() {
        val bytes = SyntheticFixtures.prodinfo()
        val analysis = NandAnalyzer.analyze(ByteArraySource(bytes, "PRODINFO.bin"), "PRODINFO.bin")

        assertEquals(NandFileKind.PRODINFO, analysis.kind)
        assertNotNull(analysis.prodinfo)
        assertNotNull(analysis.prodinfoVerification)
        assertTrue(analysis.prodinfoVerification!!.valid)
        assertEquals("XAW10000000001", analysis.prodinfo!!.serialNumber)
    }

    @Test
    fun `prodinfo with broken checksum is flagged`() {
        val bytes = SyntheticFixtures.prodinfo()
        bytes[0x3740] = 4 // model changed, CRC not recomputed
        val analysis = NandAnalyzer.analyze(ByteArraySource(bytes, "PRODINFO.bin"), "PRODINFO.bin")
        assertTrue(analysis.errors > 0)
    }

    @Test
    fun `blank boot partition is rejected`() {
        val bytes = SyntheticFixtures.bootPartition()
        val analysis = NandAnalyzer.analyze(ByteArraySource(bytes, "BOOT0"), "BOOT0")
        assertEquals(NandFileKind.BOOT0, analysis.kind)
        assertTrue(analysis.issues.any { it.title == "Partition BOOT vide" })
        assertEquals(NandFileDetector.BOOT_PARTITION_SIZE, analysis.boot!!.size)
    }

    @Test
    fun `prod_keys file is parsed without exposing values`() {
        val text = buildString {
            appendLine("# test keys")
            appendLine("master_key_00 = ${"11".repeat(16)}")
            appendLine("master_key_01 = ${"22".repeat(16)}")
            appendLine("key_area_key_application_00 = ${"33".repeat(16)}")
            appendLine("prodinfo = ${"44".repeat(32)}")
            appendLine("invalid = nothex")
        }
        val analysis = NandAnalyzer.analyze(ByteArraySource(text.toByteArray(), "prod.keys"), "prod.keys")
        assertEquals(NandFileKind.PROD_KEYS, analysis.kind)
        val keys = analysis.keys!!
        assertEquals(4, keys.validKeys)
        assertEquals(listOf(0, 1), keys.masterKeys)
        assertTrue(keys.hasKeyAreaKeys)
        assertEquals(1, keys.invalidLines)
        assertTrue(
            "le résumé ne doit pas exposer de valeur de clé",
            !keys.toString().contains("11111111"),
        )
    }

    @Test
    fun `file type detection identifies boot partitions by size`() {
        val detected = NandFileDetector.detect(
            "BOOT1",
            NandFileDetector.BOOT_PARTITION_SIZE,
            ByteArray(16),
        )
        assertEquals(NandFileKind.BOOT1, detected.kind)
        assertTrue(detected.detail.contains("4 Mio"))
    }

    @Test
    fun `split source reads across part boundaries`() {
        val partA = ByteArray(1024) { 0x0A }
        val partB = ByteArray(2048) { 0x0B }
        val source = MultiSource(
            listOf(ByteArraySource(partA, "a"), ByteArraySource(partB, "b")),
            "split",
        )
        assertEquals(3072L, source.size)
        val cross = source.read(1000, 100)
        assertEquals(0x0A.toByte(), cross.first())
        assertEquals(0x0B.toByte(), cross.last())
        assertEquals(24, cross.count { it == 0x0A.toByte() })
    }

    @Test
    fun `partition probe identifies fat and cal0`() {
        val fat = ByteArray(512)
        "FAT32   ".toByteArray().copyInto(fat, 82)
        assertEquals("système de fichiers FAT32", PartitionProbe.describe(fat))

        val prodinfo = ByteArray(512)
        "CAL0".toByteArray().copyInto(prodinfo, 0)
        assertEquals("PRODINFO déchiffré (CAL0)", PartitionProbe.describe(prodinfo))

        assertEquals("zone vide (0x00)", PartitionProbe.describe(ByteArray(512)))
    }
}
