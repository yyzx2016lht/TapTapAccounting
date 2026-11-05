package com.taostudio.tapaccounting.capture.data

import android.content.Context
import android.util.Log
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * `filesDir/rule_bundle.json` 的读写入口。
 *
 * - 文件存在：以 bundle 为准；bundle 损坏时记录日志并回退内置资产，保证付款解析不中断。
 * - 文件不存在：直接回退 `assets/autoaccounting_rules.json`。
 * - 所有写入都先写同目录临时文件、fsync 后再 rename；rename 失败时旧文件保持不变。
 * - 写入成功后调用 [AutoAccountingRuleEngine.invalidate]，下一次付款解析立即用新规则。
 */
object RuleBundleStore {

    const val FILE_NAME = "rule_bundle.json"
    private const val ASSET_NAME = "autoaccounting_rules.json"
    private const val TAG = "TapCaptureRule"

    @Volatile
    private var cachedBundle: RuleBundleCodec.Bundle? = null

    @Volatile
    private var cachedStamp: Long = Long.MIN_VALUE

    @Synchronized
    fun load(context: Context): RuleBundleCodec.Bundle {
        val app = context.applicationContext ?: context
        val file = File(app.filesDir, FILE_NAME)
        val stamp = stampOf(file)
        cachedBundle?.takeIf { cachedStamp == stamp }?.let { return it }

        val bundle = if (file.isFile) {
            try {
                val decoded = RuleBundleCodec.decode(file.readText(Charsets.UTF_8))
                if (decoded.version < RuleBundleCodec.VERSION) {
                    val assetBundle = loadAssets(app)
                    val merged = RuleBundleCodec.mergeForSync(
                        existing = decoded,
                        syncedRules = assetBundle.rules,
                        common = assetBundle.common,
                        category = assetBundle.category,
                    ).copy(version = RuleBundleCodec.VERSION)
                    save(context, merged)
                } else {
                    decoded
                }
            } catch (t: Throwable) {
                Log.w(TAG, "rule bundle invalid, fallback to assets: ${t.message}")
                loadAssets(app)
            }
        } else {
            loadAssets(app)
        }
        cachedBundle = bundle
        cachedStamp = stamp
        return bundle
    }

    @Synchronized
    fun save(context: Context, bundle: RuleBundleCodec.Bundle): RuleBundleCodec.Bundle {
        val app = context.applicationContext ?: context
        val stamped = bundle.copy(date = System.currentTimeMillis())
        val text = RuleBundleCodec.encode(stamped)
        // 先自校验，避免把半截/结构错误的 bundle 写进唯一规则文件。
        RuleBundleCodec.decode(text)

        val dir = app.filesDir
        val target = File(dir, FILE_NAME)
        val temp = File(dir, "$FILE_NAME.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target)) {
                throw IOException("规则临时文件替换失败")
            }
        } finally {
            if (temp.exists()) {
                // 只清理本次自己创建的临时文件；target 未被 rename 成功时仍是旧内容。
                temp.delete()
            }
        }
        cachedBundle = stamped
        cachedStamp = stampOf(target)
        AutoAccountingRuleEngine.invalidate()
        return stamped
    }

    /** 新增或覆盖一条用户规则；用户规则写回时保持在文件前部。 */
    @Synchronized
    fun upsert(context: Context, rule: RuleBundleCodec.Rule): RuleBundleCodec.Bundle {
        val bundle = load(context)
        val rest = bundle.rules.filterNot { sameIdentity(it, rule) }
        val rules = if (rule.isUser) listOf(rule) + rest else rest + rule
        return save(
            context,
            bundle.copy(rules = rules, source = RuleBundleCodec.SOURCE_LOCAL),
        )
    }

    @Synchronized
    fun delete(context: Context, rule: RuleBundleCodec.Rule): RuleBundleCodec.Bundle {
        val bundle = load(context)
        return save(
            context,
            bundle.copy(
                rules = bundle.rules.filterNot { sameIdentity(it, rule) },
                source = RuleBundleCodec.SOURCE_LOCAL,
            ),
        )
    }

    @Synchronized
    fun setEnabled(
        context: Context,
        rule: RuleBundleCodec.Rule,
        enabled: Boolean,
    ): RuleBundleCodec.Bundle {
        val bundle = load(context)
        val rules = bundle.rules.map {
            if (sameIdentity(it, rule)) it.copy(enabled = enabled) else it
        }
        return save(
            context,
            bundle.copy(rules = rules, source = RuleBundleCodec.SOURCE_LOCAL),
        )
    }

    @Synchronized
    fun importJson(context: Context, json: String): RuleBundleCodec.Bundle {
        val decoded = try {
            RuleBundleCodec.decode(json)
        } catch (t: Throwable) {
            throw IllegalArgumentException(t.message ?: t.javaClass.simpleName)
        }
        return save(context, decoded.copy(source = RuleBundleCodec.SOURCE_IMPORT))
    }

    @Synchronized
    fun exportJson(context: Context): String =
        RuleBundleCodec.encode(load(context), pretty = true)

    /**
     * 规则变量名在 bundle 内唯一，用它做更新/删除的稳定身份。
     * 这样编辑时改应用包名/数据类型不会残留旧规则。
     */
    private fun sameIdentity(a: RuleBundleCodec.Rule, b: RuleBundleCodec.Rule): Boolean =
        a.systemRuleName == b.systemRuleName

    private fun stampOf(file: File): Long =
        if (file.isFile) file.lastModified() * 31L + file.length() else -1L

    private fun loadAssets(context: Context): RuleBundleCodec.Bundle {
        val text = context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return RuleBundleCodec.fromAssetJson(text)
    }
}
