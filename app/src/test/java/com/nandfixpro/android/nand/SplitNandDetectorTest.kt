package com.nandfixpro.android.nand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitNandDetectorTest {

    private fun ref(name: String, size: Long) = SplitNandDetector.FileRef(name, size)

    private val fourMib = 4L * 1024 * 1024

    @Test
    fun `complete hekate split set is accepted`() {
        val files = listOf(
            ref("rawnand.bin.00", fourMib),
            ref("rawnand.bin.01", fourMib),
            ref("rawnand.bin.02", 1024L * 1024),
            ref("BOOT0", fourMib),
            ref("BOOT1", fourMib),
        )
        val result = SplitNandDetector.detect("rawnand.bin.01", files)
        assertTrue(result is SplitNandDetector.Result.Success)
        val set = (result as SplitNandDetector.Result.Success).set
        assertEquals(SplitNandDetector.Format.HEKATE_SPLIT, set.format)
        assertEquals(3, set.partNames.size)
        assertEquals(9L * 1024 * 1024, set.totalSize)
        assertTrue(set.hasBoot0)
        assertTrue(set.hasBoot1)
    }

    @Test
    fun `missing part is rejected`() {
        val files = listOf(
            ref("rawnand.bin.00", fourMib),
            ref("rawnand.bin.02", 1024L * 1024),
        )
        val result = SplitNandDetector.detect("rawnand.bin.00", files)
        assertTrue(result is SplitNandDetector.Result.Failure)
    }

    @Test
    fun `inconsistent part sizes are rejected`() {
        val files = listOf(
            ref("rawnand.bin.00", fourMib),
            ref("rawnand.bin.01", fourMib - 512),
            ref("rawnand.bin.02", 1024L * 1024),
        )
        val result = SplitNandDetector.detect("rawnand.bin.00", files)
        assertTrue(result is SplitNandDetector.Result.Failure)
    }

    @Test
    fun `emuMMC files require boot0 and boot1`() {
        val files = listOf(
            ref("00", fourMib),
            ref("01", 512L * 1024),
        )
        val result = SplitNandDetector.detect("00", files)
        assertTrue(result is SplitNandDetector.Result.Failure)
        assertTrue((result as SplitNandDetector.Result.Failure).reason.contains("BOOT0"))
    }

    @Test
    fun `emuMMC with boot files is accepted`() {
        val files = listOf(
            ref("00", fourMib),
            ref("01", 512L * 1024),
            ref("BOOT0", fourMib),
            ref("BOOT1", fourMib),
        )
        val result = SplitNandDetector.detect("01", files)
        assertTrue(result is SplitNandDetector.Result.Success)
        val set = (result as SplitNandDetector.Result.Success).set
        assertEquals(SplitNandDetector.Format.EMUMMC_SD_FILES, set.format)
        assertEquals(2, set.partNames.size)
    }

    @Test
    fun `single rawnand file is not a split set`() {
        val result = SplitNandDetector.detect("rawnand.bin", listOf(ref("rawnand.bin", fourMib)))
        assertTrue(result is SplitNandDetector.Result.Failure)
    }

    @Test
    fun `unrecognised name is refused`() {
        val result = SplitNandDetector.detect("backup.img", listOf(ref("backup.img", fourMib)))
        assertTrue(result is SplitNandDetector.Result.Failure)
    }
}
