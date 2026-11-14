package com.lc.shoppingrecord

import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lc.shoppingrecord.data.OrderRepository
import com.lc.shoppingrecord.data.RecordsRepository
import com.lc.shoppingrecord.data.ShoppingDatabaseHelper
import com.lc.shoppingrecord.model.ConfirmedOrderDraft
import com.lc.shoppingrecord.model.ConfirmedOrderItemDraft
import com.lc.shoppingrecord.model.SaveOrderResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordsNavigationTest {
    @Before
    fun resetDatabase() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .deleteDatabase("shopping_record.db")
    }

    @Test
    fun missingPaidAmountsStillOpenOrderAndProductDetails() {
        assertOrderAndProductDetailsOpen(orderPaidCents = null, itemPaidCents = null)
    }

    @Test
    fun knownPaidAmountsOpenOrderAndProductDetails() {
        assertOrderAndProductDetailsOpen(orderPaidCents = 4_320, itemPaidCents = 4_320)
    }

    @Test
    fun manualEntryPrefillsFromMostRecentMatchingProduct() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val database = ShoppingDatabaseHelper(activity.applicationContext)
                OrderRepository(database).saveConfirmedOrder(
                    ConfirmedOrderDraft(
                        platform = "京东",
                        items = listOf(
                            ConfirmedOrderItemDraft(
                                rawProductName = "测试牛奶",
                                rawSpecification = "250mL×24盒",
                                contentPerInner = "250",
                                contentUnit = "mL",
                                saleQuantity = "1",
                                saleUnit = "箱",
                                innerCountPerSaleUnit = "24",
                                paidAmountCents = 4_320
                            )
                        )
                    )
                )

                activity.findViewById<View>(R.id.manualEntryButton).performClick()
                activity.findViewById<AutoCompleteTextView>(R.id.itemProductName).setText("测试牛奶")
                assertEquals("250mL×24盒", activity.findViewById<EditText>(R.id.itemRawSpecification).text.toString())
                assertEquals("1", activity.findViewById<EditText>(R.id.itemPurchaseQuantity).text.toString())
                assertEquals("24", activity.findViewById<EditText>(R.id.itemInnerCount).text.toString())
                assertEquals("43.20", activity.findViewById<EditText>(R.id.itemPaidAmount).text.toString())
            }
        }
    }

    @Test
    fun approvedUiMovesFromAddHomeToEditableConfirmation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    "购物记录",
                    activity.findViewById<TextView>(R.id.recognizePageTitle).text.toString()
                )
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.recognitionHomeContainer).visibility
                )

                activity.findViewById<View>(R.id.manualEntryButton).performClick()

                assertEquals(
                    "确认购买信息",
                    activity.findViewById<TextView>(R.id.recognizePageTitle).text.toString()
                )
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.recognitionHomeContainer).visibility
                )
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.recognitionResultContainer).visibility
                )
                activity.findViewById<View>(R.id.recognitionBackButton).performClick()
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.recognitionHomeContainer).visibility
                )
            }
        }
    }

    @Test
    fun sameNameAndSpecificationShareOneProductHistoryGroup() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val database = ShoppingDatabaseHelper(activity.applicationContext)
                val orders = OrderRepository(database)
                repeat(2) { index ->
                    orders.saveConfirmedOrder(
                        ConfirmedOrderDraft(
                            platform = if (index == 0) "京东" else "淘宝",
                            items = listOf(
                                ConfirmedOrderItemDraft(
                                    rawProductName = "测试牛奶",
                                    rawSpecification = "250mL×24盒",
                                    contentPerInner = "250",
                                    contentUnit = "mL",
                                    saleQuantity = "1",
                                    saleUnit = "箱",
                                    innerCountPerSaleUnit = "24",
                                    paidAmountCents = if (index == 0) 4_320 else 4_800
                                )
                            )
                        )
                    )
                }
                val records = RecordsRepository(database)
                val groups = records.listProducts()
                assertEquals(1, groups.size)
                assertEquals(2, groups.single().purchaseCount)
                val history = records.priceHistory(groups.single())
                assertEquals(2, history.size)
                assertEquals("件", history.first().standardUnit)
                assertEquals(
                    setOf("1.800000000000", "2.000000000000"),
                    history.map { it.standardPrice!!.toPlainString() }.toSet()
                )
            }
        }
    }

    private fun assertOrderAndProductDetailsOpen(orderPaidCents: Long?, itemPaidCents: Long?) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val repository = OrderRepository(ShoppingDatabaseHelper(activity.applicationContext))
                val result = repository.saveConfirmedOrder(
                    ConfirmedOrderDraft(
                        platform = "京东",
                        orderPaidCents = orderPaidCents,
                        items = listOf(
                            ConfirmedOrderItemDraft(
                                rawProductName = "测试牛奶",
                                rawSpecification = "250mL×24盒",
                                contentPerInner = "250",
                                contentUnit = "mL",
                                saleQuantity = "1",
                                saleUnit = "箱",
                                innerCountPerSaleUnit = "24",
                                paidAmountCents = itemPaidCents
                            )
                        )
                    )
                )
                assertTrue(result is SaveOrderResult.Saved)

                activity.findViewById<View>(R.id.navRecords).performClick()
                val list = activity.findViewById<LinearLayout>(R.id.recordsListContainer)
                assertEquals(1, list.childCount)
                list.getChildAt(0).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.recordDetailContainer).visibility)

                activity.findViewById<View>(R.id.recordsBackButton).performClick()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.recordDetailContainer).visibility)
                activity.findViewById<View>(R.id.showOrdersButton).performClick()
                assertEquals(1, list.childCount)
                list.getChildAt(0).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.recordDetailContainer).visibility)
            }
        }
    }
}
