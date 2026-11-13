from __future__ import annotations

import re
import sqlite3
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SCHEMA_SOURCE = PROJECT_ROOT / "app/src/main/java/com/lc/shoppingrecord/data/ShoppingDatabaseHelper.kt"


def extract_schema_sql() -> list[str]:
    source = SCHEMA_SOURCE.read_text(encoding="utf-8")
    tables = re.findall(r'private const val CREATE_[A-Z_]+ = """(.*?)"""', source, re.S)
    indexes = re.findall(r'"(CREATE INDEX [^"]+)"', source)
    if len(tables) != 5:
        raise AssertionError(f"Expected 5 CREATE TABLE statements, found {len(tables)}")
    return [statement.strip() for statement in tables + indexes]


def create_database() -> sqlite3.Connection:
    connection = sqlite3.connect(":memory:")
    connection.execute("PRAGMA foreign_keys=ON")
    for statement in extract_schema_sql():
        connection.execute(statement)
    return connection


class DatabaseSchemaTests(unittest.TestCase):
    def setUp(self) -> None:
        self.db = create_database()

    def tearDown(self) -> None:
        self.db.close()

    def table_names(self) -> set[str]:
        rows = self.db.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
        return {row[0] for row in rows}

    def columns(self, table: str) -> set[str]:
        return {row[1] for row in self.db.execute(f"PRAGMA table_info({table})")}

    def test_exact_five_core_tables(self) -> None:
        self.assertEqual({"products", "skus", "orders", "order_items", "ai_tasks"}, self.table_names())

    def test_money_columns_are_integer_and_nullable(self) -> None:
        order_info = {row[1]: row for row in self.db.execute("PRAGMA table_info(orders)")}
        item_info = {row[1]: row for row in self.db.execute("PRAGMA table_info(order_items)")}
        for column in ("order_paid_cents", "order_original_cents", "discount_cents", "shipping_cents"):
            self.assertEqual("INTEGER", order_info[column][2])
            self.assertEqual(0, order_info[column][3])
        for column in ("original_amount_cents", "paid_amount_cents"):
            self.assertEqual("INTEGER", item_info[column][2])
            self.assertEqual(0, item_info[column][3])

    def test_decimal_quantities_are_stored_as_text(self) -> None:
        sku_info = {row[1]: row for row in self.db.execute("PRAGMA table_info(skus)")}
        item_info = {row[1]: row for row in self.db.execute("PRAGMA table_info(order_items)")}
        self.assertEqual("TEXT", sku_info["base_quantity_per_sale_unit"][2])
        self.assertEqual("TEXT", sku_info["inner_count_per_sale_unit"][2])
        self.assertEqual("TEXT", item_info["sale_quantity"][2])

    def test_order_item_keeps_original_snapshot_fields(self) -> None:
        columns = self.columns("order_items")
        self.assertTrue({"raw_product_name", "raw_specification", "specification_snapshot_json"} <= columns)

    def test_no_refund_or_return_columns_exist(self) -> None:
        all_columns = set().union(*(self.columns(table) for table in self.table_names()))
        forbidden_fragments = ("refund", "return", "退款", "退货")
        self.assertFalse(any(fragment in column.lower() for column in all_columns for fragment in forbidden_fragments))

    def test_foreign_keys_are_enforced(self) -> None:
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(
                """INSERT INTO order_items(
                    item_id, order_id, raw_product_name, created_at, updated_at
                ) VALUES ('item-1', 'missing-order', '测试商品', '2026-11-13', '2026-11-13')"""
            )

    def test_valid_relationship_and_null_price_can_be_saved(self) -> None:
        self.db.execute(
            "INSERT INTO products VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ("p1", "纯牛奶", "纯牛奶", "蒙牛", "食品饮料", "standard", "2026-11-13", "2026-11-13"),
        )
        self.db.execute(
            """INSERT INTO skus(
                sku_id, product_id, spec_raw, measurement_type, base_unit,
                base_quantity_per_sale_unit, sale_unit, inner_unit,
                inner_count_per_sale_unit, spec_status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            ("s1", "p1", "250mL×24盒/箱", "volume", "L", "6", "箱", "盒", "24", "observed", "2026-11-13", "2026-11-13"),
        )
        self.db.execute(
            """INSERT INTO orders(
                order_id, platform, external_order_no, verification_status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?)""",
            ("o1", "京东", "JD-1", "needs_review", "2026-11-13", "2026-11-13"),
        )
        self.db.execute(
            """INSERT INTO order_items(
                item_id, order_id, product_id, sku_id, raw_product_name,
                raw_specification, specification_snapshot_json, sale_quantity,
                sale_unit, paid_amount_cents, price_source, verification_status,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            ("i1", "o1", "p1", "s1", "蒙牛纯牛奶…", "250mL×24盒/箱", "{}", "2", "箱", None, "unknown", "needs_review", "2026-11-13", "2026-11-13"),
        )
        saved = self.db.execute(
            "SELECT raw_product_name, paid_amount_cents FROM order_items WHERE item_id='i1'"
        ).fetchone()
        self.assertEqual(("蒙牛纯牛奶…", None), saved)

    def test_order_number_is_not_globally_unique(self) -> None:
        values = [
            ("o1", "京东", "SAME-1", "needs_review", "2026-11-13", "2026-11-13"),
            ("o2", "淘宝", "SAME-1", "needs_review", "2026-11-13", "2026-11-13"),
        ]
        self.db.executemany(
            """INSERT INTO orders(
                order_id, platform, external_order_no, verification_status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?)""",
            values,
        )
        self.assertEqual(2, self.db.execute("SELECT COUNT(*) FROM orders").fetchone()[0])

    def test_ai_task_can_keep_five_image_references(self) -> None:
        self.db.execute(
            """INSERT INTO ai_tasks(
                task_id, status, image_paths_json, retry_count, created_at
            ) VALUES (?, ?, ?, ?, ?)""",
            (
                "task-1",
                "pending",
                '["content://1","content://2","content://3","content://4","content://5"]',
                0,
                "2026-11-13",
            ),
        )
        saved = self.db.execute(
            "SELECT status, image_paths_json, retry_count FROM ai_tasks WHERE task_id='task-1'"
        ).fetchone()
        self.assertEqual("pending", saved[0])
        self.assertEqual(5, saved[1].count("content://"))
        self.assertEqual(0, saved[2])

    def test_confirmed_order_transaction_rolls_back_as_a_unit(self) -> None:
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute(
                    """INSERT INTO orders(
                        order_id, platform, verification_status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?)""",
                    ("rollback-order", "京东", "confirmed", "2026-11-13", "2026-11-13"),
                )
                self.db.execute(
                    """INSERT INTO order_items(
                        item_id, order_id, raw_product_name, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?)""",
                    ("bad-item", "missing-order", "测试商品", "2026-11-13", "2026-11-13"),
                )
        count = self.db.execute(
            "SELECT COUNT(*) FROM orders WHERE order_id='rollback-order'"
        ).fetchone()[0]
        self.assertEqual(0, count)

    def test_required_indexes_exist(self) -> None:
        indexes = {
            row[0]
            for row in self.db.execute(
                "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
            )
        }
        self.assertTrue(
            {
                "idx_orders_paid_date",
                "idx_orders_platform_number",
                "idx_order_items_order",
                "idx_order_items_product",
                "idx_skus_product",
                "idx_ai_tasks_status",
            }
            <= indexes
        )


if __name__ == "__main__":
    unittest.main()
