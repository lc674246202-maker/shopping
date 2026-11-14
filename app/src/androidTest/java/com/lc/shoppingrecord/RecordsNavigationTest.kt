package com.lc.shoppingrecord

import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lc.shoppingrecord.data.OrderRepository
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val repository = OrderRepository(ShoppingDatabaseHelper(activity.applicationContext))
                val result = repository.saveConfirmedOrder(
                    ConfirmedOrderDraft(
                        platform = "京东",
                        orderPaidCents = null,
                        items = listOf(
                            ConfirmedOrderItemDraft(
                                rawProductName = "测试牛奶",
                                rawSpecification = "250mL×24盒",
                                contentPerInner = "250",
                                contentUnit = "mL",
                                saleQuantity = "1",
                                saleUnit = "箱",
                                innerCountPerSaleUnit = "24",
                                paidAmountCents = null
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
                activity.findViewById<View>(R.id.showProductsButton).performClick()
                assertEquals(1, list.childCount)
                list.getChildAt(0).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.recordDetailContainer).visibility)
            }
        }
    }
}
