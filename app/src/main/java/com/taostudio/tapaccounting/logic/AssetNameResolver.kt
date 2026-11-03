package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Asset

/**
 * 把支付渠道里的原始户名折算成 App 本地资产。
 *
 * AutoAccounting 给的是“零钱”“招商银行储蓄卡(4852)”这类渠道名；
 * App 的资产通常是“微信”“招商银行”。这里只做保守匹配：
 * 精确名 -> 去掉卡号/卡种 -> 明确别名 -> 包含本地资产名的最长匹配。
 * 匹配不到返回 null，调用方保留原值并让用户自己选，绝不猜一个错误资产。
 */
object AssetNameResolver {

    private data class Alias(val keyword: String, val targets: List<String>)

    private val aliases = listOf(
        Alias("零钱", listOf("微信", "微信零钱", "微信零钱通")),
        Alias("余额宝", listOf("余额宝")),
        Alias("账户余额", listOf("支付宝", "微信")),
        Alias("余额", listOf("支付宝", "微信")),
        Alias("花呗", listOf("花呗")),
        Alias("白条", listOf("京东白条")),
        Alias("抖音月付", listOf("抖音月付")),
        Alias("美团月付", listOf("美团月付")),
        Alias("微信", listOf("微信", "微信零钱", "微信零钱通")),
        Alias("支付宝", listOf("支付宝")),
        Alias("云闪付", listOf("云闪付")),
        Alias("现金", listOf("现金")),
    ).sortedByDescending { it.keyword.length }

    fun resolve(assets: List<Asset>, rawName: String?): Asset? {
        val raw = rawName?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val active = assets.filterNot { it.isArchived }
        if (active.isEmpty()) return null

        active.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.let { return it }

        val normalized = raw
            .replace(Regex("[（(]\\d{2,6}[）)]"), "")
            .replace(Regex("\\s*(储蓄卡|信用卡|借记卡|银行卡|卡)$"), "")
            .trim()
        if (normalized.isNotEmpty()) {
            active.firstOrNull { it.name.equals(normalized, ignoreCase = true) }?.let { return it }
        }

        for (alias in aliases) {
            if (!raw.contains(alias.keyword)) continue
            alias.targets.forEach { target ->
                active.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { return it }
            }
        }

        // 例如“招商银行储蓄卡(4852)”包含本地资产“招商银行”；
        // 取最长匹配，避免同时命中“微信”和“微信零钱通”时选错。
        return active
            .filter { raw.contains(it.name, ignoreCase = true) }
            .maxByOrNull { it.name.length }
    }
}
