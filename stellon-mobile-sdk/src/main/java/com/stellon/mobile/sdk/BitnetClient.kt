package com.stellon.mobile.sdk

import android.content.Context
import com.stellon.mobile.sdk.internal.NativeBitnetBridge
import com.stellon.mobile.sdk.internal.RequestJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

public class BitnetClient private constructor(
    context: Context,
    public val options: BitnetClientOptions,
    private val dispatcher: CoroutineDispatcher,
) : Closeable {
    private val appContext = context.applicationContext
    private val bridge = NativeBitnetBridge()
    private val generationMutex = Mutex()
    private var activeModel: CachedModel? = null
    private var closed = false

    public val models: ModelManager = ModelManager(appContext, options.cacheDirectoryName)

    public companion object {
        public fun create(
            context: Context,
            options: BitnetClientOptions = BitnetClientOptions(),
        ): BitnetClient = BitnetClient(context, options, Dispatchers.IO)
    }

    public suspend fun loadModel(
        source: ModelSource,
        listener: DownloadProgressListener? = null,
    ): CachedModel {
        ensureOpen()
        val model = models.resolve(source, listener)
        withContext(dispatcher) {
            bridge.loadModel(model.file.absolutePath)
        }
        activeModel = model
        return model
    }

    public fun createChatSession(
        systemPrompt: String? = null,
        template: ChatTemplate = ChatTemplate.default(),
    ): ChatSession = ChatSession(this, systemPrompt, template)

    public suspend fun generate(
        prompt: String,
        parameters: GenerationParameters = GenerationParameters(),
    ): GenerationResult {
        val chunks = mutableListOf<TokenChunk>()
        stream(prompt, parameters).collect(chunks::add)
        return GenerationResult(chunks.joinToString(separator = "") { it.text }, chunks.size)
    }

    public fun stream(
        prompt: String,
        parameters: GenerationParameters = GenerationParameters(),
    ): Flow<TokenChunk> = callbackFlow {
        ensureOpen()
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        require(activeModel != null) { "No model is loaded. Call loadModel() before inference." }

        val cancellationToken = CancellationToken()
        val request = RequestJson.encode(prompt, parameters)

        val job = CoroutineScope(dispatcher).launch {
            generationMutex.withLock {
                try {
                    bridge.generate(
                        request,
                        cancellationToken,
                        onToken = { token, index ->
                            trySend(TokenChunk(token, index, finishReason = null))
                        },
                    )
                    trySend(TokenChunk("", -1, finishReason = FinishReason.Stop))
                    close()
                } catch (error: Throwable) {
                    close(error)
                }
            }
        }

        awaitClose {
            cancellationToken.cancel()
            job.cancel()
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            bridge.close()
        }
    }

    private fun ensureOpen() {
        check(!closed) { "BitnetClient is closed." }
    }
}
