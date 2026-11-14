package com.lc.shoppingrecord.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.lc.shoppingrecord.R
import com.lc.shoppingrecord.data.RecordsRepository
import com.lc.shoppingrecord.model.OrderDetail
import com.lc.shoppingrecord.model.OrderItemRecord
import com.lc.shoppingrecord.model.OrderSummary
import com.lc.shoppingrecord.model.PriceHistoryEntry
import com.lc.shoppingrecord.model.ProductSummary
import com.lc.shoppingrecord.model.SkuSummary
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

class RecordsController(
    private val activity: Activity,
    private val repository: RecordsRepository
) {
    private val listControls = activity.findViewById<View>(R.id.recordsListControls)
    private val listContainer = activity.findViewById<LinearLayout>(R.id.recordsListContainer)
    private val emptyContainer = activity.findViewById<View>(R.id.recordsEmptyContainer)
    private val emptyTitle = activity.findViewById<TextView>(R.id.recordsEmptyTitle)
    private val detailContainer = activity.findViewById<LinearLayout>(R.id.recordDetailContainer)
    private val backButton = activity.findViewById<Button>(R.id.recordsBackButton)
    private val pageTitle = activity.findViewById<TextView>(R.id.recordsPageTitle)
    private val search = activity.findViewById<EditText>(R.id.recordsSearch)
    private val filterRow = activity.findViewById<View>(R.id.recordsFilterRow)
    private val platformFilter = activity.findViewById<Spinner>(R.id.recordsPlatformFilter)
    private val dateFilter = activity.findViewById<Spinner>(R.id.recordsDateFilter)
    private var mode = Mode.ORDERS

    init {
        activity.findViewById<View>(R.id.showOrdersButton).setOnClickListener {
            mode = Mode.ORDERS
            refresh()
        }
        activity.findViewById<View>(R.id.showProductsButton).setOnClickListener {
            mode = Mode.PRODUCTS
            refresh()
        }
        backButton.setOnClickListener { showList() }
        platformFilter.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("全部平台", "京东", "淘宝", "天猫", "拼多多", "未知")
        )
        dateFilter.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("全部日期", "近30天", "今年")
        )
        val filterListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        platformFilter.onItemSelectedListener = filterListener
        dateFilter.onItemSelectedListener = filterListener
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    fun refresh() {
        if (detailContainer.visibility == View.VISIBLE) return
        val query = search.text.toString()
        when (mode) {
            Mode.ORDERS -> {
                filterRow.visibility = View.VISIBLE
                val platform = platformFilter.selectedItemPosition.takeIf { it > 0 }
                    ?.let { platformFilter.getItemAtPosition(it).toString() }
                val today = LocalDate.now()
                val dateFrom = when (dateFilter.selectedItemPosition) {
                    1 -> today.minusDays(30).toString()
                    2 -> today.withDayOfYear(1).toString()
                    else -> null
                }
                renderOrders(repository.listOrders(query, platform, dateFrom))
            }
            Mode.PRODUCTS -> {
                filterRow.visibility = View.GONE
                renderProducts(repository.listProducts(query))
            }
        }
    }

    fun showList() {
        backButton.visibility = View.GONE
        listControls.visibility = View.VISIBLE
        detailContainer.visibility = View.GONE
        pageTitle.setText(R.string.records_title)
        refresh()
    }

    private fun renderOrders(orders: List<OrderSummary>) {
        listContainer.removeAllViews()
        orders.forEach { order ->
            listContainer.addView(card().apply {
                addView(title("${order.platform} · ${order.displayDate ?: activity.getString(R.string.date_unknown)}"))
                addView(body(order.productSummary.ifBlank { "无商品明细" }))
                addView(body("${order.itemCount} 件商品 · ${money(order.orderPaidCents)} · ${if (order.needsReview) "待完善" else "已确认"}"))
                isClickable = true
                isFocusable = true
                setOnClickListener { openOrderDetail(order.orderId) }
            })
        }
        showEmpty(orders.isEmpty())
    }

    private fun renderProducts(products: List<ProductSummary>) {
        listContainer.removeAllViews()
        products.forEach { product ->
            listContainer.addView(card().apply {
                addView(title(product.displayName))
                addView(body(listOfNotNull(product.brand, product.category).joinToString(" · ").ifBlank { "未分类" }))
                addView(body("${product.skuCount} 个规格 · ${product.purchaseCount} 次购买"))
                isClickable = true
                isFocusable = true
                setOnClickListener { openProductDetail(product) }
            })
        }
        showEmpty(products.isEmpty())
    }

    private fun showEmpty(empty: Boolean) {
        emptyContainer.visibility = if (empty) View.VISIBLE else View.GONE
        emptyTitle.text = if (search.text.isNullOrBlank()) {
            activity.getString(R.string.records_empty)
        } else {
            activity.getString(R.string.records_search_empty)
        }
    }

    private fun openOrderDetail(orderId: String) {
        try {
            val order = repository.getOrder(orderId)
                ?: return showDetailError(IllegalStateException("订单记录不存在"))
            showOrderDetail(order)
        } catch (error: Exception) {
            showDetailError(error)
        }
    }

    private fun showOrderDetail(order: OrderDetail) {
        enterDetail(activity.getString(R.string.order_detail))
        detailContainer.addView(card().apply {
            addView(title("${order.platform} · ${order.paidDate ?: order.orderDate ?: activity.getString(R.string.date_unknown)}"))
            addView(body(order.storeName ?: "店铺待确认"))
            addView(body("订单号：${order.externalOrderNo ?: "待确认"}"))
            addView(title(money(order.orderPaidCents)))
            addView(actionRow(
                activity.getString(R.string.edit) to { editOrder(order) },
                activity.getString(R.string.delete) to { confirmDeleteOrder(order.orderId) }
            ))
        })
        order.items.forEachIndexed { index, item ->
            detailContainer.addView(orderItemCard(index, item))
        }
        scrollToTop()
    }

    private fun orderItemCard(index: Int, item: OrderItemRecord) = card().apply {
        addView(title("${index + 1}. ${item.rawProductName}"))
        addView(body(item.rawSpecification ?: "规格待确认"))
        addView(body("${item.saleQuantity ?: "数量待确认"}${item.saleUnit.orEmpty()} · ${money(item.paidAmountCents)}"))
        val price = item.saleQuantity?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            ?.let { quantity -> item.paidAmountCents?.let { BigDecimal.valueOf(it, 2).divide(quantity, 4, RoundingMode.HALF_UP) } }
        addView(body(price?.let { "${formatPrice(it)}/${item.saleUnit ?: "件"}" } ?: "单价待确认"))
        addView(actionRow(
            activity.getString(R.string.edit) to { editItem(item) },
            activity.getString(R.string.delete) to { confirmDeleteItem(item) }
        ))
    }

    private fun openProductDetail(product: ProductSummary) {
        try {
            val skus = repository.listSkus(product.productId)
            val history = repository.priceHistory(product.productId)
            showProductDetail(product, skus, history)
        } catch (error: Exception) {
            showDetailError(error)
        }
    }

    private fun showProductDetail(
        product: ProductSummary,
        skus: List<SkuSummary>,
        history: List<PriceHistoryEntry>
    ) {
        enterDetail(activity.getString(R.string.product_detail))
        val header = card().apply {
            addView(title(product.displayName))
            addView(body(listOfNotNull(product.brand, product.category).joinToString(" · ").ifBlank { "未分类" }))
            addView(actionRow(activity.getString(R.string.edit) to { editProduct(product) }))
        }
        detailContainer.addView(header)
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = margins()
        }
        val modeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val standardButton = Button(activity).apply {
            text = activity.getString(R.string.standard_price_mode)
            isAllCaps = false
        }
        val packageButton = Button(activity).apply {
            text = activity.getString(R.string.package_price_mode)
            isAllCaps = false
        }
        modeRow.addView(standardButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        modeRow.addView(packageButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        controls.addView(modeRow)
        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                skus.map { it.specRaw ?: it.saleUnit ?: "未命名规格" }
            )
            visibility = View.GONE
        }
        controls.addView(spinner)
        detailContainer.addView(controls)
        val resultArea = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        detailContainer.addView(resultArea)

        var priceMode = product.preferredPriceMode
            ?: if (history.any { it.standardPrice != null }) "standard" else "package"
        fun rerender() {
            try {
                spinner.visibility = if (priceMode == "package" && skus.isNotEmpty()) View.VISIBLE else View.GONE
                val selectedSku = skus.getOrNull(spinner.selectedItemPosition)?.skuId
                renderHistory(resultArea, history, priceMode, selectedSku)
                runCatching { repository.rememberPriceMode(product.productId, priceMode) }
                    .onFailure { Log.w(TAG, "Unable to remember price display mode", it) }
            } catch (error: Exception) {
                showDetailError(error)
            }
        }
        standardButton.setOnClickListener { priceMode = "standard"; rerender() }
        packageButton.setOnClickListener { priceMode = "package"; rerender() }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = rerender()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        rerender()
        scrollToTop()
    }

    private fun renderHistory(
        target: LinearLayout,
        all: List<PriceHistoryEntry>,
        priceMode: String,
        selectedSku: String?
    ) {
        target.removeAllViews()
        val candidates = if (priceMode == "package") {
            all.filter { it.skuId == selectedSku && it.packagePrice != null }
        } else {
            val unit = all.firstNotNullOfOrNull { it.standardUnit }
            all.filter { it.standardPrice != null && it.standardUnit == unit }
        }
        if (candidates.isEmpty()) {
            target.addView(body(activity.getString(R.string.no_valid_price)))
            return
        }
        fun value(entry: PriceHistoryEntry) = if (priceMode == "package") entry.packagePrice!! else entry.standardPrice!!
        fun unit(entry: PriceHistoryEntry) = if (priceMode == "package") entry.packageUnit else entry.standardUnit
        val recent = candidates.first()
        val lowest = candidates.minBy(::value)
        target.addView(card().apply {
            addView(body(activity.getString(R.string.recent_price)))
            addView(title("¥${formatPrice(value(recent))}/${unit(recent)}"))
            addView(body(activity.getString(R.string.lowest_price)))
            addView(title("¥${formatPrice(value(lowest))}/${unit(lowest)} · ${lowest.date ?: "日期待确认"}"))
        })
        candidates.forEach { entry ->
            target.addView(card().apply {
                addView(title("¥${formatPrice(value(entry))}/${unit(entry)}"))
                addView(body("${entry.date ?: "日期待确认"} · ${entry.platform}${entry.storeName?.let { " · $it" }.orEmpty()}"))
                addView(body(entry.specification ?: "规格待确认"))
            })
        }
    }

    private fun editOrder(order: OrderDetail) {
        val form = form(
            order.platform,
            order.storeName,
            order.externalOrderNo,
            order.paidDate,
            centsToYuan(order.orderPaidCents)
        )
        AlertDialog.Builder(activity)
            .setTitle("编辑订单")
            .setView(form.first)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("保存") { _, _ ->
                val fields = form.second
                val amount = parseMoney(fields[4].text.toString())
                if (fields[0].text.isNullOrBlank() || (fields[4].text.isNotBlank() && amount == null)) {
                    Toast.makeText(activity, "请检查平台和金额", Toast.LENGTH_LONG).show()
                } else {
                    repository.updateOrder(
                        order.orderId,
                        fields[0].text.toString().trim(),
                        nullable(fields[1]), nullable(fields[2]), nullable(fields[3]), amount
                    )
                    openOrderDetail(order.orderId)
                }
            }.show()
    }

    private fun editProduct(product: ProductSummary) {
        val name = EditText(activity).apply { hint = "商品显示名称"; setText(product.displayName) }
        val brand = EditText(activity).apply { hint = "品牌（可空）"; setText(product.brand.orEmpty()) }
        val category = EditText(activity).apply { hint = "分类（可空）"; setText(product.category.orEmpty()) }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(name); addView(brand); addView(category)
        }
        AlertDialog.Builder(activity)
            .setTitle("编辑商品档案")
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("保存") { _, _ ->
                val displayName = name.text.toString().trim()
                if (displayName.isEmpty()) {
                    Toast.makeText(activity, "商品名称不能为空", Toast.LENGTH_LONG).show()
                } else {
                    repository.updateProduct(
                        product.productId,
                        displayName,
                        brand.text.toString().trim().takeIf(String::isNotEmpty),
                        category.text.toString().trim().takeIf(String::isNotEmpty)
                    )
                    openProductDetail(product.copy(
                        displayName = displayName,
                        brand = brand.text.toString().trim().takeIf(String::isNotEmpty),
                        category = category.text.toString().trim().takeIf(String::isNotEmpty)
                    ))
                }
            }.show()
    }

    private fun editItem(item: OrderItemRecord) {
        val form = form(
            item.rawProductName,
            item.rawSpecification,
            item.saleQuantity,
            item.saleUnit,
            centsToYuan(item.paidAmountCents)
        )
        AlertDialog.Builder(activity)
            .setTitle("编辑商品明细")
            .setView(form.first)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("保存") { _, _ ->
                val fields = form.second
                val amount = parseMoney(fields[4].text.toString())
                val quantity = nullable(fields[2])
                if (fields[0].text.isNullOrBlank() ||
                    (quantity != null && quantity.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } != true) ||
                    (fields[4].text.isNotBlank() && amount == null)
                ) {
                    Toast.makeText(activity, "请检查名称、数量和金额", Toast.LENGTH_LONG).show()
                } else {
                    repository.updateItem(
                        item.itemId, fields[0].text.toString().trim(), nullable(fields[1]),
                        quantity, nullable(fields[3]), amount
                    )
                    openOrderDetail(item.orderId)
                }
            }.show()
    }

    private fun confirmDeleteOrder(orderId: String) {
        AlertDialog.Builder(activity).setMessage(R.string.delete_order_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> repository.deleteOrder(orderId); showList() }
            .show()
    }

    private fun confirmDeleteItem(item: OrderItemRecord) {
        AlertDialog.Builder(activity).setMessage(R.string.delete_item_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repository.deleteItem(item.itemId)
                if (repository.getOrder(item.orderId) == null) showList() else openOrderDetail(item.orderId)
            }.show()
    }

    private fun enterDetail(title: String) {
        listControls.visibility = View.GONE
        listContainer.removeAllViews()
        emptyContainer.visibility = View.GONE
        backButton.visibility = View.VISIBLE
        pageTitle.text = title
        detailContainer.removeAllViews()
        detailContainer.visibility = View.VISIBLE
    }

    private fun showDetailError(error: Exception) {
        Log.e(TAG, "Unable to display record details", error)
        backButton.visibility = View.GONE
        listControls.visibility = View.VISIBLE
        detailContainer.removeAllViews()
        detailContainer.visibility = View.GONE
        pageTitle.setText(R.string.records_title)
        runCatching { refresh() }
        Toast.makeText(activity, "该记录暂时无法显示，请检查或重新保存记录", Toast.LENGTH_LONG).show()
    }

    private fun card() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.getDrawable(R.drawable.card_background)
        layoutParams = margins()
    }

    private fun margins() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }

    private fun title(value: String) = TextView(activity).apply {
        text = value
        textSize = 17f
        setTextColor(activity.getColor(R.color.ink))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun body(value: String) = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextColor(activity.getColor(R.color.muted))
        setPadding(0, dp(5), 0, 0)
    }

    private fun actionRow(vararg actions: Pair<String, () -> Unit>) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        actions.forEach { action ->
            addView(Button(activity).apply {
                text = action.first
                isAllCaps = false
                setOnClickListener { action.second() }
            })
        }
    }

    private fun form(vararg values: String?): Pair<LinearLayout, List<EditText>> {
        val hints = listOf("名称/平台", "规格/店铺", "数量/订单号", "单位/付款日期", "实付金额（元）")
        val fields = values.mapIndexed { index, value ->
            EditText(activity).apply {
                hint = hints[index]
                setText(value.orEmpty())
                if (index == 4) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            fields.forEach(::addView)
        } to fields
    }

    private fun nullable(field: EditText) = field.text.toString().trim().takeIf(String::isNotEmpty)
    private fun money(cents: Long?) = cents?.let { "¥${centsToYuan(it)}" } ?: activity.getString(R.string.amount_unknown)
    private fun centsToYuan(cents: Long?) = cents?.let { BigDecimal.valueOf(it, 2).setScale(2).toPlainString() }.orEmpty()
    private fun parseMoney(value: String): Long? = value.trim().takeIf { it.matches(Regex("^\\d+(\\.\\d{1,2})?$")) }
        ?.toBigDecimalOrNull()?.movePointRight(2)?.longValueExact()
    private fun formatPrice(value: BigDecimal): String = value.setScale(if (value.abs() < BigDecimal("0.01")) 4 else 2, RoundingMode.HALF_UP).toPlainString()
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun scrollToTop() = activity.findViewById<ScrollView>(R.id.recordsScroll).post { activity.findViewById<ScrollView>(R.id.recordsScroll).fullScroll(View.FOCUS_UP) }

    private enum class Mode { ORDERS, PRODUCTS }

    private companion object {
        const val TAG = "RecordsController"
    }
}
