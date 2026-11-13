from __future__ import annotations

import hashlib
import io
import json
import sqlite3
import unittest
import zipfile

from test_schema import create_database


TABLES = ("products", "skus", "orders", "order_items")


def seed(db: sqlite3.Connection) -> None:
    db.execute(
        "INSERT INTO products VALUES (?,?,?,?,?,?,?,?)",
        ("p1", "纯牛奶", "纯牛奶", "蒙牛", "食品饮料", "standard", "t", "t"),
    )
    db.execute(
        """INSERT INTO skus(
            sku_id,product_id,spec_raw,measurement_type,base_unit,
            base_quantity_per_sale_unit,sale_unit,inner_unit,
            inner_count_per_sale_unit,spec_structure_json,spec_status,created_at,updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        ("s1", "p1", "250mL×24盒/箱", "volume", "L", "6", "箱", "盒", "24", "{}", "user_confirmed", "t", "t"),
    )
    db.execute(
        """INSERT INTO orders(
            order_id,platform,external_order_no,paid_date,order_paid_cents,
            verification_status,created_at,updated_at
        ) VALUES (?,?,?,?,?,?,?,?)""",
        ("o1", "京东", "JD-1", "2026-11-12", 9600, "confirmed", "t", "t"),
    )
    db.execute(
        """INSERT INTO order_items(
            item_id,order_id,product_id,sku_id,raw_product_name,raw_specification,
            specification_snapshot_json,sale_quantity,sale_unit,paid_amount_cents,
            price_source,verification_status,created_at,updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        ("i1", "o1", "p1", "s1", "蒙牛纯牛奶", "250mL×24盒/箱", "{}", "2", "箱", 9600, "user_confirmed", "confirmed", "t", "t"),
    )
    db.commit()


def build_package(db: sqlite3.Connection) -> bytes:
    files: dict[str, bytes] = {}
    for table in TABLES:
        db.row_factory = sqlite3.Row
        rows = [dict(row) for row in db.execute(f"SELECT * FROM {table}")]
        files[f"{table}.json"] = json.dumps(rows, ensure_ascii=False).encode()
    manifest = {
        "format_version": 1,
        "exported_at": "2026-11-14T00:00:00Z",
        "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()},
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest))
        for name, data in files.items():
            archive.writestr(name, data)
    return output.getvalue()


class MigrationRoundTripTests(unittest.TestCase):
    def test_complete_package_round_trip_preserves_ids_and_relations(self) -> None:
        source = create_database()
        seed(source)
        package = build_package(source)
        restored = create_database()
        with zipfile.ZipFile(io.BytesIO(package)) as archive, restored:
            for table in TABLES:
                rows = json.loads(archive.read(f"{table}.json"))
                for row in rows:
                    columns = list(row)
                    restored.execute(
                        f"INSERT INTO {table} ({','.join(columns)}) VALUES ({','.join('?' for _ in columns)})",
                        [row[column] for column in columns],
                    )
        linked = restored.execute(
            """SELECT o.order_id,p.product_id,s.sku_id,i.item_id
               FROM order_items i JOIN orders o ON o.order_id=i.order_id
               JOIN products p ON p.product_id=i.product_id
               JOIN skus s ON s.sku_id=i.sku_id"""
        ).fetchone()
        self.assertEqual(("o1", "p1", "s1", "i1"), tuple(linked))

    def test_package_excludes_temporary_tasks_and_secrets(self) -> None:
        db = create_database()
        seed(db)
        package = build_package(db)
        with zipfile.ZipFile(io.BytesIO(package)) as archive:
            names = set(archive.namelist())
        self.assertNotIn("ai_tasks.json", names)
        self.assertFalse(any("key" in name.lower() for name in names))

    def test_failed_restore_rolls_back_all_rows(self) -> None:
        restored = create_database()
        with self.assertRaises(sqlite3.IntegrityError):
            with restored:
                restored.execute(
                    "INSERT INTO products VALUES (?,?,?,?,?,?,?,?)",
                    ("p1", None, "测试", None, None, None, "t", "t"),
                )
                restored.execute(
                    """INSERT INTO order_items(
                        item_id,order_id,raw_product_name,created_at,updated_at
                    ) VALUES (?,?,?,?,?)""",
                    ("i1", "missing", "测试", "t", "t"),
                )
        self.assertEqual(0, restored.execute("SELECT COUNT(*) FROM products").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
