package com.nandfixpro.android.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScsiProtocolTest {

    @Test
    fun `cbw encodes header cdb and direction`() {
        val cdb = ScsiProtocol.read10(lba = 0x1234, blocks = 8)
        val cbw = ScsiProtocol.buildCommandBlockWrapper(
            tag = 42,
            transferLength = 4096,
            directionIn = true,
            lun = 1,
            cdb = cdb,
        )

        assertEquals(31, cbw.size)
        assertEquals(0x43425355L, ScsiProtocol.readInt32LittleEndian(cbw, 0))
        assertEquals(42L, ScsiProtocol.readInt32LittleEndian(cbw, 4))
        assertEquals(4096L, ScsiProtocol.readInt32LittleEndian(cbw, 8))
        assertEquals(0x80, cbw[12].toInt() and 0xFF)
        assertEquals(1, cbw[13].toInt())
        assertEquals(10, cbw[14].toInt())
        assertEquals(0x28, cbw[15].toInt() and 0xFF)
        assertEquals(0x1234L, ScsiProtocol.readInt32BigEndian(cbw, 17))
        assertEquals(8, ((cbw[22].toInt() and 0xFF) shl 8) or (cbw[23].toInt() and 0xFF))
    }

    @Test
    fun `cbw rejects cdb longer than 16 bytes`() {
        val result = runCatching {
            ScsiProtocol.buildCommandBlockWrapper(1, 0, false, 0, ByteArray(20))
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `csw passed status is recognised`() {
        val csw = ByteArray(13).apply {
            ScsiProtocol.putInt32LittleEndian(this, 0, ScsiProtocol.CSW_SIGNATURE)
            ScsiProtocol.putInt32LittleEndian(this, 4, 42)
            this[12] = 0
        }
        val parsed = ScsiProtocol.parseCommandStatusWrapper(csw, 42).getOrThrow()
        assertTrue(parsed.passed)
        assertEquals(0, parsed.residue)
    }

    @Test
    fun `csw error status is reported`() {
        val csw = ByteArray(13).apply {
            ScsiProtocol.putInt32LittleEndian(this, 0, ScsiProtocol.CSW_SIGNATURE)
            ScsiProtocol.putInt32LittleEndian(this, 4, 7)
            this[12] = 1
        }
        val parsed = ScsiProtocol.parseCommandStatusWrapper(csw, 7).getOrThrow()
        assertFalse(parsed.passed)
        assertEquals("échec", parsed.statusText)
    }

    @Test
    fun `csw with wrong signature is rejected`() {
        val csw = ByteArray(13).apply {
            ScsiProtocol.putInt32LittleEndian(this, 0, 0x11223344)
        }
        assertTrue(ScsiProtocol.parseCommandStatusWrapper(csw).isFailure)
    }

    @Test
    fun `read capacity 10 is decoded`() {
        val response = ByteArray(8).apply {
            ScsiProtocol.putInt32BigEndian(this, 0, 61_122_111)
            ScsiProtocol.putInt32BigEndian(this, 4, 512)
        }
        val capacity = ScsiProtocol.parseCapacity10(response).getOrThrow()
        assertEquals(61_122_111L, capacity.lastLba)
        assertEquals(512, capacity.blockSize)
        assertEquals(61_122_112L, capacity.totalSectors)
        assertEquals(61_122_112L * 512, capacity.totalBytes)
    }

    @Test
    fun `read capacity 16 is decoded`() {
        val response = ByteArray(32).apply {
            ScsiProtocol.putInt64BigEndian(this, 0, 0x1_0000_0000L)
            ScsiProtocol.putInt32BigEndian(this, 8, 4096)
        }
        val capacity = ScsiProtocol.parseCapacity16(response).getOrThrow()
        assertEquals(4096, capacity.blockSize)
        assertTrue(capacity.totalSectors > Int.MAX_VALUE)
    }

    @Test
    fun `invalid block size is refused`() {
        val response = ByteArray(8).apply {
            ScsiProtocol.putInt32BigEndian(this, 0, 100)
            ScsiProtocol.putInt32BigEndian(this, 4, 0)
        }
        assertTrue(ScsiProtocol.parseCapacity10(response).isFailure)
    }

    @Test
    fun `inquiry fields are decoded`() {
        val response = ByteArray(96)
        response[0] = 0x00
        "hekate  ".toByteArray().copyInto(response, 8)
        "SWITCH eMMC     ".toByteArray().copyInto(response, 16)
        "1.0 ".toByteArray().copyInto(response, 32)
        val inquiry = ScsiProtocol.parseInquiry(response).getOrThrow()
        assertEquals("hekate", inquiry.vendor)
        assertEquals("SWITCH eMMC", inquiry.product)
        assertEquals("1.0", inquiry.revision)
        assertEquals("hekate SWITCH eMMC", inquiry.displayName)
    }

    @Test
    fun `sense key gives a readable description`() {
        val sense = ByteArray(18)
        sense[0] = 0x70
        sense[2] = 0x03
        val parsed = ScsiProtocol.parseSense(sense)
        assertEquals(3, parsed.key)
        assertEquals("erreur média", parsed.description)
    }

    @Test
    fun `read16 encodes a 64-bit lba`() {
        val cdb = ScsiProtocol.read16(lba = 0x1_2345_6789L, blocks = 0x20)
        assertEquals(0x88, cdb[0].toInt() and 0xFF)
        assertEquals(0x1_2345_6789L, ScsiProtocol.readInt64BigEndian(cdb, 2))
        assertEquals(0x20L, ScsiProtocol.readInt32BigEndian(cdb, 10))
    }
}
