from __future__ import annotations

import unittest
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP, getcontext


getcontext().prec = 28


@dataclass(frozen=True)
class PriceResult:
    package_price: Decimal | None
    package_unit: str | None
    standard_price: Decimal | None
    standard_unit: str | None


COUNT_UNITS = {"抽", "张", "卷", "粒", "颗", "支", "个"}


def normalize(content: Decimal, unit: str, inner_count: Decimal) -> tuple[Decimal, str] | None:
    if content <= 0 or inner_count <= 0:
        return None
    total = content * inner_count
    normalized = unit.strip().lower()
    conversions = {
        "mg": (Decimal("1000000"), "kg"),
        "g": (Decimal("1000"), "kg"),
        "kg": (Decimal("1"), "kg"),
        "ml": (Decimal("1000"), "L"),
        "l": (Decimal("1"), "L"),
        "cm": (Decimal("100"), "m"),
        "m": (Decimal("1"), "m"),
        "cm²": (Decimal("10000"), "m²"),
        "cm2": (Decimal("10000"), "m²"),
        "m²": (Decimal("1"), "m²"),
        "m2": (Decimal("1"), "m²"),
    }
    if normalized in conversions:
        divisor, standard_unit = conversions[normalized]
        return total / divisor, standard_unit
    if unit.strip() in COUNT_UNITS:
        return total, unit.strip()
    return None


def calculate(
    paid_cents: int | None,
    sale_quantity: str | None,
    content: str,
    content_unit: str,
    inner_count: str,
    sale_unit: str,
) -> PriceResult:
    if paid_cents is None or sale_quantity is None:
        return PriceResult(None, sale_unit, None, None)
    quantity = Decimal(sale_quantity)
    if paid_cents < 0 or quantity <= 0:
        return PriceResult(None, sale_unit, None, None)
    paid_yuan = Decimal(paid_cents) / Decimal(100)
    package_price = paid_yuan / quantity
    measure = normalize(Decimal(content), content_unit, Decimal(inner_count))
    if measure is None:
        return PriceResult(package_price, sale_unit, None, None)
    measure_per_sale, standard_unit = measure
    return PriceResult(
        package_price,
        sale_unit,
        paid_yuan / (quantity * measure_per_sale),
        standard_unit,
    )


class PricingReferenceTests(unittest.TestCase):
    def assert_decimal(self, expected: str, actual: Decimal | None) -> None:
        self.assertIsNotNone(actual)
        self.assertEqual(Decimal(expected), actual)

    def test_250ml_24_boxes_buy_2_paid_96(self) -> None:
        result = calculate(9600, "2", "250", "mL", "24", "箱")
        self.assert_decimal("48", result.package_price)
        self.assert_decimal("8", result.standard_price)
        self.assertEqual("L", result.standard_unit)

    def test_200ml_24_boxes_buy_1_paid_43_20(self) -> None:
        result = calculate(4320, "1", "200", "mL", "24", "箱")
        self.assert_decimal("43.2", result.package_price)
        self.assert_decimal("9", result.standard_price)

    def test_500g_3_bags_paid_24(self) -> None:
        result = calculate(2400, "1", "500", "g", "3", "组")
        self.assert_decimal("24", result.package_price)
        self.assert_decimal("16", result.standard_price)
        self.assertEqual("kg", result.standard_unit)

    def test_100_sheets_10_packs_paid_20(self) -> None:
        result = calculate(2000, "1", "100", "抽", "10", "提")
        self.assert_decimal("20", result.package_price)
        self.assert_decimal("0.02", result.standard_price)

    def test_bulk_1_35kg_paid_27(self) -> None:
        result = calculate(2700, "1", "1.35", "kg", "1", "份")
        self.assert_decimal("20", result.standard_price)

    def test_unknown_paid_amount_never_becomes_zero(self) -> None:
        result = calculate(None, "2", "250", "mL", "24", "箱")
        self.assertIsNone(result.package_price)
        self.assertIsNone(result.standard_price)

    def test_zero_quantity_never_divides(self) -> None:
        result = calculate(9600, "0", "250", "mL", "24", "箱")
        self.assertIsNone(result.package_price)
        self.assertIsNone(result.standard_price)

    def test_unknown_mixed_set_keeps_package_price_only(self) -> None:
        result = calculate(9900, "1", "1", "混合套装", "1", "套")
        self.assert_decimal("99", result.package_price)
        self.assertIsNone(result.standard_price)

    def test_standard_history_can_compare_different_milk_skus(self) -> None:
        standard_prices = [Decimal("8"), Decimal("9"), Decimal("8.5")]
        self.assertEqual(Decimal("8"), min(standard_prices))

    def test_package_history_only_compares_selected_sku(self) -> None:
        sku_250_prices = [Decimal("48"), Decimal("51"), Decimal("45")]
        other_sku_price = Decimal("43.2")
        self.assertEqual(Decimal("45"), min(sku_250_prices))
        self.assertNotIn(other_sku_price, sku_250_prices)

    def test_display_rounding_does_not_change_internal_value(self) -> None:
        result = calculate(2150, "1", "100", "抽", "10", "提")
        self.assert_decimal("0.0215", result.standard_price)
        displayed = result.standard_price.quantize(Decimal("0.001"), rounding=ROUND_HALF_UP)
        self.assertEqual(Decimal("0.022"), displayed)
        self.assert_decimal("0.0215", result.standard_price)


if __name__ == "__main__":
    unittest.main()
