package com.shifa.shared.storage

import org.springframework.core.io.AbstractResource
import org.springframework.core.io.Resource
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream

/** Objects in one S3-compatible bucket (Railway Buckets run on Tigris; the API is plain S3). */
class S3FileStorage(private val client: S3Client, private val bucket: String) : FileStorage {

    override fun put(key: String, content: InputStream, size: Long, contentType: String?) {
        val request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(StorageKeys.require(key))
            .contentLength(size)
            .apply { if (!contentType.isNullOrBlank()) contentType(contentType) }
            .build()
        client.putObject(request, RequestBody.fromInputStream(content, size))
    }

    /** HEAD now, GET only when a reader asks for the stream: a caller that gives up early holds no connection. */
    override fun open(key: String): Resource? = headResponse(key)?.let { S3ObjectResource(client, bucket, key, it) }

    override fun head(key: String): StoredObject? = headResponse(key)?.let { StoredObject(key, it.contentLength()) }

    private fun headResponse(key: String): HeadObjectResponse? = try {
        client.headObject(HeadObjectRequest.builder().bucket(bucket).key(StorageKeys.require(key)).build())
    } catch (_: NoSuchKeyException) {
        null
    }

    override fun delete(key: String): Boolean {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(StorageKeys.require(key)).build())
        return true
    }

    override fun list(prefix: String): Sequence<StoredObject> =
        client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
            .contents().asSequence()
            .map { StoredObject(it.key(), it.size()) }
}

/**
 * One object as a Spring resource. Every [getInputStream] is a fresh GET, so Spring's range handling, which closes
 * and reopens the stream between ranges, and consumers that read twice both work; the size comes from the HEAD.
 */
class S3ObjectResource(
    private val client: S3Client,
    private val bucket: String,
    private val key: String,
    private val head: HeadObjectResponse,
) : AbstractResource() {
    override fun getDescription(): String = "s3 object [${key.substringBeforeLast('/', "")}/…]"
    override fun getInputStream(): InputStream = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
    override fun exists(): Boolean = true
    override fun contentLength(): Long = head.contentLength()
    override fun lastModified(): Long = head.lastModified()?.toEpochMilli() ?: 0L
    override fun getFilename(): String = key.substringAfterLast('/')
}
