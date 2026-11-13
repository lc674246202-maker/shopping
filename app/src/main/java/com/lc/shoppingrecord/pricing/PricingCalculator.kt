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
    ): PriceResult {
        if (paidAmountCents == null) {
            return PriceResult(null, specification?.saleUnit, null, null, PriceIssue.MISSING_PAID_AMOUNT)
        }
        if (paidAmountCents < 0 || saleQuantity == null || saleQuantity <= BigDecimal.ZERO) {
            return PriceResult(null, specification?.saleUnit, null, null, PriceIssue.INVALID_SALE_QUANTITY)
        }

        val paidYuan = BigDecimal.valueOf(paidAmountCents, 2)
        val packagePrice = paidYuan.divide(saleQuantity, INTERNAL_SCALE, RoundingMode.HALF_UP)

        if (specification == null) {
            return PriceResult(packagePrice, null, null, null, PriceIssue.INVALID_SPECIFICATION)
        }

        val normalized = MeasurementNormalizer.normalize(specification)
            ?: return PriceResult(
                packagePrice,
                specification.saleUnit,
                null,
                null,
                PriceIssue.UNSUPPORTED_UNIT
            )

        val totalStandardQuantity = saleQuantity.multiply(normalized.quantityPerSaleUnit)
        if (totalStandardQuantity <= BigDecimal.ZERO) {
            return PriceResult(
                packagePrice,
                specification.saleUnit,
                null,
                normalized.unit,
                PriceIssue.INVALID_SPECIFICATION
            )
        }

        return PriceResult(
            packagePrice = packagePrice,
            packageUnit = specification.saleUnit,
            standardPrice = paidYuan.divide(totalStandardQuantity, INTERNAL_SCALE, RoundingMode.HALF_UP),
            standardUnit = normalized.unit
        )
    }
}
