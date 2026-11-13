package com.lc.shoppingrecord.model

import java.math.BigDecimal

data class SaleSpecification(
    val contentPerInner: BigDecimal,
    val contentUnit: String,
    val innerCountPerSaleUnit: BigDecimal,
    val saleUnit: String
)

data class NormalizedMeasure(
    val quantityPerSaleUnit: BigDecimal,
    val unit: String
)

data class PriceResult(
    val packagePrice: BigDecimal?,
    val packageUnit: String?,
    val standardPrice: BigDecimal?,
    val standardUnit: String?,
    val issue: PriceIssue? = null
)

enum class PriceIssue {
    MISSING_PAID_AMOUNT,
    INVALID_SALE_QUANTITY,
    INVALID_SPECIFICATION,
    UNSUPPORTED_UNIT
}
