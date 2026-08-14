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
                recipientNumber TEXT NOT NULL UNIQUE,
                name TEXT,
                lastMessageSnippet TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                unreadCount INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                thread_id INTEGER,
                sender_id TEXT,
                text TEXT,
                timestamp INTEGER,
                is_outgoing INTEGER,
                status INTEGER,
                FOREIGN KEY(thread_id) REFERENCES $TABLE_THREADS(id)
            )
            """.trimIndent()
        )
        
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CONTACTS (
                uuid TEXT PRIMARY KEY,
                name TEXT,
                about TEXT,
                profile_key TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_threadId ON $TABLE_MESSAGES(thread_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_CONTACTS (
                    uuid TEXT PRIMARY KEY,
                    name TEXT,
                    about TEXT,
                    profile_key TEXT
                )
                """.trimIndent()
            )
        }
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
        readableDatabase.rawQuery("SELECT * FROM $TABLE_THREADS ORDER BY timestamp DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    ThreadEntity(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        recipientNumber = cursor.getString(cursor.getColumnIndexOrThrow("recipientNumber")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
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
        readableDatabase.rawQuery("SELECT * FROM $TABLE_THREADS WHERE recipientNumber = ? LIMIT 1", arrayOf(recipientNumber)).use { cursor ->
            if (cursor.moveToFirst()) {
                thread = ThreadEntity(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    recipientNumber = cursor.getString(cursor.getColumnIndexOrThrow("recipientNumber")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
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
        readableDatabase.rawQuery("SELECT * FROM $TABLE_THREADS WHERE id = ? LIMIT 1", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) {
                thread = ThreadEntity(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    recipientNumber = cursor.getString(cursor.getColumnIndexOrThrow("recipientNumber")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
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
            put("name", thread.name)
            put("lastMessageSnippet", thread.lastMessageSnippet)
            put("timestamp", thread.timestamp)
            put("unreadCount", thread.unreadCount)
        }
        val id = writableDatabase.insertWithOnConflict(TABLE_THREADS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        notifyDbChanged()
        id
    }

    suspend fun updateThread(thread: ThreadEntity) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("recipientNumber", thread.recipientNumber)
            put("name", thread.name)
            put("lastMessageSnippet", thread.lastMessageSnippet)
            put("timestamp", thread.timestamp)
            put("unreadCount", thread.unreadCount)
        }
        writableDatabase.update(TABLE_THREADS, values, "id = ?", arrayOf(thread.id.toString()))
        notifyDbChanged()
    }

    suspend fun updateSnippet(id: Long, snippet: String, timestamp: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("lastMessageSnippet", snippet)
            put("timestamp", timestamp)
        }
        writableDatabase.update(TABLE_THREADS, values, "id = ?", arrayOf(id.toString()))
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
        readableDatabase.rawQuery("SELECT * FROM $TABLE_MESSAGES WHERE thread_id = ? ORDER BY timestamp ASC", arrayOf(threadId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    MessageEntity(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        threadId = cursor.getLong(cursor.getColumnIndexOrThrow("thread_id")),
                        senderId = cursor.getString(cursor.getColumnIndexOrThrow("sender_id")),
                        body = cursor.getString(cursor.getColumnIndexOrThrow("text")),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                        isOutgoing = cursor.getInt(cursor.getColumnIndexOrThrow("is_outgoing")) == 1
                    )
                )
            }
        }
        return list
    }

    suspend fun insertMessage(message: MessageEntity): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("thread_id", message.threadId)
            put("sender_id", message.senderId)
            put("text", message.body)
            put("timestamp", message.timestamp)
            put("is_outgoing", if (message.isOutgoing) 1 else 0)
        }
        val id = writableDatabase.insert(TABLE_MESSAGES, null, values)
        notifyDbChanged()
        id
    }

    suspend fun deleteMessage(id: Long) {
        val db = writableDatabase
        db.delete(TABLE_MESSAGES, "id = ?", arrayOf(id.toString()))
        notifyDbChanged()
    }
    
    fun saveContact(uuid: String, name: String?, about: String?, profileKey: String?) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("uuid", uuid)
            if (name != null) put("name", name)
            if (about != null) put("about", about)
            if (profileKey != null) put("profile_key", profileKey)
        }
        db.insertWithOnConflict(TABLE_CONTACTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    
    fun getContactName(uuid: String): String? {
        val db = readableDatabase
        val cursor = db.query(TABLE_CONTACTS, arrayOf("name"), "uuid = ?", arrayOf(uuid), null, null, null)
        var name: String? = null
        if (cursor.moveToFirst()) {
            name = cursor.getString(0)
        }
        cursor.close()
        return name
    }

    companion object {
        private const val DATABASE_NAME = "signal_db"
        private const val DATABASE_VERSION = 2

        private const val TABLE_THREADS = "threads"
        private const val TABLE_MESSAGES = "messages"
        private const val TABLE_CONTACTS = "contacts"

        @Volatile
        private var instance: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
