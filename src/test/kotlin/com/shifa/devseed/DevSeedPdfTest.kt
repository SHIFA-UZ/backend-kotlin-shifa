package com.shifa.devseed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevSeedPdfTest {
    @Test
    fun `fixture pdf has a valid header, xref offsets that point at each object, and the text`() {
        val bytes = DevSeedRunner.pdf("Seed blood test")
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.contains("(Seed blood test) Tj"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        val xrefStart = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertEquals("xref", text.substring(xrefStart, xrefStart + 4))
        val entries = text.substring(xrefStart).lines().drop(3).takeWhile { it.endsWith(" n ") } // after "xref", "0 6" and the free entry
        assertEquals(5, entries.size)
        entries.forEachIndexed { i, entry ->
            val offset = entry.substringBefore(" ").toInt()
            assertEquals("${i + 1} 0 obj", text.substring(offset, offset + "${i + 1} 0 obj".length))
        }
    }
}
