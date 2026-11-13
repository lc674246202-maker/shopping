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
               WHERE lower(COALESCE(standard_name,''))=lower(?)
                  OR lower(COALESCE(display_name,''))=lower(?)
               LIMIT 2""".trimIndent(),
            arrayOf(name, name)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val first = cursor.getString(0)
            return if (cursor.moveToNext()) null else first
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
            selection = "product_id=? AND lower(COALESCE(spec_raw,''))=lower(?)"
            args = arrayOf(productId, specification)
        } else if (item.baseUnit != null && item.baseQuantityPerSaleUnit != null && item.saleUnit != null) {
            selection = "product_id=? AND base_unit=? AND base_quantity_per_sale_unit=? AND sale_unit=?"
            args = arrayOf(productId, item.baseUnit, item.baseQuantityPerSaleUnit, item.saleUnit)
        } else {
            return null
        }
        database.query(
            "skus", arrayOf("sku_id"), selection, args, null, null, null, "2"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val first = cursor.getString(0)
            return if (cursor.moveToNext()) null else first
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
}
