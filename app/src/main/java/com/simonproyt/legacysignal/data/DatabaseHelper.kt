package com.simonproyt.legacysignal.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val dbChangeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS threads (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                recipientNumber TEXT NOT NULL,
                lastMessageSnippet TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                unreadCount INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                threadId INTEGER NOT NULL,
                senderId TEXT NOT NULL,
                body TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                isOutgoing INTEGER NOT NULL,
                FOREIGN KEY(threadId) REFERENCES threads(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_threadId ON messages(threadId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS threads")
        onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            db.execSQL("PRAGMA foreign_keys=ON;")
        }
    }

    private fun notifyDbChanged() {
        dbChangeSignal.tryEmit(Unit)
    }

    // --- ThreadDao Methods ---

    fun getAllThreads(): Flow<List<ThreadEntity>> = flow {
        emit(queryAllThreads())
        dbChangeSignal.collect {
            emit(queryAllThreads())
        }
    }.flowOn(Dispatchers.IO)

    private fun queryAllThreads(): List<ThreadEntity> {
        val list = mutableListOf<ThreadEntity>()
        readableDatabase.rawQuery("SELECT * FROM threads ORDER BY timestamp DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    ThreadEntity(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        recipientNumber = cursor.getString(cursor.getColumnIndexOrThrow("recipientNumber")),
                        lastMessageSnippet = cursor.getString(cursor.getColumnIndexOrThrow("lastMessageSnippet")),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                        unreadCount = cursor.getInt(cursor.getColumnIndexOrThrow("unreadCount"))
                    )
                )
            }
        }
        return list
    }

    suspend fun getThreadByRecipient(recipientNumber: String): ThreadEntity? = withContext(Dispatchers.IO) {
        var thread: ThreadEntity? = null
        readableDatabase.rawQuery("SELECT * FROM threads WHERE recipientNumber = ? LIMIT 1", arrayOf(recipientNumber)).use { cursor ->
            if (cursor.moveToFirst()) {
                thread = ThreadEntity(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    recipientNumber = cursor.getString(cursor.getColumnIndexOrThrow("recipientNumber")),
                    lastMessageSnippet = cursor.getString(cursor.getColumnIndexOrThrow("lastMessageSnippet")),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                    unreadCount = cursor.getInt(cursor.getColumnIndexOrThrow("unreadCount"))
                )
            }
        }
        thread
    }

    suspend fun getThreadById(id: Long): ThreadEntity? = withContext(Dispatchers.IO) {
        var thread: ThreadEntity? = null
        readableDatabase.rawQuery("SELECT * FROM threads WHERE id = ? LIMIT 1", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) {
                thread = ThreadEntity(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    recipientNumber = cursor.getString(cursor.getColumnIndexOrThrow("recipientNumber")),
                    lastMessageSnippet = cursor.getString(cursor.getColumnIndexOrThrow("lastMessageSnippet")),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                    unreadCount = cursor.getInt(cursor.getColumnIndexOrThrow("unreadCount"))
                )
            }
        }
        thread
    }

    suspend fun insertThread(thread: ThreadEntity): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("recipientNumber", thread.recipientNumber)
            put("lastMessageSnippet", thread.lastMessageSnippet)
            put("timestamp", thread.timestamp)
            put("unreadCount", thread.unreadCount)
        }
        val id = writableDatabase.insertWithOnConflict("threads", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        notifyDbChanged()
        id
    }

    suspend fun updateThread(thread: ThreadEntity) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("recipientNumber", thread.recipientNumber)
            put("lastMessageSnippet", thread.lastMessageSnippet)
            put("timestamp", thread.timestamp)
            put("unreadCount", thread.unreadCount)
        }
        writableDatabase.update("threads", values, "id = ?", arrayOf(thread.id.toString()))
        notifyDbChanged()
    }

    suspend fun updateSnippet(id: Long, snippet: String, timestamp: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("lastMessageSnippet", snippet)
            put("timestamp", timestamp)
        }
        writableDatabase.update("threads", values, "id = ?", arrayOf(id.toString()))
        notifyDbChanged()
    }

    // --- MessageDao Methods ---

    fun getMessagesForThread(threadId: Long): Flow<List<MessageEntity>> = flow {
        emit(queryMessagesForThread(threadId))
        dbChangeSignal.collect {
            emit(queryMessagesForThread(threadId))
        }
    }.flowOn(Dispatchers.IO)

    private fun queryMessagesForThread(threadId: Long): List<MessageEntity> {
        val list = mutableListOf<MessageEntity>()
        readableDatabase.rawQuery("SELECT * FROM messages WHERE threadId = ? ORDER BY timestamp ASC", arrayOf(threadId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    MessageEntity(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        threadId = cursor.getLong(cursor.getColumnIndexOrThrow("threadId")),
                        senderId = cursor.getString(cursor.getColumnIndexOrThrow("senderId")),
                        body = cursor.getString(cursor.getColumnIndexOrThrow("body")),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                        isOutgoing = cursor.getInt(cursor.getColumnIndexOrThrow("isOutgoing")) == 1
                    )
                )
            }
        }
        return list
    }

    suspend fun insertMessage(message: MessageEntity): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("threadId", message.threadId)
            put("senderId", message.senderId)
            put("body", message.body)
            put("timestamp", message.timestamp)
            put("isOutgoing", if (message.isOutgoing) 1 else 0)
        }
        val id = writableDatabase.insert("messages", null, values)
        notifyDbChanged()
        id
    }

    companion object {
        const val DATABASE_NAME = "signal_database"
        const val DATABASE_VERSION = 1

        @Volatile
        private var instance: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
