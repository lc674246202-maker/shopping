package com.lc.shoppingrecord.data

import android.content.ContentValues
import android.database.Cursor
import com.lc.shoppingrecord.model.OrderDetail
import com.lc.shoppingrecord.model.OrderItemRecord
import com.lc.shoppingrecord.model.OrderItemUpdate
import com.lc.shoppingrecord.model.OrderSummary
import com.lc.shoppingrecord.model.DateOnly
import com.lc.shoppingrecord.model.PriceHistoryEntry
import com.lc.shoppingrecord.model.ProductSummary
import com.lc.shoppingrecord.model.SkuSummary
import com.lc.shoppingrecord.pricing.PricingCalculator
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class RecordsRepository(private val databaseHelper: ShoppingDatabaseHelper) {
    fun listOrders(
        search: String = "",
        platform: String? = null,
        dateFrom: String? = null
    ): List<OrderSummary> {
        val normalized = search.trim()
        val args = mutableListOf<String>()
        val conditions = mutableListOf<String>()
        if (normalized.isNotEmpty()) {
            conditions += """(
                o.platform LIKE ? OR o.store_name LIKE ? OR o.external_order_no LIKE ? OR
                EXISTS (SELECT 1 FROM order_items si LEFT JOIN products sp ON sp.product_id=si.product_id
                        WHERE si.order_id=o.order_id AND
                        (si.raw_product_name LIKE ? OR sp.display_name LIKE ? OR sp.brand LIKE ?))
            )""".trimIndent()
            repeat(6) { args += "%$normalized%" }
        }
        platform?.takeIf { it.isNotBlank() }?.let {
            conditions += "o.platform = ?"
            args += it
        }
        dateFrom?.takeIf { it.isNotBlank() }?.let {
            conditions += "COALESCE(o.paid_date,o.order_date) >= ?"
            args += it
        }
        val where = conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND ")?.let { "WHERE $it" }.orEmpty()
        val sql = """
            SELECT o.order_id, o.platform, COALESCE(o.paid_date,o.order_date), o.store_name,
                   o.external_order_no, o.order_paid_cents,
                   COUNT(i.item_id), GROUP_CONCAT(i.raw_product_name, '、'),
                   MAX(CASE WHEN i.verification_status!='confirmed' OR i.paid_amount_cents IS NULL THEN 1 ELSE 0 END)
            FROM orders o LEFT JOIN order_items i ON i.order_id=o.order_id
            $where
            GROUP BY o.order_id
            ORDER BY CASE WHEN COALESCE(o.paid_date,o.order_date) IS NULL THEN 1 ELSE 0 END,
                     COALESCE(o.paid_date,o.order_date) DESC, o.created_at DESC
            LIMIT 200
        """.trimIndent()
        return databaseHelper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toOrderSummary()) }
        }
    }

    fun getOrder(orderId: String): OrderDetail? {
        val orderWithoutItems = databaseHelper.readableDatabase.query(
            "orders",
            arrayOf(
                "order_id", "platform", "external_order_no", "store_name", "order_date",
                "paid_date", "order_paid_cents", "order_status", "note"
            ),
            "order_id=?",
            arrayOf(orderId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            OrderDetail(
                orderId = cursor.stringOrNull(0) ?: orderId,
                platform = cursor.stringOrNull(1)?.takeIf(String::isNotBlank) ?: "未知",
                externalOrderNo = cursor.stringOrNull(2),
                storeName = cursor.stringOrNull(3),
                orderDate = DateOnly.normalize(cursor.stringOrNull(4)),
                paidDate = DateOnly.normalize(cursor.stringOrNull(5)),
                orderPaidCents = cursor.longOrNull(6),
                orderStatus = cursor.stringOrNull(7),
                note = cursor.stringOrNull(8),
                items = emptyList()
            )
        }
        val items = databaseHelper.readableDatabase.query(
            "order_items",
            arrayOf(
                "item_id", "order_id", "product_id", "sku_id", "raw_product_name",
                "raw_specification", "sale_quantity", "sale_unit", "paid_amount_cents",
                "price_source", "specification_snapshot_json"
            ),
            "order_id=?",
            arrayOf(orderId),
            null,
            null,
            "created_at"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toOrderItem()) }
        }
        return orderWithoutItems.copy(items = items)
    }

    fun listProducts(search: String = ""): List<ProductSummary> {
        val normalized = search.trim()
        val where = if (normalized.isEmpty()) "" else {
            """WHERE p.display_name LIKE ? OR p.standard_name LIKE ? OR p.brand LIKE ? OR
                      p.category LIKE ? OR s.spec_raw LIKE ? OR i.raw_specification LIKE ?"""
        }
        val args = if (normalized.isEmpty()) emptyArray() else Array(6) { "%$normalized%" }
        val sql = """
            SELECT p.product_id, COALESCE(p.display_name,p.standard_name,'未命名商品'), p.brand,
                   p.category,
                   COALESCE(NULLIF(TRIM(s.spec_raw),''),NULLIF(TRIM(i.raw_specification),''),''),
                   MAX(i.sale_unit), COUNT(DISTINCT i.item_id), p.preferred_price_mode,
                   MAX(s.comparison_quantity), MAX(s.comparison_unit)
            FROM products p
            JOIN order_items i ON i.product_id=p.product_id
            LEFT JOIN skus s ON s.sku_id=i.sku_id
            $where
            GROUP BY p.product_id,
                     LOWER(COALESCE(NULLIF(TRIM(s.spec_raw),''),NULLIF(TRIM(i.raw_specification),''),''))
            ORDER BY MAX(i.created_at) DESC, p.updated_at DESC
            LIMIT 200
        """.trimIndent()
        return databaseHelper.readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ProductSummary(
                            productId = cursor.getString(0),
                            displayName = cursor.stringOrNull(1)?.takeIf(String::isNotBlank) ?: "未命名商品",
                            brand = cursor.stringOrNull(2),
                            category = cursor.stringOrNull(3),
                            specification = cursor.stringOrNull(4)?.takeIf(String::isNotBlank),
                            saleUnit = cursor.stringOrNull(5),
                            purchaseCount = cursor.getInt(6),
                            preferredPriceMode = cursor.stringOrNull(7),
                            comparisonQuantity = cursor.stringOrNull(8),
                            comparisonUnit = cursor.stringOrNull(9)
                        )
                    )
                }
            }
        }
    }

    fun listSkus(productId: String): List<SkuSummary> = databaseHelper.readableDatabase.query(
        "skus",
        arrayOf("sku_id", "spec_raw", "sale_unit"),
        "product_id=?",
        arrayOf(productId),
        null,
        null,
        "created_at"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                SkuSummary(cursor.getString(0), cursor.stringOrNull(1), cursor.stringOrNull(2))
            )
        }
    }

    fun priceHistory(product: ProductSummary): List<PriceHistoryEntry> {
        val sql = """
            SELECT i.item_id,i.sku_id,COALESCE(o.paid_date,o.order_date),o.platform,o.store_name,
                   i.raw_specification,i.sale_quantity,i.sale_unit,i.paid_amount_cents,
                   i.specification_snapshot_json
            FROM order_items i JOIN orders o ON o.order_id=i.order_id
            LEFT JOIN skus s ON s.sku_id=i.sku_id
            WHERE i.product_id=? AND i.verification_status='confirmed'
              AND i.paid_amount_cents IS NOT NULL AND i.sale_quantity IS NOT NULL
              AND LOWER(COALESCE(NULLIF(TRIM(s.spec_raw),''),NULLIF(TRIM(i.raw_specification),''),''))
                  = LOWER(TRIM(?))
            ORDER BY CASE WHEN COALESCE(o.paid_date,o.order_date) IS NULL THEN 1 ELSE 0 END,
                     COALESCE(o.paid_date,o.order_date) DESC, i.created_at DESC
        """.trimIndent()
        return databaseHelper.readableDatabase.rawQuery(
            sql,
            arrayOf(product.productId, product.specification.orEmpty())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val quantity = cursor.getString(6).toBigDecimalOrNull()
                    val paid = cursor.longOrNull(8)
                    if (quantity == null || quantity <= BigDecimal.ZERO || paid == null) continue
                    val pieceCount = parsePieceCount(cursor.stringOrNull(9))
                    val saleUnit = cursor.stringOrNull(7)
                    val price = PricingCalculator.calculate(paid, quantity, pieceCount, saleUnit)
                    add(
                        PriceHistoryEntry(
                            itemId = cursor.getString(0),
                            skuId = cursor.stringOrNull(1),
                            date = DateOnly.normalize(cursor.stringOrNull(2)),
                            platform = cursor.stringOrNull(3)?.takeIf(String::isNotBlank) ?: "未知",
                            storeName = cursor.stringOrNull(4),
                            specification = cursor.stringOrNull(5),
                            saleQuantity = quantity,
                            saleUnit = cursor.stringOrNull(7),
                            paidAmountCents = paid,
                            packagePrice = price.packagePrice,
                            packageUnit = price.packageUnit,
                            standardPrice = price.standardPrice,
                            standardUnit = price.standardUnit
                        )
                    )
                }
            }
        }
    }

    fun rememberPriceMode(productId: String, mode: String) {
        require(mode == "unit" || mode == "piece")
        databaseHelper.writableDatabase.update(
            "products",
            ContentValues().apply {
                put("preferred_price_mode", if (mode == "unit") "package" else "standard")
                put("updated_at", Instant.now().toString())
            },
            "product_id=?",
            arrayOf(productId)
        )
    }

    fun deleteOrder(orderId: String): Boolean = databaseHelper.writableDatabase.delete(
        "orders", "order_id=?", arrayOf(orderId)
    ) == 1

    fun updateOrder(
        orderId: String,
        platform: String,
        storeName: String?,
        externalOrderNo: String?,
        paidDate: String?,
        orderPaidCents: Long?
    ): Boolean {
        require(platform.isNotBlank())
        require(orderPaidCents == null || orderPaidCents >= 0)
        return databaseHelper.writableDatabase.update(
            "orders",
            ContentValues().apply {
                put("platform", platform)
                put("store_name", storeName)
                put("external_order_no", externalOrderNo)
                put("paid_date", DateOnly.normalize(paidDate))
                put("order_paid_cents", orderPaidCents)
                put("updated_at", Instant.now().toString())
            },
            "order_id=?",
            arrayOf(orderId)
        ) == 1
    }

    fun updateItem(
        itemId: String,
        name: String,
        specification: String?,
        quantity: String?,
        saleUnit: String?,
        paidAmountCents: Long?
    ): Boolean {
        require(name.isNotBlank())
        require(quantity == null || quantity.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } == true)
        require(paidAmountCents == null || paidAmountCents >= 0)
        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        return try {
            val changed = updateItemInternal(
                database, itemId, name, specification, quantity, null, paidAmountCents, saleUnit
            )
            database.setTransactionSuccessful()
            changed
        } finally {
            database.endTransaction()
        }
    }

    fun updateOrderWithItems(
        orderId: String,
        platform: String,
        storeName: String?,
        externalOrderNo: String?,
        paidDate: String?,
        orderPaidCents: Long?,
        items: List<OrderItemUpdate>
    ): Boolean {
        require(platform.isNotBlank())
        require(orderPaidCents == null || orderPaidCents >= 0)
        require(items.isNotEmpty())
        items.forEach {
            require(it.name.isNotBlank())
            require(it.quantity == null || it.quantity.toBigDecimalOrNull()?.let { value -> value > BigDecimal.ZERO } == true)
            require(it.pieceCount == null || it.pieceCount.toBigDecimalOrNull()?.let { value -> value > BigDecimal.ZERO } == true)
            require(it.paidAmountCents == null || it.paidAmountCents >= 0)
        }
        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        return try {
            val now = Instant.now().toString()
            val orderChanged = database.update(
                "orders",
                ContentValues().apply {
                    put("platform", platform)
                    put("store_name", storeName)
                    put("external_order_no", externalOrderNo)
                    put("paid_date", DateOnly.normalize(paidDate))
                    put("order_paid_cents", orderPaidCents)
                    put("updated_at", now)
                },
                "order_id=?",
                arrayOf(orderId)
            ) == 1
            val itemChanged = items.all {
                updateItemInternal(
                    database, it.itemId, it.name, it.model, it.quantity,
                    it.pieceCount, it.paidAmountCents, null
                )
            }
            if (orderChanged && itemChanged) database.setTransactionSuccessful()
            orderChanged && itemChanged
        } finally {
            database.endTransaction()
        }
    }

    private fun updateItemInternal(
        database: android.database.sqlite.SQLiteDatabase,
        itemId: String,
        name: String,
        specification: String?,
        quantity: String?,
        pieceCount: String?,
        paidAmountCents: Long?,
        requestedSaleUnit: String?
    ): Boolean {
        val existing = database.query(
            "order_items",
            arrayOf("product_id", "sku_id", "raw_specification", "sale_unit", "specification_snapshot_json"),
            "item_id=?",
            arrayOf(itemId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            ExistingItem(
                productId = cursor.stringOrNull(0),
                skuId = cursor.stringOrNull(1),
                specification = cursor.stringOrNull(2),
                saleUnit = cursor.stringOrNull(3),
                snapshot = cursor.stringOrNull(4)
            )
        }
        val saleUnit = requestedSaleUnit ?: existing.saleUnit ?: "份"
        val productId = resolveEditedProduct(database, name)
        val snapshot = try {
            JSONObject(existing.snapshot ?: "{}").apply {
                put("raw_specification", specification ?: JSONObject.NULL)
                put("sale_unit", saleUnit)
                put("source", "user_confirmed")
                put("measurement_type", "unknown")
                put("content_per_inner", JSONObject.NULL)
                put("content_unit", JSONObject.NULL)
                put("base_unit", JSONObject.NULL)
                put("base_quantity_per_sale_unit", JSONObject.NULL)
                put("inner_unit", JSONObject.NULL)
                put("inner_count_per_sale_unit", pieceCount ?: JSONObject.NULL)
            }.toString()
        } catch (_: Exception) {
            JSONObject().apply {
                put("raw_specification", specification ?: JSONObject.NULL)
                put("sale_unit", saleUnit)
                put("inner_count_per_sale_unit", pieceCount ?: JSONObject.NULL)
                put("source", "user_confirmed")
            }.toString()
        }
        val skuId = resolveEditedSku(database, productId, specification, saleUnit)
        return database.update(
            "order_items",
            ContentValues().apply {
                put("product_id", productId)
                put("sku_id", skuId)
                put("raw_product_name", name)
                put("raw_specification", specification)
                put("specification_snapshot_json", snapshot)
                put("sale_quantity", quantity)
                put("sale_unit", saleUnit)
                put("paid_amount_cents", paidAmountCents)
                put("price_source", "user_confirmed")
                put("verification_status", "confirmed")
                put("updated_at", Instant.now().toString())
            },
            "item_id=?",
            arrayOf(itemId)
        ) == 1
    }

    private fun resolveEditedProduct(
        database: android.database.sqlite.SQLiteDatabase,
        name: String
    ): String {
        database.rawQuery(
            """SELECT product_id FROM products
               WHERE lower(trim(COALESCE(display_name,'')))=lower(trim(?))
                  OR lower(trim(COALESCE(standard_name,'')))=lower(trim(?))
               ORDER BY created_at LIMIT 1""".trimIndent(),
            arrayOf(name, name)
        ).use { if (it.moveToFirst()) return it.getString(0) }
        val now = Instant.now().toString()
        return UUID.randomUUID().toString().also { productId ->
            database.insertOrThrow("products", null, ContentValues().apply {
                put("product_id", productId)
                put("display_name", name)
                put("created_at", now)
                put("updated_at", now)
            })
        }
    }

    private fun resolveEditedSku(
        database: android.database.sqlite.SQLiteDatabase,
        productId: String?,
        specification: String?,
        saleUnit: String?
    ): String? {
        if (productId == null || (specification.isNullOrBlank() && saleUnit.isNullOrBlank())) return null
        database.query(
            "skus",
            arrayOf("sku_id"),
            "product_id=? AND lower(trim(COALESCE(spec_raw,'')))=lower(trim(?))",
            arrayOf(productId, specification.orEmpty()),
            null,
            null,
            null,
            "1"
        ).use { if (it.moveToFirst()) return it.getString(0) }
        val now = Instant.now().toString()
        val skuId = UUID.randomUUID().toString()
        database.insertOrThrow("skus", null, ContentValues().apply {
            put("sku_id", skuId)
            put("product_id", productId)
            put("spec_raw", specification)
            put("measurement_type", "unknown")
            put("sale_unit", saleUnit)
            put("spec_status", "user_confirmed")
            put("created_at", now)
            put("updated_at", now)
        })
        return skuId
    }

    private data class ExistingItem(
        val productId: String?,
        val skuId: String?,
        val specification: String?,
        val saleUnit: String?,
        val snapshot: String?
    )

    fun deleteItem(itemId: String): Boolean {
        val database = databaseHelper.writableDatabase
        database.beginTransaction()
        return try {
            val orderId = database.query(
                "order_items", arrayOf("order_id"), "item_id=?", arrayOf(itemId), null, null, null, "1"
            ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
            val deleted = database.delete("order_items", "item_id=?", arrayOf(itemId)) == 1
            val remaining = database.rawQuery(
                "SELECT COUNT(*) FROM order_items WHERE order_id=?", arrayOf(orderId)
            ).use { it.moveToFirst(); it.getInt(0) }
            if (remaining == 0) database.delete("orders", "order_id=?", arrayOf(orderId))
            database.setTransactionSuccessful()
            deleted
        } finally {
            database.endTransaction()
        }
    }

    private fun parsePieceCount(json: String?): BigDecimal? {
        if (json.isNullOrBlank()) return null
        return try {
            val value = JSONObject(json)
            value.optString("inner_count_per_sale_unit").toBigDecimalOrNull()
                ?.takeIf { it > BigDecimal.ZERO }
        } catch (_: Exception) {
            null
        }
    }

    private fun Cursor.toOrderSummary() = OrderSummary(
        orderId = getString(0),
        platform = getString(1),
        displayDate = DateOnly.normalize(stringOrNull(2)),
        storeName = stringOrNull(3),
        externalOrderNo = stringOrNull(4),
        orderPaidCents = longOrNull(5),
        itemCount = getInt(6),
        productSummary = stringOrNull(7).orEmpty(),
        needsReview = getInt(8) == 1
    )

    private fun Cursor.toOrderItem() = OrderItemRecord(
        itemId = getString(0),
        orderId = getString(1),
        productId = stringOrNull(2),
        skuId = stringOrNull(3),
        rawProductName = stringOrNull(4)?.takeIf(String::isNotBlank) ?: "未命名商品",
        rawSpecification = stringOrNull(5),
        saleQuantity = stringOrNull(6),
        saleUnit = stringOrNull(7),
        pieceCount = parsePieceCount(stringOrNull(10))?.stripTrailingZeros()?.toPlainString(),
        paidAmountCents = longOrNull(8),
        priceSource = stringOrNull(9),
        specificationSnapshotJson = stringOrNull(10)
    )

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
}
