package com.shifa.web

import org.apache.catalina.connector.ClientAbortException
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException

/**
 * Helpers for SSE streams: detect client disconnects and avoid error-handler cascades.
 */
object SseIoSupport {

    fun isClientAbort(t: Throwable?): Boolean {
        var ex = t
        while (ex != null) {
            when (ex) {
                is AsyncRequestNotUsableException -> return true
                is ClientAbortException -> return true
                is IOException -> {
                    val msg = ex.message?.lowercase().orEmpty()
                    if (
                        msg.contains("broken pipe") ||
                        msg.contains("connection reset") ||
                        msg.contains("connection abort")
                    ) {
                        return true
                    }
                }
            }
            ex = ex.cause
        }
        return false
    }

    /** Sends one SSE data frame; returns false when the client already disconnected. */
    fun sendText(emitter: SseEmitter, token: String): Boolean =
        try {
            emitter.send(token)
            true
        } catch (ex: Exception) {
            if (isClientAbort(ex)) false else throw ex
        }

    fun sendEvent(emitter: SseEmitter, event: SseEmitter.SseEventBuilder): Boolean =
        try {
            emitter.send(event)
            true
        } catch (ex: Exception) {
            if (isClientAbort(ex)) false else throw ex
        }

    fun completeQuietly(emitter: SseEmitter) {
        try {
            emitter.complete()
        } catch (_: Exception) {
            // Client may already be gone.
        }
    }

    fun finish(emitter: SseEmitter, error: Throwable? = null) {
        if (error != null && !isClientAbort(error)) {
            try {
                emitter.completeWithError(error)
            } catch (ex: Exception) {
                if (!isClientAbort(ex)) throw ex
                completeQuietly(emitter)
            }
            return
        }
        completeQuietly(emitter)
    }
}
