package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill

/**
 * 「把账单移动到其他资产」的纯函数部分：只决定账单的哪个账户字段要改指目标资产，不碰数据库。
 *
 * 余额影响不在这里：移动必须走 [BillMutationService.moveBillsToAsset]，
 * 由它先回滚旧资产的余额影响、改完绑定再应用到目标资产。
 * 只把 `accountId`/`accountName` 改掉的路径会让两边资产余额都不动——这正是历史 bug。
 */
object BillAssetMovePlanner {

    /**
     * 返回改挂到 [targetAsset] 后的账单；`account` 与 `toAccount` 都不指向 [sourceAsset] 时返回 null
     * （属于不动，调用方应跳过该账单）。
     *
     * 转账要把指向源资产的每一端都换掉（可能只有转出端、只有转入端，或两端都指向源资产）；
     * 非转账只换匹配到的那一端。除资产字段外其余字段原样保留（金额、分类、扣款组等一律不动）。
     */
    fun moveToAsset(bill: Bill, sourceAsset: Asset, targetAsset: Asset): Bill? {
        val matchSourceAccount = bill.accountId == sourceAsset.id ||
            (bill.accountId == null && bill.accountName == sourceAsset.name)
        val matchSourceToAccount = bill.toAccountId == sourceAsset.id ||
            (bill.toAccountId == null && bill.toAccountName == sourceAsset.name)

        val updated = if (bill.type == Bill.TYPE_TRANSFER) {
            when {
                matchSourceAccount && matchSourceToAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name,
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                matchSourceAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name
                )
                matchSourceToAccount -> bill.copy(
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                else -> null
            }
        } else {
            when {
                matchSourceAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name
                )
                matchSourceToAccount -> bill.copy(
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                else -> null
            }
        }
        return if (updated != null && updated != bill) updated else null
    }
}
