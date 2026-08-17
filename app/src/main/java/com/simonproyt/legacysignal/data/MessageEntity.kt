package com.simonproyt.legacysignal.data

data class MessageEntity(
    val id: Long = 0,
    val threadId: Long,
    val senderId: String,
    val body: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val imagePath: String? = null
)
