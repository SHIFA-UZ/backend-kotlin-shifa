package com.shifa.shared.storage

import com.shifa.shared.config.AppProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.springframework.beans.factory.ObjectProvider
import java.nio.file.Files
import java.nio.file.Path

class StorageMigrationServiceTest {

    @TempDir
    lateinit var volume: Path

    @TempDir
    lateinit var bucketDir: Path

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    @Test
    fun `copies what is missing, keeps the bucket's version on a conflict, and is idempotent`() {
        val images = volume.resolve("images")
        write(images.resolve("doctors/a.jpg"), "aaa")
        write(images.resolve("patientdocuments/7/r.pdf"), "pdf-bytes")
        write(images.resolve("chat-attachments/c.m4a"), "old")
        write(volume.resolve("private/transcription-feedback/x_meta.json"), "{}")
        val bucket = LocalFileStorage(bucketDir)
        bucket.put("chat-attachments/c.m4a", "newer-upload".byteInputStream(), 12, null)
        bucket.put("doctors/a.jpg", "aaa".byteInputStream(), 3, null)

        val props = AppProperties().apply { storageRoot = images.toString(); publicBaseUrl = "x" }
        @Suppress("UNCHECKED_CAST")
        val noS3 = mock(ObjectProvider::class.java) as ObjectProvider<S3FileStorage>
        val svc = StorageMigrationService(props, noS3)

        val dry = svc.runOnce(bucket, dryRun = true)
        assertEquals(listOf(4, 2, 1, 1, 0), listOf(dry.scanned, dry.copied, dry.skipped, dry.conflicts, dry.failed))
        assertEquals(null, bucket.head("patientdocuments/7/r.pdf"), "dry run copies nothing")

        val first = svc.runOnce(bucket, dryRun = false)
        assertEquals("done", first.state)
        assertEquals(listOf(4, 2, 1, 1, 0), listOf(first.scanned, first.copied, first.skipped, first.conflicts, first.failed))
        assertEquals("pdf-bytes", Files.readString(bucketDir.resolve("patientdocuments/7/r.pdf")))
        assertEquals("{}", Files.readString(bucketDir.resolve("private/transcription-feedback/x_meta.json")))
        assertEquals("newer-upload", Files.readString(bucketDir.resolve("chat-attachments/c.m4a")), "the bucket's newer object is kept")

        val second = svc.runOnce(bucket, dryRun = false)
        assertEquals(listOf(4, 0, 3, 1, 0), listOf(second.scanned, second.copied, second.skipped, second.conflicts, second.failed))
    }
}
