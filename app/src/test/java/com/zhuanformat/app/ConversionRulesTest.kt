package com.zhuanformat.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ConversionRulesTest {
    @Test fun filenamesCannotEscapeResultDirectoryOrOverwriteTheSourceName() {
        val output = safeOutputName("../../家庭视频:夏天?.mov", OutputFormat.MP4, "1234abcd")
        assertEquals("家庭视频_夏天_1234abcd.mp4", output)
        assertFalse(output.contains('/'))
        assertFalse(output.contains('\\'))
        assertEquals("转换结果_1234abcd.png", safeOutputName("...", OutputFormat.PNG, "1234abcd"))
        assertEquals("a".repeat(64) + "_u.jpg", safeOutputName("a".repeat(300) + ".png", OutputFormat.JPG, "u"))
    }

    @Test fun dimensionLimitPreservesAspectRatioAndNeverUpscales() {
        assertEquals(1920 to 1080, fitDimensions(3840, 2160, 1920))
        assertEquals(853 to 1280, fitDimensions(2000, 3000, 1280))
        assertEquals(640 to 480, fitDimensions(640, 480, 1920))
        assertEquals(3840 to 2160, fitDimensions(3840, 2160, 0))
        assertEquals(1 to 1280, fitDimensions(1, 100000, 1280))
    }

    @Test fun invalidKindAndFormatCombinationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ConversionOptions(kind = MediaKind.IMAGE, format = OutputFormat.MP4).validate() }
        assertThrows(IllegalArgumentException::class.java) { ConversionOptions(videoHeight = 4320).validate() }
        assertThrows(IllegalArgumentException::class.java) { ConversionOptions(imageQuality = -1).validate() }
        MediaKind.entries.forEach { kind -> formatsFor(kind).forEach { format -> ConversionOptions(kind = kind, format = format).validate() } }
    }

    @Test fun wavHeaderDescribesDecodedSamplesAndNotCompressedSourceBytes() {
        val header = WavHeader.create(192000, 48000, 2)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals(192036, b.getInt(4))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(48000, b.getInt(24))
        assertEquals(192000, b.getInt(28))
        assertEquals(4, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(192000, b.getInt(40))
    }

    @Test fun wavSizeAndSampleAlignmentCannotSilentlyOverflow() {
        assertThrows(IllegalArgumentException::class.java) { WavHeader.create(WavHeader.MAX_DATA_BYTES + 1, 48000, 2) }
        assertThrows(IllegalArgumentException::class.java) { WavHeader.create(3, 48000, 2) }
        assertThrows(IllegalArgumentException::class.java) { WavHeader.create(0, 0, 2) }
        assertThrows(IllegalArgumentException::class.java) { WavHeader.create(0, 48000, 0) }
    }
}
