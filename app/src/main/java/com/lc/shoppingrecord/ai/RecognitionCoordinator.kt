package com.lc.shoppingrecord.ai

import android.net.Uri
import com.lc.shoppingrecord.security.ApiKeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class RecognitionCoordinator(
    private val taskRepository: AiTaskRepository,
    private val apiKeyStore: ApiKeyStore,
    private val client: DeepSeekRecognitionClient,
    private val callback: (RecognitionOutcome) -> Unit
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong(0)
    @Volatile private var activeTaskId: String? = null

    fun start(taskId: String, imageUris: List<Uri>) {
        val apiKey = apiKeyStore.load()
            ?: throw RecognitionException("请先在设置中填写 DeepSeek API Key", false)
        val runGeneration = generation.incrementAndGet()
        activeTaskId = taskId
        taskRepository.markProcessing(taskId)
        executor.execute {
            try {
                val batch = client.recognize(apiKey, imageUris, taskId)
                if (generation.get() != runGeneration) return@execute
                taskRepository.markSuccess(taskId, batch.rawJson)
                activeTaskId = null
                callback(RecognitionOutcome.Success(taskId, batch))
            } catch (error: RecognitionException) {
                if (generation.get() != runGeneration) return@execute
                taskRepository.markFailed(taskId, error.message)
                activeTaskId = null
                callback(RecognitionOutcome.Failure(taskId, error.message, error.retryable))
            } catch (_: Exception) {
                if (generation.get() != runGeneration) return@execute
                val message = "识别发生未知错误，请重试"
                taskRepository.markFailed(taskId, message)
                activeTaskId = null
                callback(RecognitionOutcome.Failure(taskId, message, true))
            }
        }
    }

    fun retry(taskId: String) {
        val task = taskRepository.get(taskId) ?: return
        taskRepository.resetForRetry(taskId)
        start(taskId, task.imageReferences.map(Uri::parse))
    }

    fun cancel() {
        val taskId = activeTaskId ?: return
        generation.incrementAndGet()
        activeTaskId = null
        taskRepository.markCancelled(taskId)
        callback(RecognitionOutcome.Cancelled(taskId))
    }

    fun close() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }
}
