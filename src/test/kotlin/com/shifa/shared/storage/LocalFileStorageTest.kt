package com.shifa.shared.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LocalFileStorageTest {

    @TempDir
    lateinit var root: Path

    private fun storage() = LocalFileStorage(root)

    @Test
    fun `put, head, open, list and delete round-trip`() {
        val s = storage()
        val bytes = "hello".toByteArray()
        s.put("patientdocuments/7/a.pdf", bytes.inputStream(), bytes.size.toLong(), "application/pdf")

        assertEquals(StoredObject("patientdocuments/7/a.pdf", 5), s.head("patientdocuments/7/a.pdf"))
        assertEquals("hello", s.open("patientdocuments/7/a.pdf")!!.inputStream.use { it.readBytes().decodeToString() })
        assertEquals(listOf("patientdocuments/7/a.pdf"), s.list("patientdocuments").map { it.key }.toList())
        assertEquals(emptyList<StoredObject>(), s.list("doctors").toList())

        assertTrue(s.delete("patientdocuments/7/a.pdf"))
        assertTrue(s.delete("patientdocuments/7/a.pdf"), "deleting a missing object is still true")
        assertNull(s.open("patientdocuments/7/a.pdf"))
        assertFalse(s.exists("patientdocuments/7/a.pdf"))
    }

    @Test
    fun `keys cannot escape the root`() {
        val s = storage()
        for (bad in listOf("../etc/passwd", "doctors/../../x", "/abs", "a//b", "doctors/./x", "", "a\\b")) {
            assertFalse(StorageKeys.valid(bad), bad)
            assertThrows(IllegalArgumentException::class.java, { s.open(bad) }, bad)
        }
        assertTrue(StorageKeys.valid("chat-attachments/3f2a.m4a"))
        assertTrue(StorageKeys.valid("doctors/jane doe-7.jpg"), "spaces in legacy file names are allowed")
    }
}

class LocalFileStorageMissingRootTest {
    @TempDir
    lateinit var parent: Path

    @Test
    fun `a root that disappeared makes keys absent and writes recreate it`() {
        val root = parent.resolve("images")
        val s = LocalFileStorage(root)
        java.nio.file.Files.delete(root)
        assertNull(s.open("doctors/a.jpg"))
        assertNull(s.head("doctors/a.jpg"))
        assertTrue(s.delete("doctors/a.jpg"))
        assertEquals(emptyList<StoredObject>(), s.list("").toList())
        s.put("doctors/a.jpg", "x".byteInputStream(), 1, null)
        assertEquals(StoredObject("doctors/a.jpg", 1), s.head("doctors/a.jpg"))
    }
}

class LocalFileStorageSymlinkTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    @Test
    fun `a symlink under the root cannot reach outside it`() {
        val secret = outside.resolve("secret.txt").also { java.nio.file.Files.writeString(it, "s") }
        val dir = java.nio.file.Files.createDirectories(root.resolve("doctors"))
        java.nio.file.Files.createSymbolicLink(dir.resolve("link.txt"), secret)
        java.nio.file.Files.createSymbolicLink(root.resolve("patients"), outside)

        val s = LocalFileStorage(root)
        assertThrows(IllegalArgumentException::class.java) { s.open("doctors/link.txt") }
        assertThrows(IllegalArgumentException::class.java) { s.open("patients/secret.txt") }
        assertThrows(IllegalArgumentException::class.java) { s.put("patients/new.txt", "x".byteInputStream(), 1, null) }
    }
}
