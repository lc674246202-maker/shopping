package com.lc.shoppingrecord.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lc.shoppingrecord.R
import com.lc.shoppingrecord.ai.AiTaskRepository
import com.lc.shoppingrecord.ai.DeepSeekRecognitionClient
import com.lc.shoppingrecord.security.ApiKeyStore
import com.lc.shoppingrecord.transfer.ImportPreview
import com.lc.shoppingrecord.transfer.PreparedImport
import java.time.Instant
import java.util.concurrent.Executors

class SettingsController(
    private val activity: Activity,
    private val keyStore: ApiKeyStore,
    private val client: DeepSeekRecognitionClient,
    private val taskRepository: AiTaskRepository,
    private val onExportCsv: () -> Unit,
    private val onExportZip: () -> Unit,
    private val onImportZip: () -> Unit,
    private val onRestore: (PreparedImport) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val input = activity.findViewById<EditText>(R.id.apiKeyInput)
    private val status = activity.findViewById<TextView>(R.id.apiKeyStatus)
    private val previewContainer = activity.findViewById<LinearLayout>(R.id.importPreviewContainer)
    private val previewSummary = activity.findViewById<TextView>(R.id.importPreviewSummary)
    private val confirmCheck = activity.findViewById<CheckBox>(R.id.importConfirmCheck)
    private val restoreButton = activity.findViewById<Button>(R.id.restoreImportButton)
    private val preferences = activity.getSharedPreferences("local_ui_settings", Activity.MODE_PRIVATE)
    private var preparedImport: PreparedImport? = null

    init {
        refreshKeyStatus()
        refreshLastExport()
        activity.findViewById<View>(R.id.saveApiKeyButton).setOnClickListener { saveKey() }
        activity.findViewById<View>(R.id.testApiKeyButton).setOnClickListener { testKey() }
        activity.findViewById<View>(R.id.clearApiKeyButton).setOnClickListener {
            keyStore.clear()
            input.text.clear()
            refreshKeyStatus()
            Toast.makeText(activity, "API Key 已从本机清除", Toast.LENGTH_SHORT).show()
        }
        activity.findViewById<View>(R.id.exportCsvButton).setOnClickListener { onExportCsv() }
        activity.findViewById<View>(R.id.exportZipButton).setOnClickListener { onExportZip() }
        activity.findViewById<View>(R.id.importZipButton).setOnClickListener { onImportZip() }
        activity.findViewById<View>(R.id.clearAiTasksButton).setOnClickListener {
            taskRepository.finishedTasks().flatMap { it.imageReferences }.distinct().forEach { reference ->
                try {
                    activity.contentResolver.releasePersistableUriPermission(
                        Uri.parse(reference), Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Non-persistable Photo Picker grants expire without explicit release.
                }
            }
            val count = taskRepository.deleteFinishedTasks()
            Toast.makeText(activity, "已清理 $count 个识别任务", Toast.LENGTH_SHORT).show()
        }
        confirmCheck.setOnCheckedChangeListener { _, checked -> restoreButton.isEnabled = checked }
        restoreButton.setOnClickListener {
            val prepared = preparedImport ?: return@setOnClickListener
            if (confirmCheck.isChecked) onRestore(prepared)
        }
    }

    fun showImportPreview(prepared: PreparedImport) {
        preparedImport = prepared
        confirmCheck.isChecked = false
        restoreButton.isEnabled = false
        previewContainer.visibility = View.VISIBLE
        previewSummary.text = formatPreview(prepared.preview)
    }

    fun hideImportPreview() {
        preparedImport = null
        previewContainer.visibility = View.GONE
    }

    fun markZipExported() {
        val now = Instant.now().toString()
        preferences.edit().putString("last_zip_export", now).apply()
        refreshLastExport()
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun saveKey() {
        val value = input.text.toString().trim()
        if (value.isEmpty()) {
            input.error = "请输入 API Key"
            return
        }
        keyStore.save(value)
        input.text.clear()
        refreshKeyStatus()
        Toast.makeText(activity, "API Key 已加密保存到本机", Toast.LENGTH_SHORT).show()
    }

    private fun testKey() {
        val typed = input.text.toString().trim()
        val key = typed.takeIf(String::isNotEmpty) ?: keyStore.load()
        if (key == null) {
            input.error = "请先输入或保存 API Key"
            return
        }
        status.text = "正在测试连接…"
        executor.execute {
            val message = try {
                client.testConnection(key)
                "连接成功"
            } catch (error: Exception) {
                error.message ?: "连接失败"
            }
            activity.runOnUiThread { status.text = message }
        }
    }

    private fun refreshKeyStatus() {
        status.text = if (keyStore.hasKey()) "已配置并加密保存在本机" else activity.getString(R.string.settings_ai_hint)
    }

    private fun refreshLastExport() {
        val value = preferences.getString("last_zip_export", null)
        activity.findViewById<TextView>(R.id.lastExportStatus).text = "上次完整导出：${value ?: "从未"}"
    }

    private fun formatPreview(preview: ImportPreview) = buildString {
        append("导出时间：${preview.exportedAt}\n")
        append("${preview.productCount} 个商品 · ${preview.skuCount} 个规格\n")
        append("${preview.orderCount} 笔订单 · ${preview.itemCount} 条明细")
        if (preview.imageCount > 0) append(" · ${preview.imageCount} 张图片")
        append("\n关系与文件校验已通过；API Key 不在迁移包中。")
    }
}
