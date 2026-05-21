/*
 * Copyright (c) 2024 Stellon. All rights reserved.
 * Proprietary and Confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */

package com.stellon.mobile.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

public class ChatSession internal constructor(
    private val client: BitnetClient,
    systemPrompt: String?,
    private val template: ChatTemplate,
) {
    private val history = mutableListOf<ChatMessage>()

    init {
        if (!systemPrompt.isNullOrBlank()) {
            history += ChatMessage(Role.System, systemPrompt)
        }
    }

    public fun messages(): List<ChatMessage> = history.toList()

    public suspend fun send(
        content: String,
        parameters: GenerationParameters = GenerationParameters(),
    ): GenerationResult {
        val builder = StringBuilder()
        sendStreaming(content, parameters).collect { builder.append(it.text) }
        return GenerationResult(builder.toString(), tokenCount = -1)
    }

    public fun sendStreaming(
        content: String,
        parameters: GenerationParameters = GenerationParameters(),
    ): Flow<TokenChunk> {
        require(content.isNotBlank()) { "message content must not be blank" }
        history += ChatMessage(Role.User, content)
        val response = StringBuilder()
        return client.stream(template.render(history), parameters)
            .onEach { chunk ->
                if (chunk.index >= 0) response.append(chunk.text)
            }
            .onCompletion { error ->
                if (error == null && response.isNotEmpty()) {
                    history += ChatMessage(Role.Assistant, response.toString())
                }
            }
    }
}
