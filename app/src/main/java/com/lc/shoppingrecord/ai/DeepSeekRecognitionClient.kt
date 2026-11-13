package com.lc.shoppingrecord.ai

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class DeepSeekRecognitionClient(private val contentResolver: ContentResolver) {
    fun recognize(apiKey: String, imageUris: List<Uri>, taskId: String): RecognitionBatch {
        require(imageUris.size in 1..5)
        val content = JSONArray().put(JSONObject().apply {
            put("type", "text")
            put("text", RECOGNITION_PROMPT)
        })
        var totalBytes = 0
        imageUris.forEach { uri ->
            val image = readImage(uri)
            totalBytes += image.bytes.size
            if (totalBytes > MAX_TOTAL_RAW_BYTES) {
                throw RecognitionException("所选图片总大小过大，请减少图片或先压缩", false)
            }
            content.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:${image.mimeType};base64,${Base64.encodeToString(image.bytes, Base64.NO_WRAP)}")
                    put("detail", "original")
                })
            })
        }
        val request = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
            put("response_format", JSONObject().put("type", "json_object"))
            put("max_tokens", 8192)
        }
        val response = post(apiKey, request)
        val contentText = response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: throw RecognitionException("AI 返回了空白结果，请重试", true)
        return DeepSeekRecognitionParser.parse(contentText, taskId)
    }

    fun testConnection(apiKey: String) {
        val request = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "请只输出 JSON：{\"ok\":true}")
            }))
            put("response_format", JSONObject().put("type", "json_object"))
            put("max_tokens", 32)
        }
        post(apiKey, request)
    }

    private fun post(apiKey: String, body: JSONObject): JSONObject {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw mapHttpError(status, responseText)
            return try {
                JSONObject(responseText)
            } catch (_: Exception) {
                throw RecognitionException("AI 服务响应格式无效", true)
            }
        } catch (error: RecognitionException) {
            throw error
        } catch (_: IOException) {
            throw RecognitionException("网络连接失败，请检查网络后重试", true)
        } finally {
            connection.disconnect()
        }
    }

    private fun readImage(uri: Uri): ImageBytes {
        val mimeType = contentResolver.getType(uri)?.lowercase()
            ?.takeIf(SUPPORTED_MIME_TYPES::contains)
            ?: "image/jpeg"
        val output = ByteArrayOutputStream()
        val input = contentResolver.openInputStream(uri)
            ?: throw RecognitionException("无法读取所选图片", false)
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_SINGLE_IMAGE_BYTES) {
                    throw RecognitionException("单张图片过大，请先压缩后重试", false)
                }
                output.write(buffer, 0, read)
            }
        }
        return ImageBytes(mimeType, output.toByteArray())
    }

    private fun mapHttpError(status: Int, response: String): RecognitionException {
        val apiMessage = try {
            JSONObject(response).optJSONObject("error")?.optString("message")
        } catch (_: Exception) {
            null
        }
        return when (status) {
            401, 403 -> RecognitionException("API Key 无效或没有权限，请到设置中检查", false)
            402 -> RecognitionException("DeepSeek 账户余额不足，请充值后重试", false)
            408, 429 -> RecognitionException("AI 服务繁忙或请求过多，请稍后重试", true)
            in 500..599 -> RecognitionException("AI 服务暂时不可用，请稍后重试", true)
            else -> RecognitionException(apiMessage?.take(160) ?: "AI 请求失败（$status）", status >= 500)
        }
    }

    private data class ImageBytes(val mimeType: String, val bytes: ByteArray)

    companion object {
        const val MODEL = "deepseek-flash"
        const val ENDPOINT = "https://api.deepseek.com/chat/completions"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MAX_SINGLE_IMAGE_BYTES = 8 * 1024 * 1024
        private const val MAX_TOTAL_RAW_BYTES = 30 * 1024 * 1024
        private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

        private val RECOGNITION_PROMPT = """
            读取这些电商订单截图并只输出 JSON。多张图可能属于同一订单，也可能包含多笔订单。
            不要猜测截图中看不到的内容；未知字段必须为 null。不要把整单实付分配给多个商品。
            同一商品在多张截图重复出现时合并证据，不要重复计算数量。
            输出格式：
            {"orders":[{"platform":null,"external_order_no":null,"store_name":null,
            "order_date":null,"paid_date":null,"order_paid_yuan":null,
            "order_original_yuan":null,"discount_yuan":null,"shipping_yuan":null,
            "order_status":null,"items":[{"line_key":"同一商品行的稳定位置标识","raw_product_name":"截图可见名称",
            "is_name_truncated":null,"standard_name":null,"brand":null,"category":null,
            "raw_specification":null,"measurement_type":"unknown","content_per_inner":null,
            "content_unit":null,"inner_count_per_sale_unit":null,"inner_unit":null,
            "sale_unit":null,"purchase_quantity":null,"line_original_yuan":null,
            "line_paid_yuan":null,"price_source":"observed或unknown或conflict",
            "evidence":{"image_indexes":[],"note":null}}]}]}
            金额使用人民币元的十进制数字；日期保持截图可确认的 ISO 格式，否则 null。
            measurement_type 仅可为 weight、volume、count、length、area、none、unknown。
            散装或单层规格若没有内包装层，可将 inner_count_per_sale_unit 设为 1，并填写每销售单位的明确含量；没有证据仍填 null。
            不计算包装单价或标准单价，它们由本地程序计算。
        """.trimIndent()
    }
}
