package com.lc.shoppingrecord.pricing

import com.lc.shoppingrecord.model.PriceIssue
import com.lc.shoppingrecord.model.SaleSpecification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PricingCalculatorTest {
    @Test
    fun milk250ml24_buy2_paid96() {
        val result = calculate("250", "mL", "24", "箱", "2", 9600)
        assertDecimal("48", result.packagePrice)
        assertDecimal("2", result.standardPrice)
        assertEquals("件", result.standardUnit)
    }

    @Test
    fun milk200ml24_buy1_paid43_20() {
        val result = calculate("200", "mL", "24", "箱", "1", 4320)
        assertDecimal("43.2", result.packagePrice)
        assertDecimal("1.8", result.standardPrice)
    }

    @Test
    fun bags500g3_paid24() {
        val result = calculate("500", "g", "3", "组", "1", 2400)
        assertDecimal("24", result.packagePrice)
        assertDecimal("8", result.standardPrice)
        assertEquals("件", result.standardUnit)
    }

    @Test
    fun tissue100sheets10_paid20() {
        val result = calculate("100", "抽", "10", "提", "1", 2000)
        assertDecimal("20", result.packagePrice)
        assertDecimal("2", result.standardPrice)
        assertEquals("件", result.standardUnit)
    }

    @Test
    fun bulk1_35kg_paid27() {
        val result = calculate("1.35", "kg", "1", "份", "1", 2700)
        assertDecimal("27", result.standardPrice)
    }

    @Test
    fun missingLinePaidDoesNotCreatePrice() {
        val result = calculate("250", "mL", "24", "箱", "2", null)
        assertNull(result.packagePrice)
        assertNull(result.standardPrice)
        assertEquals(PriceIssue.MISSING_PAID_AMOUNT, result.issue)
    }

    @Test
    fun zeroQuantityDoesNotDivide() {
        val result = calculate("250", "mL", "24", "箱", "0", 9600)
        assertNull(result.packagePrice)
        assertEquals(PriceIssue.INVALID_SALE_QUANTITY, result.issue)
    }

    @Test
    fun mixedSetUsesVisiblePieceCountOnly() {
        val result = calculate("1", "混合套装", "1", "套", "1", 9900)
        assertDecimal("99", result.packagePrice)
        assertDecimal("99", result.standardPrice)
    }

    @Test
    fun missingPieceCountKeepsProductUnitPriceOnly() {
        val result = PricingCalculator.calculate(2400, BigDecimal.ONE, null, "箱")
        assertDecimal("24", result.packagePrice)
        assertNull(result.standardPrice)
        assertEquals(PriceIssue.INVALID_SPECIFICATION, result.issue)
    }

    private fun calculate(
        content: String,
        contentUnit: String,
        innerCount: String,
        saleUnit: String,
        saleQuantity: String,
        paidCents: Long?
    ) = PricingCalculator.calculate(
        paidAmountCents = paidCents,
        saleQuantity = BigDecimal(saleQuantity),
        specification = SaleSpecification(
            contentPerInner = BigDecimal(content),
            contentUnit = contentUnit,
            innerCountPerSaleUnit = BigDecimal(innerCount),
            saleUnit = saleUnit
        )
    )

    private fun assertDecimal(expected: String, actual: BigDecimal?) {
        assertTrue("Expected $expected but was $actual", actual != null && BigDecimal(expected).compareTo(actual) == 0)
    }
}
