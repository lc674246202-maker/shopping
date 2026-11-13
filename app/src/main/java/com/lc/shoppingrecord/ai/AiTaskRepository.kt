package com.lc.shoppingrecord.ai

import android.content.ContentValues
import com.lc.shoppingrecord.data.ShoppingDatabaseHelper
import org.json.JSONArray
import java.time.Instant

data class AiTask(
    val taskId: String,
    val status: String,
    val imageReferences: List<String>,
    val recognizedJson: String?,
    val errorMessage: String?,
    val retryCount: Int
)

class AiTaskRepository(private val databaseHelper: ShoppingDatabaseHelper) {
    fun get(taskId: String): AiTask? {
        databaseHelper.readableDatabase.query(
            "ai_tasks",
            arrayOf(
                "task_id", "status", "image_paths_json", "recognized_json",
                "error_message", "retry_count"
            ),
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val refs = JSONArray(cursor.getString(2)).let { array ->
                (0 until array.length()).map(array::getString)
            }
            return AiTask(
                taskId = cursor.getString(0),
                status = cursor.getString(1),
                imageReferences = refs,
                recognizedJson = cursor.getStringOrNull(3),
                errorMessage = cursor.getStringOrNull(4),
                retryCount = cursor.getInt(5)
            )
        }
    }

    fun markProcessing(taskId: String) {
        update(taskId, ContentValues().apply {
            put("status", STATUS_PROCESSING)
            putNull("error_message")
            putNull("finished_at")
        })
    }

    fun markSuccess(taskId: String, recognizedJson: String) {
        update(taskId, ContentValues().apply {
            put("status", STATUS_SUCCESS)
            put("recognized_json", recognizedJson)
            putNull("error_message")
            put("finished_at", Instant.now().toString())
        })
    }

    fun markFailed(taskId: String, message: String, incrementRetry: Boolean = true) {
        val task = get(taskId) ?: return
        update(taskId, ContentValues().apply {
            put("status", STATUS_FAILED)
            put("error_message", message.take(MAX_ERROR_LENGTH))
            put("retry_count", task.retryCount + if (incrementRetry) 1 else 0)
            put("finished_at", Instant.now().toString())
        })
    }

    fun markCancelled(taskId: String) {
        update(taskId, ContentValues().apply {
            put("status", STATUS_CANCELLED)
            put("finished_at", Instant.now().toString())
        })
    }

    fun resetForRetry(taskId: String) {
        update(taskId, ContentValues().apply {
            put("status", STATUS_PENDING)
            putNull("error_message")
            putNull("recognized_json")
            putNull("finished_at")
        })
    }

    fun recoverInterruptedTasks(): Int {
        val database = databaseHelper.writableDatabase
        val count = database.rawQuery(
            "SELECT COUNT(*) FROM ai_tasks WHERE status=?", arrayOf(STATUS_PROCESSING)
        ).use { it.moveToFirst(); it.getInt(0) }
        if (count > 0) {
            database.execSQL(
                """UPDATE ai_tasks
                   SET status=?, error_message=?, retry_count=retry_count+1, finished_at=?
                   WHERE status=?""".trimIndent(),
                arrayOf(
                    STATUS_FAILED,
                    "应用上次运行时识别被中断，可重新尝试",
                    Instant.now().toString(),
                    STATUS_PROCESSING
                )
            )
        }
        return count
    }

    fun deleteFinishedTasks(): Int = databaseHelper.writableDatabase.delete(
        "ai_tasks",
        "status IN (?, ?, ?)",
        arrayOf(STATUS_SUCCESS, STATUS_FAILED, STATUS_CANCELLED)
    )

    fun finishedTasks(): List<AiTask> = databaseHelper.readableDatabase.query(
        "ai_tasks",
        arrayOf(
            "task_id", "status", "image_paths_json", "recognized_json",
            "error_message", "retry_count"
        ),
        "status IN (?, ?, ?)",
        arrayOf(STATUS_SUCCESS, STATUS_FAILED, STATUS_CANCELLED),
        null,
        null,
        "finished_at DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val refs = JSONArray(cursor.getString(2)).let { array ->
                    (0 until array.length()).map(array::getString)
                }
                add(
                    AiTask(
                        cursor.getString(0), cursor.getString(1), refs,
                        cursor.getStringOrNull(3), cursor.getStringOrNull(4), cursor.getInt(5)
                    )
                )
            }
        }
    }

    fun deleteTask(taskId: String): Int = databaseHelper.writableDatabase.delete(
        "ai_tasks", "task_id=?", arrayOf(taskId)
    )

    private fun update(taskId: String, values: ContentValues) {
        val rows = databaseHelper.writableDatabase.update(
            "ai_tasks",
            values,
            "task_id = ?",
            arrayOf(taskId)
        )
        check(rows == 1) { "AI task not found" }
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
        private const val MAX_ERROR_LENGTH = 500
    }
}
