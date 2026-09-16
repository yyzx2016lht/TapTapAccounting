package com.taostudio.tapaccounting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIAmountSplitGuardTest {

    @Test
    fun collapsesBillsWhenUserOnlyGaveOneTotalNumber() {
        val root = bills(
            bill(10.0, "可乐"),
            bill(10.0, "鸡翅"),
            bill(10.0, "西瓜")
        )

        val collapsed = collapseSingleTotalSplitBills(root, "又买了个可乐+鸡翅+西瓜30")

        assertTrue(collapsed)
        assertEquals(1, root.getJSONArray("bills").length())
        assertEquals(30.0, root.getJSONArray("bills").getJSONObject(0).getDouble("amount"), 0.001)
        assertEquals("可乐、鸡翅、西瓜", root.getJSONArray("bills").getJSONObject(0).getString("remarks"))
    }

    @Test
    fun keepsBillsWhenEachItemHasItsOwnPrice() {
        val root = bills(
            bill(10.0, "可乐"),
            bill(20.0, "西瓜")
        )

        val collapsed = collapseSingleTotalSplitBills(root, "可乐10，西瓜20")

        assertFalse(collapsed)
        assertEquals(2, root.getJSONArray("bills").length())
    }

    @Test
    fun keepsBillsWhenPerItemKeywordImpliesRepeatedAmount() {
        val root = bills(
            bill(10.0, "可乐"),
            bill(10.0, "鸡翅"),
            bill(10.0, "西瓜")
        )

        val collapsed = collapseSingleTotalSplitBills(root, "三样各10块")

        assertFalse(collapsed)
        assertEquals(3, root.getJSONArray("bills").length())
    }

    @Test
    fun keepsBillsWhenSplitSumDoesNotMatchStatedNumber() {
        val root = bills(
            bill(500.0, "给爸转账"),
            bill(500.0, "给妈转账")
        )

        val collapsed = collapseSingleTotalSplitBills(root, "给爸妈各转了500")

        assertFalse(collapsed)
        assertEquals(2, root.getJSONArray("bills").length())
    }

    @Test
    fun keepsBillsWhenTypesOrCurrenciesDiffer() {
        val mixedType = JSONObject().apply {
            put("bills", JSONArray().apply {
                put(bill(20.0, "可乐"))
                put(bill(10.0, "报销到账").apply { put("type", 1) })
            })
        }
        assertFalse(collapseSingleTotalSplitBills(mixedType, "可乐加报销一共30"))

        val mixedCurrency = JSONObject().apply {
            put("bills", JSONArray().apply {
                put(bill(20.0, "可乐"))
                put(bill(10.0, "寿司").apply { put("currency", "JPY") })
            })
        }
        assertFalse(collapseSingleTotalSplitBills(mixedCurrency, "可乐加寿司一共30"))
    }

    @Test
    fun ignoresChineseNumeralTotalsToAvoidFalsePositives() {
        val root = bills(
            bill(10.0, "可乐"),
            bill(20.0, "西瓜")
        )

        val collapsed = collapseSingleTotalSplitBills(root, "可乐和西瓜一共三十块")

        assertFalse(collapsed)
        assertEquals(2, root.getJSONArray("bills").length())
    }

    private fun bill(amount: Double, remarks: String): JSONObject =
        JSONObject().apply {
            put("amount", amount)
            put("type", 0)
            put("currency", "CNY")
            put("remarks", remarks)
        }

    private fun bills(vararg items: JSONObject): JSONObject =
        JSONObject().apply {
            put("bills", JSONArray().apply { items.forEach(::put) })
        }
}
