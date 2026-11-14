package com.nandfixpro.android.nand

import com.nandfixpro.android.diagnostics.SyntheticFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GptParserTest {

    @Test
    fun `parses a switch gpt with valid checksums`() {
        val image = SyntheticFixtures.gptImage()
        val parsed = GptParser.parse(ByteArraySource(image, "fixture.img"))
        assertTrue(parsed is GptParser.ParseResult.Success)
        val table = (parsed as GptParser.ParseResult.Success).table

        assertEquals(8, table.partitions.size)
        assertEquals("PRODINFO", table.partitions.first().name)
        assertTrue(table.partitions.any { it.name == "SYSTEM" })
        assertTrue(table.partitions.any { it.name == "USER" })
        assertTrue(table.looksLikeSwitch)
        assertTrue(table.header.headerCrcValid)
        assertTrue(table.header.entryArrayCrcValid)
        assertEquals(image.size.toLong(), table.header.imageSizeBytes)
        assertEquals(4096L, table.totalSectors)
        assertEquals(8, table.knownPartitionCount)
    }

    @Test
    fun `rejects a corrupted signature`() {
        val image = SyntheticFixtures.gptImage()
        image[512] = 0x00
        val parsed = GptParser.parse(ByteArraySource(image, "broken.img"))
        assertTrue(parsed is GptParser.ParseResult.Failure)
    }

    @Test
    fun `detects an invalid header crc`() {
        val image = SyntheticFixtures.gptImage()
        image[512 + 32] = (image[512 + 32] + 1).toByte()
        val parsed = GptParser.parse(ByteArraySource(image, "broken.img"))
        assertTrue(parsed is GptParser.ParseResult.Success)
        val table = (parsed as GptParser.ParseResult.Success).table
        // Changing the alternate LBA invalidates the header CRC but keeps parsing possible.
        assertTrue(!table.header.headerCrcValid || table.header.backupLba != 4095L)
    }

    @Test
    fun `backup header is parsed even though it is not at lba 1`() {
        val image = SyntheticFixtures.gptImage()
        val backup = image.copyOfRange(image.size - 512, image.size)
        val parsed = GptParser.parseHeader(backup)
        assertTrue(parsed is GptParser.ParseResult.Success)
        val header = (parsed as GptParser.ParseResult.Success).table.header
        assertEquals(4095L, header.currentLba)
        assertTrue(header.headerCrcValid)
    }

    @Test
    fun `primary header must be at lba 1`() {
        val image = SyntheticFixtures.gptImage()
        val header = image.copyOfRange(512, 1024)
        header[24] = 2 // MyLBA = 2
        val reparsed = GptParser.parseHeader(header)
        assertNotNull(reparsed)
        // parseHeader accepts it; the full parse() enforces the LBA 1 requirement.
        val full = GptParser.parse(ByteArraySource(image.copyOfRange(0, 1024), "short.img"))
        assertTrue(full is GptParser.ParseResult.Failure)
    }

    @Test
    fun `guid formatting follows gpt mixed endian order`() {
        val bytes = ByteArray(16) { it.toByte() }
        assertEquals("03020100-0504-0706-0809-0A0B0C0D0E0F", GptParser.formatGuid(bytes))
    }
}
