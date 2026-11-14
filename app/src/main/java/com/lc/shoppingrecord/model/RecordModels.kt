package com.lc.shoppingrecord.model

import java.math.BigDecimal

data class OrderSummary(
    val orderId: String,
    val platform: String,
    val displayDate: String?,
    val storeName: String?,
    val externalOrderNo: String?,
    val orderPaidCents: Long?,
    val itemCount: Int,
    val productSummary: String,
    val needsReview: Boolean
)

data class OrderDetail(
    val orderId: String,
    val platform: String,
    val externalOrderNo: String?,
    val storeName: String?,
    val orderDate: String?,
    val paidDate: String?,
    val orderPaidCents: Long?,
    val orderStatus: String?,
    val note: String?,
    val items: List<OrderItemRecord>
)

data class OrderItemRecord(
    val itemId: String,
    val orderId: String,
    val productId: String?,
    val skuId: String?,
    val rawProductName: String,
    val rawSpecification: String?,
    val saleQuantity: String?,
    val saleUnit: String?,
    val pieceCount: String?,
    val paidAmountCents: Long?,
    val priceSource: String?,
    val specificationSnapshotJson: String?
)

data class OrderItemUpdate(
    val itemId: String,
    val name: String,
    val model: String?,
    val quantity: String?,
    val pieceCount: String?,
    val paidAmountCents: Long?
)

data class ProductSummary(
    val productId: String,
    val displayName: String,
    val brand: String?,
    val category: String?,
    val specification: String?,
    val saleUnit: String?,
    val purchaseCount: Int,
    val preferredPriceMode: String?,
    val comparisonQuantity: String?,
    val comparisonUnit: String?
)

data class SkuSummary(
    val skuId: String,
    val specRaw: String?,
    val saleUnit: String?
)

data class PriceHistoryEntry(
    val itemId: String,
    val skuId: String?,
    val date: String?,
    val platform: String,
    val storeName: String?,
    val specification: String?,
    val saleQuantity: BigDecimal,
    val saleUnit: String?,
    val paidAmountCents: Long,
    val packagePrice: BigDecimal?,
    val packageUnit: String?,
    val standardPrice: BigDecimal?,
    val standardUnit: String?
)
