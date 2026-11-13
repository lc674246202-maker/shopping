package com.lc.shoppingrecord.model

data class ConfirmedOrderDraft(
    val platform: String,
    val externalOrderNo: String? = null,
    val storeName: String? = null,
    val orderDate: String? = null,
    val paidDate: String? = null,
    val orderPaidCents: Long? = null,
    val orderOriginalCents: Long? = null,
    val discountCents: Long? = null,
    val shippingCents: Long? = null,
    val orderStatus: String? = null,
    val note: String? = null,
    val items: List<ConfirmedOrderItemDraft>
)

data class ConfirmedOrderItemDraft(
    val productId: String? = null,
    val skuId: String? = null,
    val standardName: String? = null,
    val brand: String? = null,
    val category: String? = null,
    val rawProductName: String,
    val isNameTruncated: Boolean? = null,
    val rawSpecification: String? = null,
    val measurementType: String? = null,
    val contentPerInner: String? = null,
    val contentUnit: String? = null,
    val baseUnit: String? = null,
    val baseQuantityPerSaleUnit: String? = null,
    val saleQuantity: String? = null,
    val saleUnit: String? = null,
    val innerUnit: String? = null,
    val innerCountPerSaleUnit: String? = null,
    val originalAmountCents: Long? = null,
    val paidAmountCents: Long? = null,
    val priceSource: String = "user_confirmed",
    val sourceReferencesJson: String? = null
)

sealed interface SaveOrderResult {
    data class Saved(val orderId: String, val itemIds: List<String>) : SaveOrderResult
    data class Duplicate(val existingOrderId: String) : SaveOrderResult
    data class PossibleDuplicate(val existingOrderId: String) : SaveOrderResult
    data class Rejected(val reason: String) : SaveOrderResult
}
