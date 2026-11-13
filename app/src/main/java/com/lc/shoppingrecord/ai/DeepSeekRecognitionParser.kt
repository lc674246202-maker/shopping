package com.lc.shoppingrecord.ai

import com.lc.shoppingrecord.model.ConfirmedOrderDraft
import com.lc.shoppingrecord.model.ConfirmedOrderItemDraft
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

object DeepSeekRecognitionParser {
    fun parse(rawJson: String, taskId: String): RecognitionBatch {
        if (rawJson.isBlank()) throw RecognitionException("AI 返回了空白结果，请重试", true)
        val root = try {
            JSONObject(rawJson)
        } catch (_: JSONException) {
            throw RecognitionException("AI 返回的数据格式无效，请重试", true)
        }
        val orderArray = root.optJSONArray("orders")
            ?: root.optJSONObject("order")?.let { JSONArray().put(it) }
            ?: throw RecognitionException("AI 结果中没有订单数据", true)
        if (orderArray.length() == 0) throw RecognitionException("截图中未识别到订单", false)

        val orders = mergeOrderObjects(orderArray).mapIndexed { index, order ->
            parseOrder(order, taskId, index)
        }
        return RecognitionBatch(orders, rawJson)
    }

    private fun mergeOrderObjects(array: JSONArray): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val keyed = linkedMapOf<String, JSONObject>()
        for (index in 0 until array.length()) {
            val order = array.optJSONObject(index)
                ?: throw RecognitionException("第${index + 1}笔订单结构无效", true)
            val platform = order.stringOrNull("platform")
            val number = order.stringOrNull("external_order_no")
            if (platform == null || number == null) {
                result += order
                continue
            }
            val key = "${platform.lowercase()}|$number"
            val existing = keyed[key]
            if (existing == null) {
                keyed[key] = order
            } else {
                mergeOrderInto(existing, order)
            }
        }
        return keyed.values + result
    }

    private fun mergeOrderInto(target: JSONObject, incoming: JSONObject) {
        ORDER_MERGE_FIELDS.forEach { field ->
            val current = target.stringOrNull(field)
            val next = incoming.stringOrNull(field)
            when {
                current == null && next != null -> target.put(field, incoming.get(field))
                current != null && next != null && current != next -> target.put(field, JSONObject.NULL)
            }
        }
        val targetItems = target.optJSONArray("items") ?: JSONArray().also { target.put("items", it) }
        val incomingItems = incoming.optJSONArray("items") ?: return
        for (index in 0 until incomingItems.length()) targetItems.put(incomingItems.get(index))
    }

    private fun parseOrder(order: JSONObject?, taskId: String, index: Int): ConfirmedOrderDraft {
        if (order == null) throw RecognitionException("第${index + 1}笔订单结构无效", true)
        val items = order.optJSONArray("items")
            ?: throw RecognitionException("第${index + 1}笔订单没有商品列表", true)
        if (items.length() == 0) throw RecognitionException("第${index + 1}笔订单未识别到商品", false)
        val selectedItems = selectDistinctItemObjects(items)
        return ConfirmedOrderDraft(
            platform = order.stringOrNull("platform") ?: "未知",
            externalOrderNo = order.stringOrNull("external_order_no"),
            storeName = order.stringOrNull("store_name"),
            orderDate = order.stringOrNull("order_date"),
            paidDate = order.stringOrNull("paid_date"),
            orderPaidCents = order.moneyCentsOrNull("order_paid_yuan"),
            orderOriginalCents = order.moneyCentsOrNull("order_original_yuan"),
            discountCents = order.moneyCentsOrNull("discount_yuan"),
            shippingCents = order.moneyCentsOrNull("shipping_yuan"),
            orderStatus = order.stringOrNull("order_status"),
            items = selectedItems.mapIndexed { itemIndex, item ->
                parseItem(item, taskId, index, itemIndex)
            }
        )
    }

    private fun selectDistinctItemObjects(items: JSONArray): List<JSONObject> {
        val withoutKeys = mutableListOf<JSONObject>()
        val byLineKey = linkedMapOf<String, JSONObject>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index)
                ?: throw RecognitionException("商品 ${index + 1} 结构无效", true)
            val lineKey = item.stringOrNull("line_key")
            if (lineKey == null) {
                withoutKeys += item
            } else {
                val current = byLineKey[lineKey]
                if (current == null || completenessScore(item) > completenessScore(current)) {
                    byLineKey[lineKey] = item
                }
            }
        }
        return byLineKey.values + withoutKeys
    }

    private fun completenessScore(item: JSONObject): Int = ITEM_COMPLETENESS_FIELDS.count { key ->
        item.has(key) && !item.isNull(key) && item.optString(key).isNotBlank()
    }

    private fun parseItem(
        item: JSONObject?,
        taskId: String,
        orderIndex: Int,
        itemIndex: Int
    ): ConfirmedOrderItemDraft {
        if (item == null) throw RecognitionException("商品 ${itemIndex + 1} 结构无效", true)
        val rawName = item.stringOrNull("raw_product_name")
            ?: throw RecognitionException("商品 ${itemIndex + 1} 缺少可见名称", false)
        val content = item.decimalStringOrNull("content_per_inner")
        val innerCount = item.decimalStringOrNull("inner_count_per_sale_unit")
        val evidence = item.optJSONObject("evidence")?.toString()
        val lineKey = item.stringOrNull("line_key")
        return ConfirmedOrderItemDraft(
            standardName = item.stringOrNull("standard_name"),
            brand = item.stringOrNull("brand"),
            category = item.stringOrNull("category"),
            rawProductName = rawName,
            isNameTruncated = item.booleanOrNull("is_name_truncated"),
            rawSpecification = item.stringOrNull("raw_specification"),
            measurementType = item.stringOrNull("measurement_type")
                ?.takeIf(ALLOWED_MEASUREMENT_TYPES::contains) ?: "unknown",
            contentPerInner = content,
            contentUnit = item.stringOrNull("content_unit"),
            saleQuantity = item.decimalStringOrNull("purchase_quantity"),
            saleUnit = item.stringOrNull("sale_unit"),
            innerUnit = item.stringOrNull("inner_unit"),
            innerCountPerSaleUnit = innerCount,
            originalAmountCents = item.moneyCentsOrNull("line_original_yuan"),
            paidAmountCents = item.moneyCentsOrNull("line_paid_yuan"),
            priceSource = item.stringOrNull("price_source")?.takeIf(ALLOWED_SOURCES::contains)
                ?: "unknown",
            sourceReferencesJson = JSONObject().apply {
                put("ai_task_id", taskId)
                put("order_index", orderIndex)
                put("item_index", itemIndex)
                put("line_key", lineKey ?: JSONObject.NULL)
                put("evidence", evidence ?: JSONObject.NULL)
            }.toString()
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONObject.decimalStringOrNull(key: String): String? = stringOrNull(key)
        ?.toBigDecimalOrNull()
        ?.takeIf { it > BigDecimal.ZERO }
        ?.stripTrailingZeros()
        ?.toPlainString()

    private fun JSONObject.moneyCentsOrNull(key: String): Long? {
        val value = stringOrNull(key)?.toBigDecimalOrNull() ?: return null
        if (value < BigDecimal.ZERO) return null
        return try {
            value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun JSONObject.booleanOrNull(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return when (val value = get(key)) {
            is Boolean -> value
            is String -> when (value.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
    }

    private val ALLOWED_SOURCES = setOf("observed", "unknown", "conflict", "matched")
    private val ALLOWED_MEASUREMENT_TYPES = setOf(
        "weight", "volume", "count", "length", "area", "none", "unknown"
    )
    private val ITEM_COMPLETENESS_FIELDS = listOf(
        "raw_product_name", "raw_specification", "purchase_quantity", "line_paid_yuan",
        "content_per_inner", "content_unit", "inner_count_per_sale_unit", "sale_unit"
    )
    private val ORDER_MERGE_FIELDS = listOf(
        "store_name", "order_date", "paid_date", "order_paid_yuan", "order_original_yuan",
        "discount_yuan", "shipping_yuan", "order_status"
    )
}
