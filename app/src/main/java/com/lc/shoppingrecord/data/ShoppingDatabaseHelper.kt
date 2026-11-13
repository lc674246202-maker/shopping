package com.lc.shoppingrecord.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.time.Instant
import java.util.UUID

class ShoppingDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_PRODUCTS)
        db.execSQL(CREATE_SKUS)
        db.execSQL(CREATE_ORDERS)
        db.execSQL(CREATE_ORDER_ITEMS)
        db.execSQL(CREATE_AI_TASKS)
        INDEXES.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the PRD V1.0 baseline. Future migrations must preserve history snapshots.
    }

    fun count(table: String): Int {
        require(table in ALLOWED_COUNT_TABLES)
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun createAiTask(imageReferences: List<String>): String {
        require(imageReferences.isNotEmpty())
        require(imageReferences.size <= 5)
        val taskId = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("task_id", taskId)
            put("status", "pending")
            put("image_paths_json", JSONArray(imageReferences).toString())
            put("retry_count", 0)
            put("created_at", Instant.now().toString())
        }
        writableDatabase.insertOrThrow("ai_tasks", null, values)
        return taskId
    }

    companion object {
        private const val DATABASE_NAME = "shopping_record.db"
        private const val DATABASE_VERSION = 1

        private val ALLOWED_COUNT_TABLES = setOf("products", "orders", "order_items")

        private const val CREATE_PRODUCTS = """
            CREATE TABLE products (
                product_id TEXT PRIMARY KEY NOT NULL,
                standard_name TEXT,
                display_name TEXT,
                brand TEXT,
                category TEXT,
                preferred_price_mode TEXT CHECK(preferred_price_mode IN ('standard','package') OR preferred_price_mode IS NULL),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """

        private const val CREATE_SKUS = """
            CREATE TABLE skus (
                sku_id TEXT PRIMARY KEY NOT NULL,
                product_id TEXT NOT NULL,
                spec_raw TEXT,
                measurement_type TEXT,
                base_unit TEXT,
                base_quantity_per_sale_unit TEXT,
                sale_unit TEXT,
                inner_unit TEXT,
                inner_count_per_sale_unit TEXT,
                spec_structure_json TEXT,
                spec_status TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY(product_id) REFERENCES products(product_id) ON DELETE RESTRICT
            )
        """

        private const val CREATE_ORDERS = """
            CREATE TABLE orders (
                order_id TEXT PRIMARY KEY NOT NULL,
                platform TEXT NOT NULL DEFAULT 'unknown',
                external_order_no TEXT,
                store_name TEXT,
                order_date TEXT,
                paid_date TEXT,
                order_paid_cents INTEGER,
                order_original_cents INTEGER,
                discount_cents INTEGER,
                shipping_cents INTEGER,
                order_status TEXT,
                verification_status TEXT NOT NULL DEFAULT 'needs_review',
                note TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """

        private const val CREATE_ORDER_ITEMS = """
            CREATE TABLE order_items (
                item_id TEXT PRIMARY KEY NOT NULL,
                order_id TEXT NOT NULL,
                product_id TEXT,
                sku_id TEXT,
                raw_product_name TEXT NOT NULL,
                is_name_truncated INTEGER,
                raw_specification TEXT,
                specification_snapshot_json TEXT,
                sale_quantity TEXT,
                sale_unit TEXT,
                original_amount_cents INTEGER,
                paid_amount_cents INTEGER,
                price_source TEXT,
                verification_status TEXT NOT NULL DEFAULT 'needs_review',
                source_refs_json TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY(order_id) REFERENCES orders(order_id) ON DELETE CASCADE,
                FOREIGN KEY(product_id) REFERENCES products(product_id) ON DELETE SET NULL,
                FOREIGN KEY(sku_id) REFERENCES skus(sku_id) ON DELETE SET NULL
            )
        """

        private const val CREATE_AI_TASKS = """
            CREATE TABLE ai_tasks (
                task_id TEXT PRIMARY KEY NOT NULL,
                status TEXT NOT NULL,
                image_paths_json TEXT NOT NULL,
                recognized_json TEXT,
                error_message TEXT,
                retry_count INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                finished_at TEXT
            )
        """

        private val INDEXES = listOf(
            "CREATE INDEX idx_orders_paid_date ON orders(paid_date)",
            "CREATE INDEX idx_orders_platform_number ON orders(platform, external_order_no)",
            "CREATE INDEX idx_order_items_order ON order_items(order_id)",
            "CREATE INDEX idx_order_items_product ON order_items(product_id)",
            "CREATE INDEX idx_skus_product ON skus(product_id)",
            "CREATE INDEX idx_ai_tasks_status ON ai_tasks(status)"
        )
    }
}
