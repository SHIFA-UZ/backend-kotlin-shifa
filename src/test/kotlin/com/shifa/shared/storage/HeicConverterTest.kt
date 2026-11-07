package com.shifa.shared.storage

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** BE-21: HEIC is recognised by name or type; without `heif-convert` (or on bad input) the original is kept. */
class HeicConverterTest {
    private val converter = HeicConverter()

    @Test
    fun `heic is recognised by extension or media type`() {
        assertTrue(converter.isHeic("IMG_0001.HEIC", null))
        assertTrue(converter.isHeic("photo.heif", "application/octet-stream"))
        assertTrue(converter.isHeic("blob", "image/heic; charset=binary"))
        assertFalse(converter.isHeic("scan.jpg", "image/jpeg"))
        assertFalse(converter.isHeic(null, null))
    }

    @Test
    fun `bytes that are not an image are not converted`() {
        assertNull(converter.toJpeg("not an image".byteInputStream()))
    }

    @Test
    fun `missing tool means no conversion`() {
        val hasTool = runCatching { ProcessBuilder("heif-convert", "--version").start().waitFor() }.isSuccess
        assumeTrue(!hasTool, "heif-convert is installed here")
        assertNull(converter.toJpeg(ByteArray(64).inputStream()))
    }
}
