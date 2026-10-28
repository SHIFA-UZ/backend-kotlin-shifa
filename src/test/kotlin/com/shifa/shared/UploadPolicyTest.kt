package com.shifa.shared

import com.shifa.shared.UploadPolicy.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UploadPolicyTest {

    @Test
    fun `extension is lower-cased, path-stripped and must be a plain token`() {
        assertEquals("pdf", UploadPolicy.extension("Scan.PDF"))
        assertEquals("jpg", UploadPolicy.extension("C:\\Users\\a\\photo.jpg"))
        assertEquals("", UploadPolicy.extension("noext"))
        assertEquals("", UploadPolicy.extension("x.tar.gz!"))
        assertEquals("", UploadPolicy.extension(null))
    }

    @Test
    fun `what production stores today is allowed where it is uploaded`() {
        assertTrue(UploadPolicy.check(Kind.DOCUMENT, "result.pdf").allowed)
        assertTrue(UploadPolicy.check(Kind.IMAGE, "me.jpeg").allowed)
        assertTrue(UploadPolicy.check(Kind.IMAGE, "IMG_1.HEIC").allowed)
        assertTrue(UploadPolicy.check(Kind.CHAT_ATTACHMENT, "voice.m4a").allowed)
        assertTrue(UploadPolicy.check(Kind.CHAT_ATTACHMENT, "letter.docx").allowed)
        assertTrue(UploadPolicy.check(Kind.AUDIO, "rec.webm").allowed)
    }

    @Test
    fun `scriptable and unexpected types are refused`() {
        for (name in listOf("x.html", "x.svg", "x.js", "x.exe", "x.pdf.htm")) {
            assertFalse(UploadPolicy.check(Kind.CHAT_ATTACHMENT, name).allowed, name)
        }
        assertFalse(UploadPolicy.check(Kind.IMAGE, "scan.pdf").allowed)
        assertFalse(UploadPolicy.check(Kind.DOCUMENT, "voice.m4a").allowed)
        assertEquals("no usable extension", UploadPolicy.check(Kind.DOCUMENT, "blob").reason)
    }
}
