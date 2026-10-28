package com.zhuanformat.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The callback is invoked on the native conversion worker, never the UI thread. */
fun interface NativeProgressCallback {
    fun onProgress(fraction: Float)
}

object NativeAudioBridge {
    init { System.loadLibrary("converter_audio") }

    external fun createSession(): Long
    external fun cancelSession(id: Long)
    external fun releaseSession(id: Long)
    external fun probe(id: Long, inputPath: String): String
    external fun convert(
        id: Long, inputPath: String, outputPath: String, format: String,
        sampleRate: Int, channels: Int, bitrate: Int, callback: NativeProgressCallback
    )
}

/** Wait for native cleanup before allowing the service to delete or publish files. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T> nativeAudioSession(block: (Long) -> T): T = coroutineScope {
    suspendCancellableCoroutine { continuation ->
        val session = NativeAudioBridge.createSession()
        continuation.invokeOnCancellation { NativeAudioBridge.cancelSession(session) }
        // ATOMIC ensures a cancellation before worker dispatch cannot leak the session.
        launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            try {
                val result = block(session)
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                NativeAudioBridge.releaseSession(session)
            }
        }
    }
}
