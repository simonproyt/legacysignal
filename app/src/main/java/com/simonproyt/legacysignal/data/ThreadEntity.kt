package com.simonproyt.legacysignal.data

data class ThreadEntity(
    val id: Long = 0,
    val recipientNumber: String,
    var name: String? = null,
    var lastMessageSnippet: String = "",
    var timestamp: Long = 0,
    var unreadCount: Int = 0
)
