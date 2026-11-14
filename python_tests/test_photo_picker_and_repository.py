from __future__ import annotations

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = PROJECT_ROOT / "app/src/main/java/com/lc/shoppingrecord"
RES_ROOT = PROJECT_ROOT / "app/src/main/res"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class PhotoPickerContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.activity = (JAVA_ROOT / "MainActivity.kt").read_text(encoding="utf-8")

    def test_system_photo_picker_is_limited_to_five_images(self) -> None:
        self.assertIn("PickMultipleVisualMedia(MAX_IMAGES)", self.activity)
        self.assertIn("private const val MAX_IMAGES = 5", self.activity)
        self.assertIn("PickVisualMedia.ImageOnly", self.activity)

    def test_selected_images_can_be_previewed_and_removed(self) -> None:
        self.assertIn("createImagePreview(index, uri)", self.activity)
        self.assertIn("selectedImageUris.remove(uri)", self.activity)
        self.assertIn("setImageURI(uri)", self.activity)

    def test_recognition_button_requires_images_and_creates_local_task(self) -> None:
        self.assertIn("if (selectedImageUris.isEmpty())", self.activity)
        self.assertIn("database.createAiTask", self.activity)
        layout = ET.parse(RES_ROOT / "layout/view_recognize.xml").getroot()
        buttons = {
            item.attrib.get(f"{ANDROID_NS}id"): item
            for item in layout.iter("Button")
        }
        button = buttons["@+id/startRecognitionButton"]
        self.assertEqual("false", button.attrib[f"{ANDROID_NS}enabled"])
        self.assertEqual("gone", button.attrib[f"{ANDROID_NS}visibility"])


class ConfirmedOrderRepositoryContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.repository = (JAVA_ROOT / "data/OrderRepository.kt").read_text(encoding="utf-8")
        self.draft = (JAVA_ROOT / "model/ConfirmedOrderDraft.kt").read_text(encoding="utf-8")

    def test_confirmed_order_is_saved_in_one_sqlite_transaction(self) -> None:
        self.assertIn("database.beginTransaction()", self.repository)
        self.assertIn("database.setTransactionSuccessful()", self.repository)
        self.assertIn("database.endTransaction()", self.repository)
        self.assertIn('database.insertOrThrow("orders"', self.repository)
        self.assertIn('"order_items"', self.repository)

    def test_user_confirmed_values_override_ai_result(self) -> None:
        self.assertIn('put("verification_status", "confirmed")', self.repository)
        self.assertIn('put("source", "user_confirmed")', self.repository)
        self.assertIn('val priceSource: String = "user_confirmed"', self.draft)

    def test_duplicate_check_uses_platform_and_order_number(self) -> None:
        self.assertIn('"platform = ? AND external_order_no = ?"', self.repository)
        self.assertIn("SaveOrderResult.Duplicate", self.repository)

    def test_refund_features_are_not_added(self) -> None:
        combined = (self.repository + self.draft).lower()
        self.assertNotIn("refund", combined)
        self.assertNotIn("退款", combined)
        self.assertNotIn("退货", combined)


class InlineRecognitionReviewTests(unittest.TestCase):
    def setUp(self) -> None:
        self.controller = (
            JAVA_ROOT / "ui/RecognitionReviewController.kt"
        ).read_text(encoding="utf-8")

    def test_review_is_inline_on_recognition_page(self) -> None:
        page = ET.parse(RES_ROOT / "layout/view_recognize.xml").getroot()
        ids = {
            item.attrib.get(f"{ANDROID_NS}id")
            for item in page.iter()
            if f"{ANDROID_NS}id" in item.attrib
        }
        self.assertIn("@+id/recognitionResultContainer", ids)
        self.assertIn("@+id/recognizedItemsContainer", ids)
        self.assertIn("@+id/saveRecognitionButton", ids)

    def test_all_required_item_fields_are_directly_editable(self) -> None:
        item = ET.parse(RES_ROOT / "layout/item_recognition_review.xml").getroot()
        edit_ids = {
            node.attrib[f"{ANDROID_NS}id"]
            for node in item.iter()
            if node.tag in {"EditText", "AutoCompleteTextView"}
            and f"{ANDROID_NS}id" in node.attrib
        }
        self.assertEqual(
            {
                "@+id/itemProductName",
                "@+id/itemRawSpecification",
                "@+id/itemContentPerInner",
                "@+id/itemContentUnit",
                "@+id/itemInnerCount",
                "@+id/itemInnerUnit",
                "@+id/itemPurchaseQuantity",
                "@+id/itemSaleUnit",
                "@+id/itemPaidAmount",
            },
            edit_ids,
        )

    def test_standard_and_package_prices_are_outputs_not_inputs(self) -> None:
        item = ET.parse(RES_ROOT / "layout/item_recognition_review.xml").getroot()
        output_ids = {"@+id/itemPackagePrice", "@+id/itemStandardPrice"}
        text_ids = {
            node.attrib.get(f"{ANDROID_NS}id")
            for node in item.iter("TextView")
        }
        edit_ids = {
            node.attrib.get(f"{ANDROID_NS}id")
            for node in item.iter("EditText")
        }
        self.assertTrue(output_ids <= text_ids)
        self.assertTrue(output_ids.isdisjoint(edit_ids))
        self.assertIn("PricingCalculator.calculate", self.controller)

    def test_human_values_are_read_back_before_save(self) -> None:
        self.assertIn("repository.saveConfirmedOrder(draft)", self.controller)
        self.assertIn('priceSource = "user_confirmed"', self.controller)
        self.assertIn("source.copy(", self.controller)

    def test_manual_entry_uses_local_history_autocomplete(self) -> None:
        self.assertIn("listHistoricalProductNames()", self.controller)
        self.assertIn("findRecentItemTemplate(name)", self.controller)
        self.assertIn("已从历史记录自动填充", self.controller)


if __name__ == "__main__":
    unittest.main()
