package com.shifa.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "scribe")
class ScribeProperties {

    /** Temp directory for scribe recordings (downloads, uploads). */
    var tempDir: String = "./public-storage/images/scribe-temp"

    /** How long to keep temp files before cleanup (minutes). */
    var tempRetentionMinutes: Int = 60

    /** Max audio file size in bytes (Whisper limit 25 MB). */
    var maxAudioSizeBytes: Long = 26_214_400L

    /** Daily webhook secret for signature verification (optional). Mapped from daily-webhook-secret. */
    var dailyWebhookSecret: String = ""

    /** S3 bucket for Daily recordings (if Daily is configured with user's S3). */
    var s3Bucket: String = ""

    /** AWS region for S3. */
    var s3Region: String = "us-east-1"
}
