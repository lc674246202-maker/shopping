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
    private val recordsScroll = activity.findViewById<ScrollView>(R.id.pageRecords)
    private val ordersButton = activity.findViewById<Button>(R.id.showOrdersButton)
    private val productsButton = activity.findViewById<Button>(R.id.showProductsButton)
    private var mode = Mode.PRODUCTS

    init {
        ordersButton.setOnClickListener {
            mode = Mode.ORDERS
            refresh()
        }
        productsButton.setOnClickListener {
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
        productsButton.isSelected = mode == Mode.PRODUCTS
        ordersButton.isSelected = mode == Mode.ORDERS
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
        listContainer.visibility = View.VISIBLE
        detailContainer.visibility = View.GONE
        pageTitle.setText(R.string.records_title)
        refresh()
    }

    private fun renderOrders(orders: List<OrderSummary>) {
        listContainer.removeAllViews()
        orders.forEach { order ->
            listContainer.addView(card().apply {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(title("${order.platform} · ${order.displayDate ?: activity.getString(R.string.date_unknown)}"))
                        addView(body(order.productSummary.ifBlank { "无商品明细" }))
                        addView(body("${order.itemCount} 件商品 · ${if (order.needsReview) "待完善" else "已确认"}"))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(title("${money(order.orderPaidCents)}  ›"))
                })
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
            val history = runCatching { repository.priceHistory(product) }.getOrDefault(emptyList())
            val standardUnit = history.firstNotNullOfOrNull { it.standardUnit }
            val comparable = if (standardUnit != null) {
                history.filter { it.standardPrice != null && it.standardUnit == standardUnit }
            } else {
                history.filter { it.packagePrice != null }
            }
            val latestText = comparable.firstOrNull()?.let { entry ->
                if (standardUnit != null) "¥${formatPrice(entry.standardPrice!!)}/$standardUnit"
                else "¥${formatPrice(entry.packagePrice!!)}/${entry.packageUnit ?: product.saleUnit ?: "件"}"
            } ?: activity.getString(R.string.amount_unknown)
            val lowestText = comparable.minByOrNull { entry ->
                entry.standardPrice ?: entry.packagePrice ?: BigDecimal.valueOf(Long.MAX_VALUE)
            }?.let { entry ->
                val value = entry.standardPrice ?: entry.packagePrice
                value?.let { "最低 ¥${formatPrice(it)}" }
            }
            listContainer.addView(card().apply {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(title(product.displayName))
                        addView(body(product.specification ?: "规格待确认"))
                        addView(body("共 ${product.purchaseCount} 次购买${lowestText?.let { " · $it" }.orEmpty()}"))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(LinearLayout(activity).apply {
                        gravity = Gravity.END
                        orientation = LinearLayout.VERTICAL
                        addView(title(latestText).apply {
                            textSize = 15f
                            gravity = Gravity.END
                        })
                        addView(body("查看历史价格  ›").apply {
                            gravity = Gravity.END
                            setTextColor(activity.getColor(R.color.green))
                        })
                    })
                })
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
            addView(detailRow("商家", order.storeName ?: order.platform))
            addView(detailRow("购买日期", order.paidDate ?: order.orderDate ?: activity.getString(R.string.date_unknown)))
            addView(detailRow("订单号", order.externalOrderNo ?: "待确认"))
            addView(detailRow("实付款", money(order.orderPaidCents), emphasize = true))
        })
        detailContainer.addView(sectionTitle("商品明细"))
        order.items.forEachIndexed { index, item ->
            detailContainer.addView(orderItemCard(index, item))
        }
        detailContainer.addView(actionRow(
            "编辑订单" to { editOrder(order) },
            activity.getString(R.string.delete) to { confirmDeleteOrder(order.orderId) }
        ))
        scrollToTop()
    }

    private fun orderItemCard(index: Int, item: OrderItemRecord) = card().apply {
        addView(body("商品 ${index + 1}"))
        addView(title(item.rawProductName))
        addView(body(item.rawSpecification ?: "规格待确认"))
        addView(detailRow("购买数量", "${item.saleQuantity ?: "待确认"}${item.saleUnit.orEmpty()}"))
        addView(detailRow("实付价格", money(item.paidAmountCents), emphasize = true))
        val price = item.saleQuantity?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            ?.let { quantity -> item.paidAmountCents?.let { BigDecimal.valueOf(it, 2).divide(quantity, 4, RoundingMode.HALF_UP) } }
        addView(detailRow("按件单价", price?.let { "¥${formatPrice(it)}/${item.saleUnit ?: "件"}" } ?: "待确认"))
        addView(actionRow(
            activity.getString(R.string.edit) to { editItem(item) },
            activity.getString(R.string.delete) to { confirmDeleteItem(item) }
        ))
    }

    private fun openProductDetail(product: ProductSummary) {
        try {
            val history = repository.priceHistory(product)
            showProductDetail(product, history)
        } catch (error: Exception) {
            showDetailError(error)
        }
    }

    private fun showProductDetail(
        product: ProductSummary,
        history: List<PriceHistoryEntry>
    ) {
        enterDetail(product.displayName)
        val header = card().apply {
            addView(TextView(activity).apply {
                text = product.specification ?: "规格待确认"
                textSize = 13f
                setTextColor(activity.getColor(R.color.green_dark))
                background = activity.getDrawable(R.drawable.pill_background)
            })
            addView(body(listOfNotNull(product.brand, product.category).joinToString(" · ").ifBlank { "未分类" }))
            addView(actionRow("修改标准单位" to { editProduct(product) }))
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
            background = activity.getDrawable(R.drawable.segmented_button_background)
            setTextColor(activity.getColorStateList(R.color.segmented_text))
        }
        val packageButton = Button(activity).apply {
            text = activity.getString(R.string.package_price_mode)
            isAllCaps = false
            background = activity.getDrawable(R.drawable.segmented_button_background)
            setTextColor(activity.getColorStateList(R.color.segmented_text))
        }
        modeRow.addView(standardButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        modeRow.addView(packageButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        controls.addView(modeRow)
        detailContainer.addView(controls)
        val resultArea = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        detailContainer.addView(resultArea)

        var priceMode = product.preferredPriceMode
            ?: if (history.any { it.standardPrice != null }) "standard" else "package"
        fun rerender() {
            try {
                standardButton.isSelected = priceMode == "standard"
                packageButton.isSelected = priceMode == "package"
                renderHistory(resultArea, history, priceMode)
                runCatching { repository.rememberPriceMode(product.productId, priceMode) }
                    .onFailure { Log.w(TAG, "Unable to remember price display mode", it) }
            } catch (error: Exception) {
                showDetailError(error)
            }
        }
        standardButton.setOnClickListener { priceMode = "standard"; rerender() }
        packageButton.setOnClickListener { priceMode = "package"; rerender() }
        rerender()
        scrollToTop()
    }

    private fun renderHistory(
        target: LinearLayout,
        all: List<PriceHistoryEntry>,
        priceMode: String
    ) {
        target.removeAllViews()
        val candidates = if (priceMode == "package") {
            all.filter { it.packagePrice != null }
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
            addView(body(if (priceMode == "standard") "当前标准单位" else "当前计价方式"))
            addView(title(if (priceMode == "standard") {
                unit(recent).orEmpty()
            } else {
                "按销售包装"
            }))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(metric(activity.getString(R.string.recent_price), "¥${formatPrice(value(recent))}/${unit(recent)}"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(metric(activity.getString(R.string.lowest_price), "¥${formatPrice(value(lowest))}/${unit(lowest)}"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            })
        })
        target.addView(card().apply {
            addView(PriceHistoryChartView(activity).apply {
                setValues(candidates.asReversed().map { it.date to value(it) })
            })
        })
        target.addView(sectionTitle("购买历史"))
        candidates.forEach { entry ->
            target.addView(card().apply {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(title(entry.date ?: "日期待确认").apply { textSize = 15f })
                        addView(body("${entry.platform}${entry.storeName?.let { " · $it" }.orEmpty()}"))
                        addView(body(entry.specification ?: "规格待确认"))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(title("¥${formatPrice(value(entry))}/${unit(entry)}").apply {
                        textSize = 15f
                        gravity = Gravity.END
                    })
                })
            })
        }
    }

    private fun editOrder(order: OrderDetail) {
        val form = form(
            labels = listOf("平台", "店铺", "订单号", "付款日期", "整单实付金额（元）"),
            values = listOf(order.platform, order.storeName, order.externalOrderNo, order.paidDate, centsToYuan(order.orderPaidCents)),
            decimalIndexes = setOf(4)
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
        val form = form(
            labels = listOf("商品名称", "品牌", "分类", "基础计价数量", "基础计价单位"),
            values = listOf(
                product.displayName,
                product.brand,
                product.category,
                product.comparisonQuantity,
                product.comparisonUnit
            ),
            decimalIndexes = setOf(3)
        )
        AlertDialog.Builder(activity)
            .setTitle("编辑商品与计价单位")
            .setView(form.first)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("保存") { _, _ ->
                val fields = form.second
                val displayName = fields[0].text.toString().trim()
                val comparisonQuantity = nullable(fields[3])
                val comparisonUnit = nullable(fields[4])
                val comparisonValid = (comparisonQuantity == null && comparisonUnit == null) ||
                    (comparisonQuantity?.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } == true && comparisonUnit != null)
                if (displayName.isEmpty() || !comparisonValid) {
                    Toast.makeText(activity, "请填写商品名称；基础计价数量和单位需同时填写", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        repository.updateProduct(
                            product.productId,
                            product.specification,
                            displayName,
                            nullable(fields[1]),
                            nullable(fields[2]),
                            comparisonQuantity,
                            comparisonUnit
                        )
                    } catch (_: IllegalArgumentException) {
                        Toast.makeText(activity, "基础计价单位不受支持，请使用 g、kg、mL、L、抽、张、个等单位", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    openProductDetail(product.copy(
                        displayName = displayName,
                        brand = nullable(fields[1]),
                        category = nullable(fields[2]),
                        comparisonQuantity = comparisonQuantity,
                        comparisonUnit = comparisonUnit
                    ))
                }
            }.show()
    }

    private fun editItem(item: OrderItemRecord) {
        val form = form(
            labels = listOf("商品名称", "规格型号", "购买数量", "购买单位", "商品实付金额（元）"),
            values = listOf(item.rawProductName, item.rawSpecification, item.saleQuantity, item.saleUnit, centsToYuan(item.paidAmountCents)),
            decimalIndexes = setOf(2, 4)
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
        listContainer.visibility = View.GONE
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
        listContainer.visibility = View.VISIBLE
        detailContainer.removeAllViews()
        detailContainer.visibility = View.GONE
        pageTitle.setText(R.string.records_title)
        runCatching { refresh() }
        Toast.makeText(activity, "该记录暂时无法显示，请检查或重新保存记录", Toast.LENGTH_LONG).show()
    }

    private fun card() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.getDrawable(R.drawable.card_background)
        elevation = dp(1).toFloat()
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

    private fun sectionTitle(value: String) = title(value).apply {
        textSize = 18f
        setPadding(0, dp(8), 0, dp(10))
    }

    private fun detailRow(label: String, value: String, emphasize: Boolean = false) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(body(label).apply { setPadding(0, 0, 0, 0) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(activity).apply {
                text = value
                textSize = if (emphasize) 16f else 14f
                setTextColor(activity.getColor(if (emphasize) R.color.ink else R.color.muted))
                if (emphasize) setTypeface(typeface, Typeface.BOLD)
            })
        }

    private fun metric(label: String, value: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, 0)
        addView(body(label).apply { setPadding(0, 0, 0, 0) })
        addView(title(value).apply {
            textSize = 16f
            setPadding(0, dp(3), 0, 0)
        })
    }

    private fun actionRow(vararg actions: Pair<String, () -> Unit>) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(10), 0, 0)
        actions.forEach { action ->
            addView(Button(activity).apply {
                text = action.first
                isAllCaps = false
                background = activity.getDrawable(R.drawable.secondary_button)
                setTextColor(activity.getColor(R.color.green))
                minWidth = 0
                setOnClickListener { action.second() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                marginStart = dp(8)
            })
        }
    }

    private fun form(
        labels: List<String>,
        values: List<String?>,
        decimalIndexes: Set<Int> = emptySet()
    ): Pair<LinearLayout, List<EditText>> {
        require(labels.size == values.size)
        val fields = values.mapIndexed { index, value ->
            EditText(activity).apply {
                hint = "未填写"
                setText(value.orEmpty())
                background = activity.getDrawable(R.drawable.input_background)
                minHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                if (index in decimalIndexes) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            fields.forEachIndexed { index, field ->
                addView(body(labels[index]).apply {
                    setTextColor(activity.getColor(R.color.ink))
                    setPadding(0, dp(12), 0, dp(5))
                })
                addView(field, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48)
                ))
            }
        } to fields
    }

    fun handleBack(): Boolean {
        if (detailContainer.visibility != View.VISIBLE) return false
        showList()
        return true
    }

    private fun nullable(field: EditText) = field.text.toString().trim().takeIf(String::isNotEmpty)
    private fun money(cents: Long?) = cents?.let { "¥${centsToYuan(it)}" } ?: activity.getString(R.string.amount_unknown)
    private fun centsToYuan(cents: Long?) = cents?.let { BigDecimal.valueOf(it, 2).setScale(2).toPlainString() }.orEmpty()
    private fun parseMoney(value: String): Long? = value.trim().takeIf { it.matches(Regex("^\\d+(\\.\\d{1,2})?$")) }
        ?.toBigDecimalOrNull()?.movePointRight(2)?.longValueExact()
    private fun formatPrice(value: BigDecimal): String = value.setScale(if (value.abs() < BigDecimal("0.01")) 4 else 2, RoundingMode.HALF_UP).toPlainString()
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun scrollToTop() = recordsScroll.post { recordsScroll.fullScroll(View.FOCUS_UP) }

    private enum class Mode { ORDERS, PRODUCTS }

    private companion object {
        const val TAG = "RecordsController"
    }
}
