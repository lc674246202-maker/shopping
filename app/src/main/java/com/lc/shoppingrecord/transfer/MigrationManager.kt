package com.lc.shoppingrecord.transfer

import android.content.ContentValues
import android.database.Cursor
import com.lc.shoppingrecord.data.ShoppingDatabaseHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ImportPreview(
    val productCount: Int,
    val skuCount: Int,
    val orderCount: Int,
    val itemCount: Int,
    val imageCount: Int,
    val exportedAt: String
)

data class PreparedImport(
    val preview: ImportPreview,
    internal val tables: Map<String, JSONArray>
)

class MigrationManager(private val databaseHelper: ShoppingDatabaseHelper) {
    fun exportCsv(output: OutputStream) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF平台,购买日期,店铺,订单号,商品,规格,数量,销售单位,商品实付（元）\n")
            val sql = """
                SELECT o.platform,COALESCE(o.paid_date,o.order_date),o.store_name,o.external_order_no,
                       i.raw_product_name,i.raw_specification,i.sale_quantity,i.sale_unit,i.paid_amount_cents
                FROM orders o JOIN order_items i ON i.order_id=o.order_id
                ORDER BY COALESCE(o.paid_date,o.order_date) DESC,o.created_at DESC,i.created_at
            """.trimIndent()
            databaseHelper.readableDatabase.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = (0..7).map { cursor.stringOrEmpty(it) } +
                        listOf(if (cursor.isNull(8)) "" else "%.2f".format(java.util.Locale.ROOT, cursor.getLong(8) / 100.0))
                    writer.write(row.joinToString(",", transform = ::csvEscape))
                    writer.newLine()
                }
            }
        }
    }

    fun exportZip(output: OutputStream) {
        val files = linkedMapOf<String, ByteArray>()
        TABLE_FILES.forEach { (table, filename) ->
            files[filename] = tableJson(table).toString().toByteArray(Charsets.UTF_8)
        }
        val manifest = JSONObject().apply {
            put("format_version", FORMAT_VERSION)
            put("exported_at", Instant.now().toString())
            put("app_version", "0.1.0")
            put("counts", JSONObject().apply {
                TABLE_FILES.forEach { (table, filename) ->
                    put(table, JSONArray(String(files.getValue(filename), Charsets.UTF_8)).length())
                }
                put("images", 0)
            })
            put("sha256", JSONObject().apply {
                files.forEach { (name, bytes) -> put(name, sha256(bytes)) }
            })
        }.toString().toByteArray(Charsets.UTF_8)

        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    fun prepareImport(input: InputStream): PreparedImport {
        val files = readZip(input)
        val manifestBytes = files["manifest.json"] ?: error("迁移包缺少 manifest.json")
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        require(manifest.optInt("format_version", -1) == FORMAT_VERSION) { "不支持的迁移包版本" }
        val expectedHashes = manifest.optJSONObject("sha256") ?: error("迁移包缺少校验摘要")
        val tables = linkedMapOf<String, JSONArray>()
        TABLE_FILES.forEach { (table, filename) ->
            val bytes = files[filename] ?: error("迁移包缺少 $filename")
            require(expectedHashes.optString(filename) == sha256(bytes)) { "$filename 校验失败" }
            val array = JSONArray(String(bytes, Charsets.UTF_8))
            require(array.length() <= MAX_ROWS_PER_TABLE) { "$filename 数据量过大" }
            tables[table] = array
        }
        validateRelationships(tables)
        val preview = ImportPreview(
            productCount = tables.getValue("products").length(),
            skuCount = tables.getValue("skus").length(),
            orderCount = tables.getValue("orders").length(),
            itemCount = tables.getValue("order_items").length(),
            imageCount = files.keys.count { it.startsWith("images/") },
            exportedAt = manifest.optString("exported_at", "未知")
        )
        return PreparedImport(preview, tables)
    }

    fun importPrepared(prepared: PreparedImport) {
        val database = databaseHelper.writableDatabase
        val existing = database.rawQuery(
            "SELECT (SELECT COUNT(*) FROM products)+(SELECT COUNT(*) FROM orders)", null
        ).use { it.moveToFirst(); it.getInt(0) }
        require(existing == 0) { "当前已有购物记录，首版只支持向空数据库完整恢复" }
        database.beginTransaction()
        try {
            IMPORT_ORDER.forEach { table ->
                val allowed = TABLE_COLUMNS.getValue(table)
                val rows = prepared.tables.getValue(table)
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    require(row.keys().asSequence().all(allowed::contains)) { "$table 包含未知字段" }
                    val values = ContentValues()
                    val keys = row.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        when (val value = row.get(key)) {
                            JSONObject.NULL -> values.putNull(key)
                            is Int -> values.put(key, value)
                            is Long -> values.put(key, value)
                            is Number -> values.put(key, value.toLong())
                            else -> values.put(key, value.toString())
                        }
                    }
                    database.insertOrThrow(table, null, values)
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun tableJson(table: String): JSONArray {
        require(table in TABLE_COLUMNS)
        return databaseHelper.readableDatabase.query(table, null, null, null, null, null, null).use { cursor ->
            JSONArray().apply {
                while (cursor.moveToNext()) put(cursor.toJson())
            }
        }
    }

    private fun readZip(input: InputStream): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory) { "迁移包包含不允许的目录" }
                require(name in ALLOWED_FILES || name.matches(Regex("^images/[A-Za-z0-9._-]+$"))) {
                    "迁移包包含不允许的路径"
                }
                require(name !in files) { "迁移包包含重复文件" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var entrySize = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    entrySize += count
                    total += count
                    require(entrySize <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) { "迁移包过大" }
                    output.write(buffer, 0, count)
                }
                files[name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        return files
    }

    private fun validateRelationships(tables: Map<String, JSONArray>) {
        fun ids(table: String, key: String): Set<String> {
            val result = mutableSetOf<String>()
            val rows = tables.getValue(table)
            for (index in 0 until rows.length()) {
                val id = rows.getJSONObject(index).optString(key)
                require(id.isNotBlank() && result.add(id)) { "$table 包含空ID或重复ID" }
            }
            return result
        }
        val productIds = ids("products", "product_id")
        val skuIds = ids("skus", "sku_id")
        val orderIds = ids("orders", "order_id")
        ids("order_items", "item_id")
        val skus = tables.getValue("skus")
        for (index in 0 until skus.length()) {
            require(skus.getJSONObject(index).optString("product_id") in productIds) { "SKU商品关联无效" }
        }
        val items = tables.getValue("order_items")
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            require(item.optString("order_id") in orderIds) { "购买明细订单关联无效" }
            item.optNullableString("product_id")?.let { require(it in productIds) { "购买明细商品关联无效" } }
            item.optNullableString("sku_id")?.let { require(it in skuIds) { "购买明细SKU关联无效" } }
        }
    }

    private fun Cursor.toJson() = JSONObject().apply {
        columnNames.forEachIndexed { index, name ->
            when (getType(index)) {
                Cursor.FIELD_TYPE_NULL -> put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> put(name, getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> put(name, getDouble(index))
                Cursor.FIELD_TYPE_STRING -> put(name, getString(index))
                else -> error("数据库包含不支持的字段类型")
            }
        }
    }

    private fun Cursor.stringOrEmpty(index: Int) = if (isNull(index)) "" else getString(index)
    private fun JSONObject.optNullableString(key: String) = if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    private fun csvEscape(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val FORMAT_VERSION = 1
        private const val MAX_ENTRY_BYTES = 12 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 50 * 1024 * 1024
        private const val MAX_ROWS_PER_TABLE = 100_000
        private val TABLE_FILES = linkedMapOf(
            "products" to "products.json",
            "skus" to "skus.json",
            "orders" to "orders.json",
            "order_items" to "order_items.json"
        )
        private val IMPORT_ORDER = listOf("products", "skus", "orders", "order_items")
        private val ALLOWED_FILES = TABLE_FILES.values.toSet() + "manifest.json"
        private val TABLE_COLUMNS = mapOf(
            "products" to setOf("product_id","standard_name","display_name","brand","category","preferred_price_mode","created_at","updated_at"),
            "skus" to setOf("sku_id","product_id","spec_raw","measurement_type","base_unit","base_quantity_per_sale_unit","sale_unit","inner_unit","inner_count_per_sale_unit","spec_structure_json","spec_status","created_at","updated_at"),
            "orders" to setOf("order_id","platform","external_order_no","store_name","order_date","paid_date","order_paid_cents","order_original_cents","discount_cents","shipping_cents","order_status","verification_status","note","created_at","updated_at"),
            "order_items" to setOf("item_id","order_id","product_id","sku_id","raw_product_name","is_name_truncated","raw_specification","specification_snapshot_json","sale_quantity","sale_unit","original_amount_cents","paid_amount_cents","price_source","verification_status","source_refs_json","created_at","updated_at")
        )
    }
}
