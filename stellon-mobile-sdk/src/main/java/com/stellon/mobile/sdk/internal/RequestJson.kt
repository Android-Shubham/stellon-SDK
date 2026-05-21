package com.stellon.mobile.sdk.internal

import com.stellon.mobile.sdk.GenerationParameters
import org.json.JSONArray
import org.json.JSONObject

internal object RequestJson {
    fun encode(prompt: String, parameters: GenerationParameters): String =
        JSONObject()
            .put("prompt", prompt)
            .put("maxTokens", parameters.maxTokens)
            .put("temperature", parameters.temperature)
            .put("topK", parameters.topK)
            .put("topP", parameters.topP)
            .put("repetitionPenalty", parameters.repetitionPenalty)
            .put("stopSequences", JSONArray(parameters.stopSequences))
            .toString()
}
