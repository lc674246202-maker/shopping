package com.lc.shoppingrecord

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.lc.shoppingrecord.ai.AiTaskRepository
import com.lc.shoppingrecord.ai.DeepSeekRecognitionClient
import com.lc.shoppingrecord.ai.DeepSeekRecognitionParser
import com.lc.shoppingrecord.ai.RecognitionCoordinator
import com.lc.shoppingrecord.ai.RecognitionException
import com.lc.shoppingrecord.ai.RecognitionOutcome
import com.lc.shoppingrecord.data.OrderRepository
import com.lc.shoppingrecord.data.RecordsRepository
import com.lc.shoppingrecord.data.ShoppingDatabaseHelper
import com.lc.shoppingrecord.model.ConfirmedOrderDraft
import com.lc.shoppingrecord.security.ApiKeyStore
import com.lc.shoppingrecord.transfer.MigrationManager
import com.lc.shoppingrecord.transfer.PreparedImport
import com.lc.shoppingrecord.ui.RecognitionReviewController
import com.lc.shoppingrecord.ui.RecordsController
import com.lc.shoppingrecord.ui.SettingsController
import java.time.LocalDate
import java.util.ArrayDeque
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var database: ShoppingDatabaseHelper
    private lateinit var pages: List<View>
    private lateinit var navigation: List<TextView>
    private lateinit var reviewController: RecognitionReviewController
    private lateinit var recordsController: RecordsController
    private lateinit var settingsController: SettingsController
    private lateinit var taskRepository: AiTaskRepository
    private lateinit var recognitionCoordinator: RecognitionCoordinator
    private lateinit var migrationManager: MigrationManager
    private val selectedImageUris = mutableListOf<Uri>()
    private val pendingRecognitionDrafts = ArrayDeque<ConfirmedOrderDraft>()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var activeTaskId: String? = null
    private var confirmedDraftCount = 0
    private var currentPage = PAGE_RECOGNIZE

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)
    ) { uris ->
        selectedImageUris.clear()
        selectedImageUris.addAll(uris.distinct().take(MAX_IMAGES))
        selectedImageUris.forEach(::tryPersistReadAccess)
        renderSelectedImages()
    }

    private val createCsv = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { exportCsv(it) } }

    private val createZip = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { exportZip(it) } }

    private val openZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { prepareImport(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()

        database = ShoppingDatabaseHelper(applicationContext)
        taskRepository = AiTaskRepository(database)
        taskRepository.recoverInterruptedTasks()
        migrationManager = MigrationManager(database)
        val keyStore = ApiKeyStore(applicationContext)
        val deepSeekClient = DeepSeekRecognitionClient(contentResolver)
        recordsController = RecordsController(this, RecordsRepository(database))
        reviewController = RecognitionReviewController(
            activity = this,
            repository = OrderRepository(database),
            onSaved = ::onConfirmedOrderSaved
        )
        recognitionCoordinator = RecognitionCoordinator(
            taskRepository = taskRepository,
            apiKeyStore = keyStore,
            client = deepSeekClient,
            callback = { outcome -> runOnUiThread { handleRecognitionOutcome(outcome) } }
        )
        settingsController = SettingsController(
            activity = this,
            keyStore = keyStore,
            client = deepSeekClient,
            taskRepository = taskRepository,
            onExportCsv = { createCsv.launch("shopping-record-${LocalDate.now()}.csv") },
            onExportZip = { createZip.launch("shopping-record-backup-${LocalDate.now()}.zip") },
            onImportZip = { openZip.launch(arrayOf("application/zip", "application/octet-stream")) },
            onRestore = ::restoreImport
        )

        pages = listOf(
            findViewById(R.id.pageRecognize),
            findViewById(R.id.pageRecords),
            findViewById(R.id.pageSettings)
        )
        navigation = listOf(
            findViewById(R.id.navRecognize),
            findViewById(R.id.navRecords),
            findViewById(R.id.navSettings)
        )
        navigation.forEachIndexed { index, item -> item.setOnClickListener { showPage(index) } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    currentPage == PAGE_RECORDS && recordsController.handleBack() -> Unit
                    currentPage != PAGE_RECOGNIZE -> showPage(PAGE_RECOGNIZE)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        selectedImageUris.addAll(
            savedInstanceState?.getStringArrayList(STATE_IMAGES).orEmpty().map(Uri::parse)
        )
        activeTaskId = savedInstanceState?.getString(STATE_TASK_ID)
        confirmedDraftCount = savedInstanceState?.getInt(STATE_CONFIRMED_DRAFTS) ?: 0
        findViewById<View>(R.id.selectScreenshotsButton).setOnClickListener {
            pickImages.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        }
        findViewById<View>(R.id.startRecognitionButton).setOnClickListener { requestRecognition() }
        findViewById<View>(R.id.cancelRecognitionButton).setOnClickListener {
            recognitionCoordinator.cancel()
        }
        findViewById<View>(R.id.retryRecognitionButton).setOnClickListener {
            activeTaskId?.let { taskId ->
                showProcessing("正在重新识别并校验订单信息…", retryVisible = false)
                try {
                    recognitionCoordinator.retry(taskId)
                } catch (error: RecognitionException) {
                    showRecognitionFailure(error.message, false)
                }
            }
        }

        showPage(savedInstanceState?.getInt(STATE_PAGE) ?: PAGE_RECOGNIZE)
        renderSelectedImages()
        loadStorageSummary()
        restoreActiveTaskIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE, navigation.indexOfFirst { it.isSelected }.coerceAtLeast(0))
        outState.putStringArrayList(STATE_IMAGES, ArrayList(selectedImageUris.map(Uri::toString)))
        outState.putString(STATE_TASK_ID, activeTaskId)
        outState.putInt(STATE_CONFIRMED_DRAFTS, confirmedDraftCount)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        recognitionCoordinator.close()
        settingsController.close()
        ioExecutor.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun requestRecognition() {
        if (selectedImageUris.isEmpty()) return
        val preferences = getSharedPreferences("local_ui_settings", MODE_PRIVATE)
        if (!preferences.getBoolean("ai_privacy_confirmed", false)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.ai_privacy_title)
                .setMessage(R.string.ai_privacy_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_recognition) { _, _ ->
                    preferences.edit().putBoolean("ai_privacy_confirmed", true).apply()
                    startRecognition()
                }.show()
        } else {
            startRecognition()
        }
    }

    private fun restoreActiveTaskIfNeeded() {
        val taskId = activeTaskId ?: return
        val task = taskRepository.get(taskId) ?: run { activeTaskId = null; return }
        when (task.status) {
            AiTaskRepository.STATUS_SUCCESS -> task.recognizedJson?.let { json ->
                try {
                    val batch = DeepSeekRecognitionParser.parse(json, taskId)
                    pendingRecognitionDrafts.clear()
                    pendingRecognitionDrafts.addAll(batch.orders.drop(confirmedDraftCount))
                    showNextRecognitionResult()
                } catch (error: RecognitionException) {
                    taskRepository.markFailed(taskId, error.message)
                    showPage(PAGE_RECOGNIZE)
                    showRecognitionFailure(error.message, true)
                }
            }
            AiTaskRepository.STATUS_PENDING,
            AiTaskRepository.STATUS_PROCESSING,
            AiTaskRepository.STATUS_FAILED -> {
                showPage(PAGE_RECOGNIZE)
                showRecognitionFailure(task.errorMessage ?: "识别尚未完成，可重新尝试", true)
            }
            else -> activeTaskId = null
        }
    }

    private fun startRecognition() {
        try {
            val taskId = database.createAiTask(selectedImageUris.map(Uri::toString))
            activeTaskId = taskId
            showProcessing(getString(R.string.recognition_processing), retryVisible = false)
            recognitionCoordinator.start(taskId, selectedImageUris.toList())
        } catch (error: RecognitionException) {
            activeTaskId?.let { taskRepository.markFailed(it, error.message, incrementRetry = false) }
            showRecognitionFailure(error.message, false)
            if (error.message.contains("API Key")) showPage(PAGE_SETTINGS)
        }
    }

    private fun handleRecognitionOutcome(outcome: RecognitionOutcome) {
        when (outcome) {
            is RecognitionOutcome.Success -> {
                activeTaskId = outcome.taskId
                confirmedDraftCount = 0
                pendingRecognitionDrafts.clear()
                pendingRecognitionDrafts.addAll(outcome.batch.orders)
                findViewById<View>(R.id.recognitionProcessingContainer).visibility = View.GONE
                showNextRecognitionResult()
            }
            is RecognitionOutcome.Failure -> {
                activeTaskId = outcome.taskId
                showRecognitionFailure(outcome.message, outcome.retryable)
            }
            is RecognitionOutcome.Cancelled -> {
                activeTaskId = null
                findViewById<View>(R.id.recognitionProcessingContainer).visibility = View.GONE
                Toast.makeText(this, R.string.recognition_cancelled, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showNextRecognitionResult() {
        val next = pendingRecognitionDrafts.pollFirst()
        if (next == null) {
            activeTaskId?.let(::clearCompletedTask)
            activeTaskId = null
            confirmedDraftCount = 0
            selectedImageUris.clear()
            renderSelectedImages()
            recordsController.showList()
            showPage(PAGE_RECORDS)
            return
        }
        showRecognitionResult(next)
    }

    private fun showRecognitionResult(draft: ConfirmedOrderDraft) {
        showPage(PAGE_RECOGNIZE)
        reviewController.show(draft)
    }

    private fun onConfirmedOrderSaved() {
        confirmedDraftCount += 1
        loadStorageSummary()
        recordsController.showList()
        if (pendingRecognitionDrafts.isNotEmpty()) {
            showNextRecognitionResult()
        } else {
            activeTaskId?.let(::clearCompletedTask)
            activeTaskId = null
            confirmedDraftCount = 0
            selectedImageUris.clear()
            renderSelectedImages()
            showPage(PAGE_RECORDS)
        }
    }

    private fun clearCompletedTask(taskId: String) {
        taskRepository.get(taskId)?.imageReferences?.forEach { reference ->
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(reference),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some Photo Picker grants are not persistable and need no explicit release.
            }
        }
        taskRepository.deleteTask(taskId)
    }

    private fun showProcessing(message: String, retryVisible: Boolean) {
        findViewById<View>(R.id.recognitionProcessingContainer).visibility = View.VISIBLE
        findViewById<TextView>(R.id.recognitionProcessingStatus).text = message
        findViewById<View>(R.id.cancelRecognitionButton).visibility = if (retryVisible) View.GONE else View.VISIBLE
        findViewById<View>(R.id.retryRecognitionButton).visibility = if (retryVisible) View.VISIBLE else View.GONE
    }

    private fun showRecognitionFailure(message: String, retryable: Boolean) {
        showProcessing(message, retryVisible = retryable)
        findViewById<View>(R.id.cancelRecognitionButton).visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showPage(index: Int) {
        currentPage = index
        pages.forEachIndexed { pageIndex, page -> page.visibility = if (pageIndex == index) View.VISIBLE else View.GONE }
        navigation.forEachIndexed { navIndex, item -> item.isSelected = navIndex == index }
        if (index == PAGE_RECORDS) recordsController.refresh()
        if (index == PAGE_SETTINGS) loadStorageSummary()
    }

    private fun loadStorageSummary() {
        ioExecutor.execute {
            val products = database.count("products")
            val orders = database.count("orders")
            val items = database.count("order_items")
            runOnUiThread {
                findViewById<TextView>(R.id.storageSummary).text =
                    getString(R.string.settings_storage_summary, products, orders, items)
            }
        }
    }

    private fun exportCsv(uri: Uri) = ioExecutor.execute {
        val message = try {
            contentResolver.openOutputStream(uri, "w")?.use(migrationManager::exportCsv)
                ?: error("无法创建导出文件")
            "CSV 已导出"
        } catch (error: Exception) {
            "CSV 导出失败：${error.message ?: "未知错误"}"
        }
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun exportZip(uri: Uri) = ioExecutor.execute {
        val (success, message) = try {
            contentResolver.openOutputStream(uri, "w")?.use(migrationManager::exportZip)
                ?: error("无法创建导出文件")
            true to "完整迁移包已导出"
        } catch (error: Exception) {
            false to "迁移包导出失败：${error.message ?: "未知错误"}"
        }
        runOnUiThread {
            if (success) settingsController.markZipExported()
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun prepareImport(uri: Uri) = ioExecutor.execute {
        val result = try {
            contentResolver.openInputStream(uri)?.use(migrationManager::prepareImport)
                ?: error("无法读取迁移包")
        } catch (error: Exception) {
            runOnUiThread { Toast.makeText(this, "迁移包校验失败：${error.message}", Toast.LENGTH_LONG).show() }
            return@execute
        }
        runOnUiThread { settingsController.showImportPreview(result) }
    }

    private fun restoreImport(prepared: PreparedImport) = ioExecutor.execute {
        val (success, message) = try {
            migrationManager.importPrepared(prepared)
            true to "迁移数据恢复完成"
        } catch (error: Exception) {
            false to "恢复失败，现有数据未被修改：${error.message}"
        }
        runOnUiThread {
            if (success) {
                settingsController.hideImportPreview()
                loadStorageSummary()
                recordsController.showList()
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderSelectedImages() {
        val hasImages = selectedImageUris.isNotEmpty()
        val status = findViewById<TextView>(R.id.selectedImagesStatus)
        val scroll = findViewById<HorizontalScrollView>(R.id.selectedImagesScroll)
        val container = findViewById<LinearLayout>(R.id.selectedImagesContainer)
        val startButton = findViewById<Button>(R.id.startRecognitionButton)
        status.visibility = if (hasImages) View.VISIBLE else View.GONE
        scroll.visibility = if (hasImages) View.VISIBLE else View.GONE
        startButton.visibility = if (hasImages) View.VISIBLE else View.GONE
        startButton.isEnabled = hasImages
        status.text = getString(R.string.selected_images_count, selectedImageUris.size)
        container.removeAllViews()
        selectedImageUris.forEachIndexed { index, uri -> container.addView(createImagePreview(index, uri)) }
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.appRoot)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, windowInsets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safeInsets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(
                    initialLeft + safeInsets.left,
                    initialTop + safeInsets.top,
                    initialRight + safeInsets.right,
                    initialBottom + safeInsets.bottom
                )
            } else {
                val cutout = windowInsets.displayCutout
                view.setPadding(
                    initialLeft + maxOf(windowInsets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                    initialTop + maxOf(windowInsets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                    initialRight + maxOf(windowInsets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                    initialBottom + maxOf(windowInsets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0)
                )
            }
            windowInsets
        }
        root.requestApplyInsets()
    }

    private fun createImagePreview(index: Int, uri: Uri): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
        }
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(112))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.WHITE)
            contentDescription = "第 ${index + 1} 张订单截图"
            setImageURI(uri)
        }
        val remove = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
            text = getString(R.string.remove_image)
            textSize = 12f
            isAllCaps = false
            setOnClickListener { selectedImageUris.remove(uri); renderSelectedImages() }
        }
        wrapper.addView(image)
        wrapper.addView(remove)
        return wrapper
    }

    private fun tryPersistReadAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Photo Picker may grant access without a persistable flag; the active task can still read it.
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_PAGE = "selected_page"
        private const val STATE_IMAGES = "selected_images"
        private const val STATE_TASK_ID = "active_task_id"
        private const val STATE_CONFIRMED_DRAFTS = "confirmed_draft_count"
        private const val PAGE_RECOGNIZE = 0
        private const val PAGE_RECORDS = 1
        private const val PAGE_SETTINGS = 2
        private const val MAX_IMAGES = 5
    }
}
