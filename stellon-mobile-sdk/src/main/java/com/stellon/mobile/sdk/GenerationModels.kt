/*
 * Copyright (c) 2024 Stellon. All rights reserved.
 * Proprietary and Confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */

package com.stellon.mobile.sdk

public data class GenerationParameters(
    val maxTokens: Int = 128,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val repetitionPenalty: Float = 1.1f,
    val stopSequences: List<String> = emptyList(),
) {
    init {
        require(maxTokens > 0) { "maxTokens must be greater than 0" }
        require(temperature >= 0f) { "temperature must be non-negative" }
        require(topK >= 0) { "topK must be non-negative" }
        require(topP in 0f..1f) { "topP must be between 0 and 1" }
        require(repetitionPenalty > 0f) { "repetitionPenalty must be greater than 0" }
    }
}

public data class GenerationResult(
    val text: String,
    val tokenCount: Int,
)

public data class TokenChunk(
    val text: String,
    val index: Int,
    val finishReason: FinishReason?,
)

public enum class FinishReason {
    Stop,
    Cancelled,
    Error,
}
