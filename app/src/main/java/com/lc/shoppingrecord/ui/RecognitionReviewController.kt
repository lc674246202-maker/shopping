package com.lc.shoppingrecord.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import com.lc.shoppingrecord.R
import com.lc.shoppingrecord.data.OrderRepository
import com.lc.shoppingrecord.model.ConfirmedOrderDraft
import com.lc.shoppingrecord.model.ConfirmedOrderItemDraft
import com.lc.shoppingrecord.model.PriceIssue
import com.lc.shoppingrecord.model.SaleSpecification
import com.lc.shoppingrecord.model.SaveOrderResult
import com.lc.shoppingrecord.pricing.MeasurementNormalizer
import com.lc.shoppingrecord.pricing.PricingCalculator
import java.math.BigDecimal
import java.math.RoundingMode

class RecognitionReviewController(
    private val activity: Activity,
    private val repository: OrderRepository,
    private val onSaved: () -> Unit
) {
    private val resultContainer = activity.findViewById<LinearLayout>(R.id.recognitionResultContainer)
    private val itemContainer = activity.findViewById<LinearLayout>(R.id.recognizedItemsContainer)
    private val reconcileStatus = activity.findViewById<TextView>(R.id.reconcileStatus)
    private val itemBindings = mutableListOf<ItemBinding>()
    private var currentDraft: ConfirmedOrderDraft? = null

    init {
        activity.findViewById<View>(R.id.saveRecognitionButton).setOnClickListener { save() }
        activity.findViewById<EditText>(R.id.reviewOrderPaid).addTextChangedListener(
            simpleTextWatcher(::updateReconciliation)
        )
    }

    fun show(draft: ConfirmedOrderDraft, manual: Boolean = false) {
        currentDraft = draft
        activity.findViewById<View>(R.id.recognitionHomeContainer).visibility = View.GONE
        activity.findViewById<TextView>(R.id.recognizePageTitle)
            .setText(R.string.confirm_purchase_information)
        activity.findViewById<TextView>(R.id.reviewBanner).text = if (manual) {
            "手动填写购物信息；输入已有商品名称可自动带出最近一次购买信息，所有字段仍可修改。"
        } else {
            activity.getString(R.string.review_banner)
        }
        activity.findViewById<EditText>(R.id.reviewPlatform).setText(draft.platform)
        activity.findViewById<EditText>(R.id.reviewStore).setText(draft.storeName.orEmpty())
        activity.findViewById<EditText>(R.id.reviewOrderNumber).setText(draft.externalOrderNo.orEmpty())
        activity.findViewById<EditText>(R.id.reviewPaidDate).setText(draft.paidDate.orEmpty())
        activity.findViewById<EditText>(R.id.reviewOrderPaid).setText(centsToYuan(draft.orderPaidCents))

        itemBindings.clear()
        itemContainer.removeAllViews()
        draft.items.forEachIndexed { index, item ->
            val card = LayoutInflater.from(activity)
                .inflate(R.layout.item_recognition_review, itemContainer, false)
            val binding = ItemBinding(card, item)
            binding.title.text = activity.getString(R.string.recognized_item_number, index + 1)
            binding.fill(item)
            binding.watchPriceInputs()
            itemBindings += binding
            itemContainer.addView(card)
            binding.updatePrices()
            binding.updateRecognitionStatus()
        }
        resultContainer.visibility = View.VISIBLE
        updateReconciliation()
        activity.findViewById<ScrollView>(R.id.pageRecognize).post {
            activity.findViewById<ScrollView>(R.id.pageRecognize).fullScroll(View.FOCUS_UP)
        }
    }

    fun hide() {
        currentDraft = null
        resultContainer.visibility = View.GONE
        activity.findViewById<View>(R.id.recognitionHomeContainer).visibility = View.VISIBLE
        activity.findViewById<TextView>(R.id.recognizePageTitle).setText(R.string.recognize_title)
        itemBindings.clear()
        itemContainer.removeAllViews()
    }

    private fun save() {
        val draft = readDraft() ?: return
        handleSaveResult(draft, repository.saveConfirmedOrder(draft))
    }

    private fun handleSaveResult(draft: ConfirmedOrderDraft, result: SaveOrderResult) {
        when (result) {
            is SaveOrderResult.Saved -> {
                Toast.makeText(activity, R.string.order_saved, Toast.LENGTH_SHORT).show()
                hide()
                onSaved()
            }
            is SaveOrderResult.Duplicate -> Toast.makeText(
                activity,
                R.string.order_duplicate,
                Toast.LENGTH_LONG
            ).show()
            is SaveOrderResult.PossibleDuplicate -> AlertDialog.Builder(activity)
                .setTitle("可能是重复订单")
                .setMessage("发现同平台、同日期且同金额的已有订单。是否仍然保存这笔记录？")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("仍然保存") { _, _ ->
                    handleSaveResult(draft, repository.saveConfirmedOrder(draft, allowPossibleDuplicate = true))
                }.show()
            is SaveOrderResult.Rejected -> Toast.makeText(
                activity,
                result.reason,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun readDraft(): ConfirmedOrderDraft? {
        val platformField = activity.findViewById<EditText>(R.id.reviewPlatform)
        val platform = platformField.text.toString().trim()
        if (platform.isEmpty()) {
            platformField.error = "平台不能为空；无法识别时可填写“未知”"
            return null
        }
        val orderPaidField = activity.findViewById<EditText>(R.id.reviewOrderPaid)
        val orderPaid = parseMoney(orderPaidField) ?: if (orderPaidField.text.isNullOrBlank()) {
            null
        } else {
            orderPaidField.error = activity.getString(R.string.invalid_money)
            return null
        }

        val items = itemBindings.map { it.read() ?: return null }
        return (currentDraft ?: return null).copy(
            platform = platform,
            externalOrderNo = textOrNull(R.id.reviewOrderNumber),
            storeName = textOrNull(R.id.reviewStore),
            paidDate = textOrNull(R.id.reviewPaidDate),
            orderPaidCents = orderPaid,
            items = items
        )
    }

    private fun updateReconciliation() {
        val orderPaid = parseMoney(activity.findViewById(R.id.reviewOrderPaid))
        val itemAmounts = itemBindings.map { parseMoney(it.paidAmount) }
        reconcileStatus.text = when {
            itemAmounts.any { it == null } -> "金额核对：仍有商品实付未识别"
            orderPaid == null -> "金额核对：整单实付未识别"
            itemAmounts.filterNotNull().sum() == orderPaid -> "金额核对：商品实付合计与整单一致"
            else -> "金额核对：商品实付合计与整单不一致，请核对"
        }
    }

    private fun textOrNull(id: Int): String? = activity.findViewById<EditText>(id)
        .text.toString().trim().takeIf { it.isNotEmpty() }

    private inner class ItemBinding(view: View, original: ConfirmedOrderItemDraft) {
        private var source = original
        private var applyingHistory = false
        private var lastPrefilledName: String? = null
        val title: TextView = view.findViewById(R.id.itemTitle)
        private val status: TextView = view.findViewById(R.id.itemRecognitionStatus)
        private val productName: AutoCompleteTextView = view.findViewById(R.id.itemProductName)
        private val rawSpecification: EditText = view.findViewById(R.id.itemRawSpecification)
        private val contentPerInner: EditText = view.findViewById(R.id.itemContentPerInner)
        private val contentUnit: EditText = view.findViewById(R.id.itemContentUnit)
        private val innerCount: EditText = view.findViewById(R.id.itemInnerCount)
        private val innerUnit: EditText = view.findViewById(R.id.itemInnerUnit)
        private val purchaseQuantity: EditText = view.findViewById(R.id.itemPurchaseQuantity)
        private val saleUnit: EditText = view.findViewById(R.id.itemSaleUnit)
        val paidAmount: EditText = view.findViewById(R.id.itemPaidAmount)
        private val packagePrice: TextView = view.findViewById(R.id.itemPackagePrice)
        private val standardPrice: TextView = view.findViewById(R.id.itemStandardPrice)

        init {
            productName.threshold = 1
            productName.setAdapter(
                ArrayAdapter(
                    activity,
                    android.R.layout.simple_dropdown_item_1line,
                    repository.listHistoricalProductNames()
                )
            )
            productName.setOnItemClickListener { _, _, _, _ -> prefillFromHistory() }
            productName.addTextChangedListener(simpleTextWatcher(::prefillFromHistory))
        }

        fun fill(item: ConfirmedOrderItemDraft) {
            applyingHistory = true
            source = item
            productName.setText(item.rawProductName, false)
            rawSpecification.setText(item.rawSpecification.orEmpty())
            contentPerInner.setText(item.contentPerInner.orEmpty())
            contentUnit.setText(item.contentUnit.orEmpty())
            innerCount.setText(item.innerCountPerSaleUnit.orEmpty())
            innerUnit.setText(item.innerUnit.orEmpty())
            purchaseQuantity.setText(item.saleQuantity.orEmpty())
            saleUnit.setText(item.saleUnit.orEmpty())
            paidAmount.setText(centsToYuan(item.paidAmountCents))
            applyingHistory = false
            listOf(
                rawSpecification,
                contentPerInner,
                contentUnit,
                innerCount,
                innerUnit,
                purchaseQuantity,
                saleUnit,
                paidAmount
            ).forEach { it.hint = activity.getString(R.string.unknown_field_hint) }
        }

        private fun prefillFromHistory() {
            if (applyingHistory) return
            val name = productName.text.toString().trim()
            if (name.isEmpty() || name.equals(lastPrefilledName, ignoreCase = true)) return
            val template = repository.findRecentItemTemplate(name) ?: return
            lastPrefilledName = name
            fill(template.copy(rawProductName = name))
            status.text = "已从历史记录自动填充，可继续修改"
            status.setTextColor(activity.getColor(R.color.green))
            updatePrices()
            updateReconciliation()
        }

        fun watchPriceInputs() {
            val watcher = simpleTextWatcher {
                updatePrices()
                updateRecognitionStatus()
                updateReconciliation()
            }
            listOf(
                productName,
                rawSpecification,
                contentPerInner,
                contentUnit,
                innerCount,
                innerUnit,
                purchaseQuantity,
                saleUnit,
                paidAmount
            ).forEach { it.addTextChangedListener(watcher) }
        }

        fun updateRecognitionStatus() {
            val missing = listOf(
                productName,
                rawSpecification,
                purchaseQuantity,
                saleUnit,
                paidAmount
            ).any { it.text.isNullOrBlank() }
            status.setText(if (missing) R.string.recognized_missing else R.string.recognized_filled)
            status.setTextColor(
                activity.getColor(if (missing) R.color.orange else R.color.green)
            )
        }

        fun updatePrices() {
            val paid = parseMoney(paidAmount)
            val quantity = decimalOrNull(purchaseQuantity)
            val specification = readSpecification()
            val result = PricingCalculator.calculate(paid, quantity, specification)
            packagePrice.text = result.packagePrice?.let {
                "¥${formatPrice(it)}/${result.packageUnit ?: saleUnit.text.toString().ifBlank { "件" }}"
            } ?: activity.getString(R.string.price_waiting)
            standardPrice.text = result.standardPrice?.let {
                "¥${formatPrice(it)}/${result.standardUnit}"
            } ?: activity.getString(
                if (result.issue == PriceIssue.MISSING_PAID_AMOUNT) {
                    R.string.price_waiting
                } else {
                    R.string.price_unavailable
                }
            )
        }

        fun read(): ConfirmedOrderItemDraft? {
            val name = productName.text.toString().trim()
            if (name.isEmpty()) {
                productName.error = "商品名称不能为空"
                return null
            }
            val paid = parseMoney(paidAmount) ?: if (paidAmount.text.isNullOrBlank()) {
                null
            } else {
                paidAmount.error = activity.getString(R.string.invalid_money)
                return null
            }
            val specification = readSpecification()
            val normalized = specification?.let(MeasurementNormalizer::normalize)
            return source.copy(
                rawProductName = name,
                rawSpecification = rawSpecification.text.toString().trim().takeIf(String::isNotEmpty),
                contentPerInner = contentPerInner.text.toString().trim().takeIf(String::isNotEmpty),
                contentUnit = contentUnit.text.toString().trim().takeIf(String::isNotEmpty),
                baseQuantityPerSaleUnit = normalized?.quantityPerSaleUnit?.stripTrailingZeros()?.toPlainString(),
                baseUnit = normalized?.unit,
                saleQuantity = purchaseQuantity.text.toString().trim().takeIf(String::isNotEmpty),
                saleUnit = saleUnit.text.toString().trim().takeIf(String::isNotEmpty),
                innerUnit = innerUnit.text.toString().trim().takeIf(String::isNotEmpty),
                innerCountPerSaleUnit = innerCount.text.toString().trim().takeIf(String::isNotEmpty),
                paidAmountCents = paid,
                priceSource = "user_confirmed"
            )
        }

        private fun readSpecification(): SaleSpecification? {
            val content = decimalOrNull(contentPerInner) ?: return null
            val count = decimalOrNull(innerCount) ?: return null
            val unit = contentUnit.text.toString().trim().takeIf(String::isNotEmpty) ?: return null
            val sale = saleUnit.text.toString().trim().takeIf(String::isNotEmpty) ?: return null
            return SaleSpecification(content, unit, count, sale)
        }
    }

    private fun decimalOrNull(field: EditText): BigDecimal? = field.text.toString()
        .trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()

    private fun parseMoney(field: EditText): Long? {
        val value = field.text.toString().trim()
        if (value.isEmpty() || !MONEY_PATTERN.matches(value)) return null
        return try {
            BigDecimal(value).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun centsToYuan(cents: Long?): String = cents?.let {
        BigDecimal.valueOf(it, 2).setScale(2).toPlainString()
    }.orEmpty()

    private fun formatPrice(value: BigDecimal): String {
        val scale = if (value.abs() < BigDecimal("0.01")) 4 else 2
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }

    private fun simpleTextWatcher(after: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = after()
    }

    companion object {
        private val MONEY_PATTERN = Regex("^(0|[1-9]\\d*)(\\.\\d{1,2})?$")
    }
}
