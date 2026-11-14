package com.lc.shoppingrecord.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.lc.shoppingrecord.model.ConfirmedOrderDraft
import com.lc.shoppingrecord.model.ConfirmedOrderItemDraft
import com.lc.shoppingrecord.model.SaveOrderResult
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class OrderRepository(private val databaseHelper: ShoppingDatabaseHelper) {
    fun listHistoricalProductNames(): List<String> {
        val sql = """
            SELECT COALESCE(NULLIF(TRIM(p.display_name),''),NULLIF(TRIM(i.raw_product_name),'')) AS name
            FROM order_items i LEFT JOIN products p ON p.product_id=i.product_id
            WHERE COALESCE(NULLIF(TRIM(p.display_name),''),NULLIF(TRIM(i.raw_product_name),'')) IS NOT NULL
            GROUP BY LOWER(name)
            ORDER BY MAX(i.created_at) DESC
            LIMIT 100
        """.trimIndent()
        return databaseHelper.readableDatabase.rawQuery(sql, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    fun findRecentItemTemplate(name: String): ConfirmedOrderItemDraft? {
        if (name.isBlank()) return null
        val sql = """
            SELECT i.product_id,i.sku_id,i.raw_product_name,i.raw_specification,
                   i.sale_quantity,i.sale_unit,i.original_amount_cents,i.paid_amount_cents,
                   i.specification_snapshot_json,p.standard_name,p.brand,p.category
            FROM order_items i
            JOIN orders o ON o.order_id=i.order_id
            LEFT JOIN products p ON p.product_id=i.product_id
            WHERE LOWER(TRIM(i.raw_product_name))=LOWER(TRIM(?))
               OR LOWER(TRIM(COALESCE(p.display_name,'')))=LOWER(TRIM(?))
               OR LOWER(TRIM(COALESCE(p.standard_name,'')))=LOWER(TRIM(?))
            ORDER BY CASE WHEN COALESCE(o.paid_date,o.order_date) IS NULL THEN 1 ELSE 0 END,
                     COALESCE(o.paid_date,o.order_date) DESC,i.created_at DESC
            LIMIT 1
        """.trimIndent()
        return databaseHelper.readableDatabase.rawQuery(sql, arrayOf(name, name, name)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val snapshot = cursor.stringOrNull(8)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ConfirmedOrderItemDraft(
                productId = cursor.stringOrNull(0),
                skuId = cursor.stringOrNull(1),
                standardName = cursor.stringOrNull(9),
                brand = cursor.stringOrNull(10),
                category = cursor.stringOrNull(11),
                rawProductName = cursor.stringOrNull(2)?.takeIf(String::isNotBlank) ?: name,
                rawSpecification = cursor.stringOrNull(3),
                measurementType = snapshot.stringOrNull("measurement_type"),
                contentPerInner = snapshot.stringOrNull("content_per_inner"),
                contentUnit = snapshot.stringOrNull("content_unit"),
                baseUnit = snapshot.stringOrNull("base_unit"),
                baseQuantityPerSaleUnit = snapshot.stringOrNull("base_quantity_per_sale_unit"),
                saleQuantity = cursor.stringOrNull(4),
                saleUnit = cursor.stringOrNull(5),
                innerUnit = snapshot.stringOrNull("inner_unit"),
                innerCountPerSaleUnit = snapshot.stringOrNull("inner_count_per_sale_unit"),
                originalAmountCents = cursor.longOrNull(6),
                paidAmountCents = cursor.longOrNull(7),
                priceSource = "history_prefill"
            )
        }
    }

    fun saveConfirmedOrder(
        draft: ConfirmedOrderDraft,
        allowPossibleDuplicate: Boolean = false
    ): SaveOrderResult {
        validate(draft)?.let { return SaveOrderResult.Rejected(it) }
        findStrongDuplicate(draft)?.let { return SaveOrderResult.Duplicate(it) }
        if (!allowPossibleDuplicate) {
            findPossibleDuplicate(draft)?.let { return SaveOrderResult.PossibleDuplicate(it) }
        }

        val database = databaseHelper.writableDatabase
        val now = Instant.now().toString()
        val orderId = UUID.randomUUID().toString()
        val itemIds = mutableListOf<String>()

        database.beginTransaction()
        return try {
            database.insertOrThrow("orders", null, orderValues(orderId, draft, now))
            draft.items.forEach { item ->
                val productId = item.productId ?: findMatchingProduct(database, item)
                    ?: createProduct(database, item, now)
                val skuId = item.skuId ?: findMatchingSku(database, productId, item)
                    ?: createSku(database, productId, item, now)
                val itemId = UUID.randomUUID().toString()
                database.insertOrThrow(
                    "order_items",
                    null,
                    itemValues(itemId, orderId, productId, skuId, item, now)
                )
                itemIds += itemId
            }
            database.setTransactionSuccessful()
            SaveOrderResult.Saved(orderId, itemIds)
        } finally {
            database.endTransaction()
        }
    }

    private fun validate(draft: ConfirmedOrderDraft): String? {
        if (draft.platform.isBlank()) return "平台不能为空"
        if (draft.items.isEmpty()) return "订单至少需要一条商品明细"
        if (listOfNotNull(
                draft.orderPaidCents,
                draft.orderOriginalCents,
                draft.discountCents,
                draft.shippingCents
            ).any { it < 0 }
        ) return "订单金额不能为负数"

        draft.items.forEachIndexed { index, item ->
            if (item.rawProductName.isBlank()) return "第${index + 1}条商品名称不能为空"
            if (listOfNotNull(item.originalAmountCents, item.paidAmountCents).any { it < 0 }) {
                return "第${index + 1}条商品金额不能为负数"
            }
            item.saleQuantity?.let { rawQuantity ->
                val quantity = rawQuantity.toBigDecimalOrNull()
                    ?: return "第${index + 1}条购买数量格式无效"
                if (quantity <= BigDecimal.ZERO) return "第${index + 1}条购买数量必须大于0"
            }
        }
        return null
    }

    private fun findStrongDuplicate(draft: ConfirmedOrderDraft): String? {
        val orderNo = draft.externalOrderNo?.takeIf(String::isNotBlank) ?: return null
        databaseHelper.readableDatabase.query(
            "orders",
            arrayOf("order_id"),
            "platform = ? AND external_order_no = ?",
            arrayOf(draft.platform, orderNo),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun findPossibleDuplicate(draft: ConfirmedOrderDraft): String? {
        if (!draft.externalOrderNo.isNullOrBlank()) return null
        val date = draft.paidDate ?: draft.orderDate ?: return null
        val amount = draft.orderPaidCents ?: return null
        databaseHelper.readableDatabase.rawQuery(
            """SELECT order_id FROM orders
               WHERE platform=? AND COALESCE(paid_date,order_date)=? AND order_paid_cents=?
               LIMIT 1""".trimIndent(),
            arrayOf(draft.platform, date, amount.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun orderValues(
        orderId: String,
        draft: ConfirmedOrderDraft,
        now: String
    ) = ContentValues().apply {
        put("order_id", orderId)
        put("platform", draft.platform)
        put("external_order_no", draft.externalOrderNo)
        put("store_name", draft.storeName)
        put("order_date", draft.orderDate)
        put("paid_date", draft.paidDate)
        put("order_paid_cents", draft.orderPaidCents)
        put("order_original_cents", draft.orderOriginalCents)
        put("discount_cents", draft.discountCents)
        put("shipping_cents", draft.shippingCents)
        put("order_status", draft.orderStatus)
        put("verification_status", "confirmed")
        put("note", draft.note)
        put("created_at", now)
        put("updated_at", now)
    }

    private fun createProduct(
        database: SQLiteDatabase,
        item: ConfirmedOrderItemDraft,
        now: String
    ): String {
        val productId = UUID.randomUUID().toString()
        database.insertOrThrow("products", null, ContentValues().apply {
            put("product_id", productId)
            put("standard_name", item.standardName)
            put("display_name", item.standardName ?: item.rawProductName)
            put("brand", item.brand)
            put("category", item.category)
            put("created_at", now)
            put("updated_at", now)
        })
        return productId
    }

    private fun findMatchingProduct(
        database: SQLiteDatabase,
        item: ConfirmedOrderItemDraft
    ): String? {
        val name = item.standardName?.takeIf(String::isNotBlank) ?: item.rawProductName
        database.rawQuery(
            """SELECT product_id FROM products
               WHERE lower(trim(COALESCE(standard_name,'')))=lower(trim(?))
                  OR lower(trim(COALESCE(display_name,'')))=lower(trim(?))
               ORDER BY created_at
               LIMIT 1""".trimIndent(),
            arrayOf(name, name)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun createSku(
        database: SQLiteDatabase,
        productId: String,
        item: ConfirmedOrderItemDraft,
        now: String
    ): String? {
        if (item.rawSpecification.isNullOrBlank() && item.saleUnit.isNullOrBlank()) return null
        val skuId = UUID.randomUUID().toString()
        database.insertOrThrow("skus", null, ContentValues().apply {
            put("sku_id", skuId)
            put("product_id", productId)
            put("spec_raw", item.rawSpecification)
            put("measurement_type", item.measurementType ?: "unknown")
            put("base_unit", item.baseUnit)
            put("base_quantity_per_sale_unit", item.baseQuantityPerSaleUnit)
            put("sale_unit", item.saleUnit)
            put("inner_unit", item.innerUnit)
            put("inner_count_per_sale_unit", item.innerCountPerSaleUnit)
            put("spec_structure_json", specificationSnapshot(item).toString())
            put("spec_status", "user_confirmed")
            put("created_at", now)
            put("updated_at", now)
        })
        return skuId
    }

    private fun findMatchingSku(
        database: SQLiteDatabase,
        productId: String,
        item: ConfirmedOrderItemDraft
    ): String? {
        val specification = item.rawSpecification?.takeIf(String::isNotBlank)
        val selection: String
        val args: Array<String>
        if (specification != null) {
            selection = "product_id=? AND lower(trim(COALESCE(spec_raw,'')))=lower(trim(?))"
            args = arrayOf(productId, specification)
        } else if (item.baseUnit != null && item.baseQuantityPerSaleUnit != null && item.saleUnit != null) {
            selection = "product_id=? AND base_unit=? AND base_quantity_per_sale_unit=? AND sale_unit=?"
            args = arrayOf(productId, item.baseUnit, item.baseQuantityPerSaleUnit, item.saleUnit)
        } else {
            return null
        }
        database.query(
            "skus", arrayOf("sku_id"), selection, args, null, null, "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun itemValues(
        itemId: String,
        orderId: String,
        productId: String,
        skuId: String?,
        item: ConfirmedOrderItemDraft,
        now: String
    ) = ContentValues().apply {
        put("item_id", itemId)
        put("order_id", orderId)
        put("product_id", productId)
        put("sku_id", skuId)
        put("raw_product_name", item.rawProductName)
        item.isNameTruncated?.let { put("is_name_truncated", if (it) 1 else 0) }
            ?: putNull("is_name_truncated")
        put("raw_specification", item.rawSpecification)
        put("specification_snapshot_json", specificationSnapshot(item).toString())
        put("sale_quantity", item.saleQuantity)
        put("sale_unit", item.saleUnit)
        put("original_amount_cents", item.originalAmountCents)
        put("paid_amount_cents", item.paidAmountCents)
        put("price_source", item.priceSource)
        put("verification_status", "confirmed")
        put("source_refs_json", item.sourceReferencesJson)
        put("created_at", now)
        put("updated_at", now)
    }

    private fun specificationSnapshot(item: ConfirmedOrderItemDraft) = JSONObject().apply {
        put("raw_specification", item.rawSpecification ?: JSONObject.NULL)
        put("measurement_type", item.measurementType ?: JSONObject.NULL)
        put("content_per_inner", item.contentPerInner ?: JSONObject.NULL)
        put("content_unit", item.contentUnit ?: JSONObject.NULL)
        put("base_unit", item.baseUnit ?: JSONObject.NULL)
        put("base_quantity_per_sale_unit", item.baseQuantityPerSaleUnit ?: JSONObject.NULL)
        put("sale_unit", item.saleUnit ?: JSONObject.NULL)
        put("inner_unit", item.innerUnit ?: JSONObject.NULL)
        put("inner_count_per_sale_unit", item.innerCountPerSaleUnit ?: JSONObject.NULL)
        put("source", "user_confirmed")
    }

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun JSONObject?.stringOrNull(key: String): String? {
        if (this == null || !has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
}
