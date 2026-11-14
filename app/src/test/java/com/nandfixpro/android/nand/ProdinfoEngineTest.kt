package com.nandfixpro.android.nand

import com.nandfixpro.android.diagnostics.SyntheticFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProdinfoEngineTest {

    @Test
    fun `valid fixture loads and verifies`() {
        val engine = ProdinfoEngine()
        val loaded = engine.load(SyntheticFixtures.prodinfo(), "PRODINFO.bin")
        assertTrue(loaded is ProdinfoEngine.LoadResult.Success)
        val summary = (loaded as ProdinfoEngine.LoadResult.Success).summary

        assertEquals("XAW10000000001", summary.serialNumber)
        assertEquals("V2 (Mariko)", summary.modelName)
        assertEquals("Europe", summary.wifiRegion)
        assertEquals(0x8004, summary.fileSize)
        assertTrue(engine.verify().valid)
    }

    @Test
    fun `file without CAL0 magic is refused`() {
        val bytes = SyntheticFixtures.prodinfo()
        bytes[0] = 'X'.toByte()
        val result = ProdinfoEngine().load(bytes, "bad.bin")
        assertTrue(result is ProdinfoEngine.LoadResult.Failure)
        assertTrue((result as ProdinfoEngine.LoadResult.Failure).message.contains("CAL0"))
    }

    @Test
    fun `too small file is refused`() {
        val result = ProdinfoEngine().load(ByteArray(512), "tiny.bin")
        assertTrue(result is ProdinfoEngine.LoadResult.Failure)
    }

    @Test
    fun `edited copy is recalculated and verified`() {
        val engine = ProdinfoEngine()
        engine.load(SyntheticFixtures.prodinfo(), "PRODINFO.bin")
        val edited = engine.applyEditsAndRecalculate(
            ProdinfoEngine.Edit(
                serial = "XAW10000000002",
                wifiRegion = "Japon",
                color = "AABBCC",
            ),
        ).getOrThrow()

        val verifier = ProdinfoEngine()
        verifier.load(edited, "edited.bin")
        val verification = verifier.verify()
        assertTrue(verification.valid)
        assertEquals(0, verification.errors)
        assertEquals("XAW10000000002", verifier.summary().serialNumber)
        assertEquals("Japon", verifier.summary().wifiRegion)
        assertEquals("AABBCC", verifier.summary().colors["HousingBezelColor"])
    }

    @Test
    fun `source bytes are not modified by an edit`() {
        val original = SyntheticFixtures.prodinfo()
        val before = original.copyOf()
        val engine = ProdinfoEngine()
        engine.load(original, "PRODINFO.bin")
        engine.applyEditsAndRecalculate(ProdinfoEngine.Edit(serial = "XAW10000000009"))
        assertTrue(before.contentEquals(original))
    }

    @Test
    fun `invalid serial is rejected`() {
        val engine = ProdinfoEngine()
        engine.load(SyntheticFixtures.prodinfo(), "PRODINFO.bin")
        val result = engine.applyEditsAndRecalculate(ProdinfoEngine.Edit(serial = "SHORT"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `garbage does not pass verification`() {
        val bytes = SyntheticFixtures.prodinfo()
        bytes[0x250] = 'Z'.toByte() // serial changed without recomputing checksums
        val engine = ProdinfoEngine()
        engine.load(bytes, "corrupted.bin")
        val verification = engine.verify()
        assertFalse(verification.valid)
        assertTrue(verification.errors > 0)
    }

    @Test
    fun `crc16 matches stored header checksum`() {
        val bytes = SyntheticFixtures.prodinfo()
        assertEquals(
            ProdinfoEngine.readLe16(bytes, 0x1E),
            ProdinfoEngine.calculateCrc16(bytes, 0, 0x1E),
        )
    }

    @Test
    fun `console model mapping matches original tool`() {
        val engine = ProdinfoEngine()
        assertEquals("Erista (V1 patchée / non patchée)", engine.modelName(1))
        assertEquals("V2 (Mariko)", engine.modelName(3))
        assertEquals("Lite", engine.modelName(4))
        assertEquals("OLED", engine.modelName(6))
    }
}
