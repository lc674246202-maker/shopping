from __future__ import annotations

import re
import unittest
from pathlib import Path


WORKSPACE_ROOT = Path(__file__).resolve().parents[2]
HTML = WORKSPACE_ROOT / "browser-prototype/dist/index.html"
SCRIPT = WORKSPACE_ROOT / "browser-prototype/dist/app.js"


class BrowserPrototypeContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.html = HTML.read_text(encoding="utf-8")
        cls.script = SCRIPT.read_text(encoding="utf-8")

    def test_recognition_home_has_no_unfinished_task(self) -> None:
        self.assertNotIn("未完成任务", self.html)
        self.assertNotIn("recent-draft", self.html)

    def test_result_fields_are_inline_and_editable(self) -> None:
        fields = re.findall(r'data-inline-item="(milk|tissue)" data-inline-field="([^"]+)"', self.html)
        self.assertEqual(18, len(fields))
        expected = {
            "name", "spec", "content", "contentUnit", "innerCount",
            "innerUnit", "quantity", "unit", "paid",
        }
        self.assertEqual(expected, {field for _, field in fields})
        self.assertNotIn('data-action="edit-item"', self.html)

    def test_prices_are_auto_calculated_and_human_result_is_saved(self) -> None:
        self.assertIn("function updateInlineResult", self.script)
        self.assertIn("function saveRecognitionResult", self.script)
        self.assertIn("已按本页人工确认结果保存", self.script)

    def test_product_history_cards_have_distinct_routes(self) -> None:
        routes = re.findall(r'data-action="open-product" data-product="([^"]+)"', self.html)
        self.assertEqual(["milk", "tissue", "brush"], routes)


if __name__ == "__main__":
    unittest.main()
