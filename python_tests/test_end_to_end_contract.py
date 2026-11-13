from __future__ import annotations

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = PROJECT_ROOT / "app/src/main/java/com/lc/shoppingrecord"
RES_ROOT = PROJECT_ROOT / "app/src/main/res"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def source(relative: str) -> str:
    return (JAVA_ROOT / relative).read_text(encoding="utf-8")


def ids(layout: str) -> set[str]:
    root = ET.parse(RES_ROOT / f"layout/{layout}").getroot()
    return {
        node.attrib[f"{ANDROID_NS}id"]
        for node in root.iter()
        if f"{ANDROID_NS}id" in node.attrib
    }


class RecognitionReachabilityTests(unittest.TestCase):
    def setUp(self) -> None:
        self.activity = source("MainActivity.kt")
        self.coordinator = source("ai/RecognitionCoordinator.kt")

    def test_start_button_reaches_ai_and_success_reaches_review(self) -> None:
        self.assertIn("recognitionCoordinator.start(taskId", self.activity)
        self.assertIn("handleRecognitionOutcome", self.activity)
        self.assertIn("showRecognitionResult(next)", self.activity)
        self.assertIn("reviewController.show(draft)", self.activity)

    def test_multi_order_results_are_confirmed_one_by_one(self) -> None:
        self.assertIn("pendingRecognitionDrafts.addAll", self.activity)
        self.assertIn("pendingRecognitionDrafts.pollFirst()", self.activity)
        self.assertIn("confirmedDraftCount", self.activity)

    def test_interrupted_task_can_be_restored_or_retried(self) -> None:
        self.assertIn("restoreActiveTaskIfNeeded()", self.activity)
        self.assertIn("STATE_TASK_ID", self.activity)
        self.assertIn("recognitionCoordinator.retry", self.activity)

    def test_task_lifecycle_has_terminal_updates(self) -> None:
        tasks = source("ai/AiTaskRepository.kt")
        for method in (
            "markProcessing",
            "markSuccess",
            "markFailed",
            "markCancelled",
            "resetForRetry",
            "recoverInterruptedTasks",
            "deleteFinishedTasks",
        ):
            self.assertIn(f"fun {method}", tasks)
        self.assertIn("retry_count", tasks)
        self.assertIn("finished_at", tasks)


class DeepSeekAndKeySecurityTests(unittest.TestCase):
    def test_current_vision_model_and_json_mode_are_configured(self) -> None:
        client = source("ai/DeepSeekRecognitionClient.kt")
        self.assertIn('MODEL = "deepseek-flash"', client)
        self.assertIn('ENDPOINT = "https://api.deepseek.com/chat/completions"', client)
        self.assertIn('put("response_format"', client)
        self.assertIn('put("detail", "original")', client)
        self.assertIn("不要把整单实付分配给多个商品", client)

    def test_key_uses_android_keystore_aes_gcm(self) -> None:
        key_store = source("security/ApiKeyStore.kt")
        self.assertIn('KEYSTORE_PROVIDER = "AndroidKeyStore"', key_store)
        self.assertIn('TRANSFORMATION = "AES/GCM/NoPadding"', key_store)
        self.assertNotIn("sk-", key_store)

    def test_images_are_bounded_and_key_is_only_sent_as_header(self) -> None:
        client = source("ai/DeepSeekRecognitionClient.kt")
        self.assertIn("MAX_SINGLE_IMAGE_BYTES", client)
        self.assertIn("MAX_TOTAL_RAW_BYTES", client)
        self.assertIn('setRequestProperty("Authorization", "Bearer $apiKey")', client)
        self.assertNotIn("println(", client)


class RecordsAndSettingsUiTests(unittest.TestCase):
    def test_records_page_has_real_list_search_and_detail_containers(self) -> None:
        record_ids = ids("view_records.xml")
        self.assertTrue(
            {
                "@+id/recordsSearch",
                "@+id/recordsListContainer",
                "@+id/recordsEmptyContainer",
                "@+id/recordDetailContainer",
                "@+id/showOrdersButton",
                "@+id/showProductsButton",
            }
            <= record_ids
        )
        controller = source("ui/RecordsController.kt")
        self.assertIn("repository.listOrders", controller)
        self.assertIn("repository.listProducts", controller)
        self.assertIn("repository.priceHistory", controller)

    def test_records_support_edit_delete_and_both_price_modes(self) -> None:
        controller = source("ui/RecordsController.kt")
        for call in (
            "repository.updateOrder",
            "repository.updateItem",
            "repository.deleteOrder",
            "repository.deleteItem",
            'priceMode = "standard"',
            'priceMode = "package"',
        ):
            self.assertIn(call, controller)

    def test_settings_has_key_transfer_preview_and_cleanup_controls(self) -> None:
        settings_ids = ids("view_settings.xml")
        self.assertTrue(
            {
                "@+id/apiKeyInput",
                "@+id/saveApiKeyButton",
                "@+id/testApiKeyButton",
                "@+id/clearApiKeyButton",
                "@+id/exportCsvButton",
                "@+id/exportZipButton",
                "@+id/importZipButton",
                "@+id/importPreviewContainer",
                "@+id/restoreImportButton",
                "@+id/clearAiTasksButton",
            }
            <= settings_ids
        )


class MigrationSafetyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.migration = source("transfer/MigrationManager.kt")

    def test_zip_excludes_ai_tasks_and_api_key(self) -> None:
        table_map = self.migration.split("TABLE_FILES =", 1)[1]
        self.assertNotIn('"ai_tasks" to', table_map)
        self.assertNotIn("deepseek_key", self.migration)

    def test_import_is_bounded_validated_and_transactional(self) -> None:
        for marker in (
            "MAX_ENTRY_BYTES",
            "MAX_TOTAL_BYTES",
            "MAX_ROWS_PER_TABLE",
            "validateRelationships",
            "database.beginTransaction()",
            "database.setTransactionSuccessful()",
            "database.endTransaction()",
            "sha256",
        ):
            self.assertIn(marker, self.migration)

    def test_restore_requires_empty_database_and_safe_zip_paths(self) -> None:
        self.assertIn("首版只支持向空数据库完整恢复", self.migration)
        self.assertIn('name.matches(Regex("^images/', self.migration)
        self.assertIn("迁移包包含不允许的路径", self.migration)


if __name__ == "__main__":
    unittest.main()
