package com.stellon.mobile.sdk.internal

import com.stellon.mobile.sdk.CancellationToken
import com.stellon.mobile.sdk.StellonSdkException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class NativeBitnetBridge : Closeable {
    private val closed = AtomicBoolean(false)
    private var handle: Long = nativeCreate()

    fun loadModel(path: String) {
        ensureOpen()
        try {
            nativeLoadModel(handle, path)
        } catch (error: RuntimeException) {
            throw StellonSdkException.ModelLoadFailed("Native runtime could not load model at $path", error)
        }
    }

    fun generate(
        requestJson: String,
        cancellationToken: CancellationToken,
        onToken: (String, Int) -> Unit,
    ) {
        ensureOpen()
        val callback = NativeTokenCallback(onToken)
        try {
            nativeGenerate(handle, requestJson, callback, cancellationToken)
        } catch (error: RuntimeException) {
            throw StellonSdkException.NativeInferenceFailed("Native generation failed", error)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) { "Native bridge is closed." }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoadModel(handle: Long, modelPath: String)
    private external fun nativeGenerate(
        handle: Long,
        requestJson: String,
        callback: NativeTokenCallback,
        cancellationToken: CancellationToken,
    )

    private companion object {
        init {
            System.loadLibrary("stellon_bitnet")
        }
    }
}

internal class NativeTokenCallback(
    private val onToken: (String, Int) -> Unit,
) {
    @Suppress("unused")
    fun onToken(token: String, index: Int) {
        onToken.invoke(token, index)
    }
}
