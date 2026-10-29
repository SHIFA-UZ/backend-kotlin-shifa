package com.shifa.shared.storage

import com.shifa.shared.config.AppProperties

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.time.Duration
import java.nio.file.Path

/** `app.storage.s3.*`: the bucket uploads go to when enabled (roadmap 2.7). Keys come from Railway reference variables. */
@Component
@ConfigurationProperties(prefix = "app.storage.s3")
class S3StorageProperties {
    var enabled: Boolean = false
    var endpoint: String = ""
    var region: String = "auto"
    var bucket: String = ""
    var accessKey: String = ""
    var secretKey: String = ""
    /** Older Railway buckets need path-style URLs; the bucket's Credentials tab says which. */
    var forcePathStyle: Boolean = false
}

@Configuration
class FileStorageConfig {
    private val log = LoggerFactory.getLogger(FileStorageConfig::class.java)

    /** A bean so Spring closes it on shutdown. */
    @Bean
    @ConditionalOnProperty("app.storage.s3.enabled", havingValue = "true")
    fun s3Client(props: S3StorageProperties): S3Client {
        require(props.bucket.isNotBlank() && props.endpoint.isNotBlank()) { "app.storage.s3 needs endpoint and bucket" }
        return S3Client.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)))
            .forcePathStyle(props.forcePathStyle)
            // S3-compatible stores do not all accept the SDK's default CRC32 request checksums.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            // The SDK has no deadline by default; a 25 MB upload or download on a slow link fits in five minutes.
            .overrideConfiguration { it.apiCallAttemptTimeout(Duration.ofMinutes(5)).apiCallTimeout(Duration.ofMinutes(5)) }
            .build()
    }

    @Bean
    @ConditionalOnProperty("app.storage.s3.enabled", havingValue = "true")
    fun s3FileStorage(client: S3Client, props: S3StorageProperties): S3FileStorage {
        log.info("Uploads go to bucket {} at {}", props.bucket, props.endpoint)
        return S3FileStorage(client, props.bucket)
    }

    /** The storage every writer and the file controller use: the bucket with the volume as read fallback, or the volume alone. */
    @Bean
    @Primary
    fun fileStorage(app: AppProperties, s3: ObjectProvider<S3FileStorage>): FileStorage {
        val local = LocalFileStorage(Path.of(app.storageRoot))
        return s3.ifAvailable?.let { FallbackFileStorage(it, local) } ?: local
    }
}
