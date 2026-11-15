package hu.personal.phoneagent

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val time: String
)
