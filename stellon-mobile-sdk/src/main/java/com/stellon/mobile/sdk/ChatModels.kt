package com.stellon.mobile.sdk

public enum class Role {
    System,
    User,
    Assistant,
}

public data class ChatMessage(
    val role: Role,
    val content: String,
)

public class ChatTemplate private constructor(
    private val renderer: (List<ChatMessage>) -> String,
) {
    public fun render(messages: List<ChatMessage>): String = renderer(messages)

    public companion object {
        public fun default(): ChatTemplate = ChatTemplate { messages ->
            buildString {
                messages.forEach { message ->
                    append("<|")
                    append(message.role.name.lowercase())
                    append("|>\n")
                    append(message.content.trim())
                    append('\n')
                }
                append("<|assistant|>\n")
            }
        }
    }
}
