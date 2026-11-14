package com.lc.shoppingrecord.pricing

import com.lc.shoppingrecord.model.PriceIssue
import com.lc.shoppingrecord.model.PriceResult
import com.lc.shoppingrecord.model.SaleSpecification
import java.math.BigDecimal
import java.math.RoundingMode

object PricingCalculator {
    private const val INTERNAL_SCALE = 12

    fun calculate(
        paidAmountCents: Long?,
        saleQuantity: BigDecimal?,
        specification: SaleSpecification?
    ): PriceResult = calculate(
        paidAmountCents = paidAmountCents,
        saleQuantity = saleQuantity,
        pieceCount = specification?.innerCountPerSaleUnit,
        saleUnit = specification?.saleUnit
    )

    fun calculate(
        paidAmountCents: Long?,
        saleQuantity: BigDecimal?,
        pieceCount: BigDecimal?,
        saleUnit: String? = null
    ): PriceResult {
        if (paidAmountCents == null) {
            return PriceResult(null, saleUnit, null, null, PriceIssue.MISSING_PAID_AMOUNT)
        }
        if (paidAmountCents < 0 || saleQuantity == null || saleQuantity <= BigDecimal.ZERO) {
            return PriceResult(null, saleUnit, null, null, PriceIssue.INVALID_SALE_QUANTITY)
        }

        val paidYuan = BigDecimal.valueOf(paidAmountCents, 2)
        val packagePrice = paidYuan.divide(saleQuantity, INTERNAL_SCALE, RoundingMode.HALF_UP)

        if (pieceCount == null || pieceCount <= BigDecimal.ZERO) {
            return PriceResult(
                packagePrice,
                saleUnit,
                null,
                null,
                PriceIssue.INVALID_SPECIFICATION
            )
        }

        return PriceResult(
            packagePrice = packagePrice,
            packageUnit = saleUnit,
            standardPrice = packagePrice.divide(pieceCount, INTERNAL_SCALE, RoundingMode.HALF_UP),
            standardUnit = "件"
        )
    }
}
