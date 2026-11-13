package com.lc.shoppingrecord.pricing

import com.lc.shoppingrecord.model.NormalizedMeasure
import com.lc.shoppingrecord.model.SaleSpecification
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object MeasurementNormalizer {
    private val countUnits = setOf("抽", "张", "卷", "粒", "颗", "支", "个")

    fun normalize(specification: SaleSpecification): NormalizedMeasure? {
        if (specification.contentPerInner <= BigDecimal.ZERO ||
            specification.innerCountPerSaleUnit <= BigDecimal.ZERO
        ) return null

        val rawTotal = specification.contentPerInner.multiply(specification.innerCountPerSaleUnit)
        return when (specification.contentUnit.trim().lowercase(Locale.ROOT)) {
            "mg" -> NormalizedMeasure(rawTotal.divide(BigDecimal("1000000"), 12, RoundingMode.HALF_UP), "kg")
            "g" -> NormalizedMeasure(rawTotal.divide(BigDecimal("1000"), 12, RoundingMode.HALF_UP), "kg")
            "kg" -> NormalizedMeasure(rawTotal, "kg")
            "ml" -> NormalizedMeasure(rawTotal.divide(BigDecimal("1000"), 12, RoundingMode.HALF_UP), "L")
            "l" -> NormalizedMeasure(rawTotal, "L")
            "cm" -> NormalizedMeasure(rawTotal.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP), "m")
            "m" -> NormalizedMeasure(rawTotal, "m")
            "cm²", "cm2" -> NormalizedMeasure(rawTotal.divide(BigDecimal("10000"), 12, RoundingMode.HALF_UP), "m²")
            "m²", "m2" -> NormalizedMeasure(rawTotal, "m²")
            else -> specification.contentUnit.trim().takeIf(countUnits::contains)?.let {
                NormalizedMeasure(rawTotal, it)
            }
        }
    }
}
