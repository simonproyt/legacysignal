package com.simonproyt.legacysignal.data

data class ThreadEntity(
    val id: Long = 0,
    val recipientNumber: String,
    val lastMessageSnippet: String = "",
    val timestamp: Long = 0,
    val unreadCount: Int = 0
)
