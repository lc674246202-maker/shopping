package com.lc.shoppingrecord.ai

import com.lc.shoppingrecord.model.ConfirmedOrderDraft

data class RecognitionBatch(
    val orders: List<ConfirmedOrderDraft>,
    val rawJson: String
)

sealed interface RecognitionOutcome {
    data class Success(val taskId: String, val batch: RecognitionBatch) : RecognitionOutcome
    data class Failure(val taskId: String, val message: String, val retryable: Boolean) : RecognitionOutcome
    data class Cancelled(val taskId: String) : RecognitionOutcome
}

class RecognitionException(
    override val message: String,
    val retryable: Boolean = false
) : Exception(message)
