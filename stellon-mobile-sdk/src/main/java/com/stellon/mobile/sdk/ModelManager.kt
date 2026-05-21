/*
 * Copyright (c) 2024 Stellon. All rights reserved.
 * Proprietary and Confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */

package com.stellon.mobile.sdk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

public class ModelManager internal constructor(
    context: Context,
    cacheDirectoryName: String,
) {
    private val modelDirectory = File(context.filesDir, cacheDirectoryName).also { it.mkdirs() }

    public suspend fun resolve(
        source: ModelSource,
        listener: DownloadProgressListener? = null,
    ): CachedModel = withContext(Dispatchers.IO) {
        when (source) {
            is ModelSource.LocalFile -> {
                require(source.file.exists()) { "Model file does not exist: ${source.file.absolutePath}" }
                CachedModel(source.id, source.file, source.file.length())
            }
            is ModelSource.Remote -> download(source, listener)
        }
    }

    public fun isDownloaded(source: ModelSource): Boolean {
        return when (source) {
            is ModelSource.LocalFile -> source.file.exists()
            is ModelSource.Remote -> {
                val target = File(modelDirectory, safeFileName(source.id))
                target.exists() && target.length() > 0
            }
        }
    }

    public fun listDownloaded(): List<CachedModel> =
        modelDirectory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
            ?.map { CachedModel(it.nameWithoutExtension, it, it.length()) }
            ?.sortedBy { it.id }
            ?: emptyList()

    public fun diskUsageBytes(): Long = listDownloaded().sumOf { it.bytes }

    public fun delete(modelId: String): Boolean {
        val target = File(modelDirectory, safeFileName(modelId))
        val partial = File(modelDirectory, safeFileName(modelId) + PART_SUFFIX)
        return target.delete() or partial.delete()
    }

    private fun download(
        source: ModelSource.Remote,
        listener: DownloadProgressListener?,
    ): CachedModel {
        val target = File(modelDirectory, safeFileName(source.id))
        if (target.exists() && target.length() > 0) {
            return CachedModel(source.id, target, target.length())
        }

        val partial = File(modelDirectory, target.name + PART_SUFFIX)
        val existingBytes = partial.length().takeIf { source.resume } ?: 0L
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = source.connectTimeoutMillis
            readTimeout = source.readTimeoutMillis
            requestMethod = "GET"
            if (existingBytes > 0L) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299 && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw StellonSdkException.DownloadFailed("Model download failed with HTTP $responseCode from ${source.url}")
            }
            val append = responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            val totalBytes = contentLength(connection, existingBytes)
            RandomAccessFile(partial, "rw").use { output ->
                if (append) output.seek(existingBytes) else output.setLength(0L)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = if (append) existingBytes else 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        listener?.onProgress(DownloadProgress(source.id, downloaded, totalBytes))
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                throw StellonSdkException.DownloadFailed("Could not move downloaded model into cache: ${target.absolutePath}")
            }
            return CachedModel(source.id, target, target.length())
        } catch (error: StellonSdkException) {
            throw error
        } catch (error: Throwable) {
            throw StellonSdkException.DownloadFailed("Could not download model ${source.id}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun contentLength(connection: HttpURLConnection, existingBytes: Long): Long {
        val contentRange = connection.getHeaderField("Content-Range")
        val totalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
        return totalFromRange ?: (connection.contentLengthLong.takeIf { it > 0L }?.plus(existingBytes) ?: -1L)
    }

    private fun safeFileName(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString(separator = "") { "%02x".format(it) }
        return "$digest.gguf"
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}

public sealed class ModelSource {
    public abstract val id: String

    public data class LocalFile(
        override val id: String,
        val file: File,
    ) : ModelSource()

    public data class Remote(
        override val id: String,
        val url: String,
        val resume: Boolean = true,
        val connectTimeoutMillis: Int = 15_000,
        val readTimeoutMillis: Int = 30_000,
    ) : ModelSource()

    public companion object {
        public fun huggingFace(
            repoId: String,
            fileName: String = "model.gguf",
        ): Remote = Remote(
            id = repoId,
            url = "https://huggingface.co/$repoId/resolve/main/$fileName",
        )

        public fun officialBitnetB1582B4T(): Remote = huggingFace(
            repoId = "microsoft/BitNet-b1.58-2B-4T-gguf",
            fileName = "ggml-model-i2_s.gguf",
        )
    }
}

public data class CachedModel(
    val id: String,
    val file: File,
    val bytes: Long,
)

public data class DownloadProgress(
    val modelId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    public val fraction: Float?
        get() = if (totalBytes > 0L) downloadedBytes.toFloat() / totalBytes else null
}

public fun interface DownloadProgressListener {
    public fun onProgress(progress: DownloadProgress)
}
