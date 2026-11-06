package com.taostudio.tapaccounting.capture.data

import android.content.Context
import android.util.Log
import com.taostudio.tapaccounting.capture.parse.AutoAccountingRuleEngine
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 主应用侧 `filesDir/rule_bundle.json` 的读写入口。
 *
 * 与插件版的区别：
 * - **不再从 APK assets 兜底**。AutoAccounting 规则包属于第三方 GPL 内容，不放进主 APK；
 *   首次由 [com.taostudio.tapaccounting.capture.rule.CaptureRuleBundleBootstrap] 从插件导出，
 *   或由用户/调试命令导入。
 * - 文件不存在/损坏时返回空 bundle，规则引擎视为“没有可执行规则”，只走本地备注/资产规则。
 * - 所有写入先写临时文件、fsync 后 rename；写入成功后调用 [AutoAccountingRuleEngine.invalidate]。
 */
object RuleBundleStore {

    const val FILE_NAME = "rule_bundle.json"
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
                RuleBundleCodec.decode(file.readText(Charsets.UTF_8))
            } catch (t: Throwable) {
                Log.w(TAG, "rule bundle invalid, use empty rules: ${t.message}")
                emptyBundle()
            }
        } else {
            emptyBundle()
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
                temp.delete()
            }
        }
        cachedBundle = stamped
        cachedStamp = stampOf(target)
        AutoAccountingRuleEngine.invalidate()
        return stamped
    }

    /** 导入/覆盖整包规则；解析失败抛出异常，调用方保留旧文件。 */
    @Synchronized
    fun installFromJson(context: Context, json: String): RuleBundleCodec.Bundle {
        val decoded = RuleBundleCodec.decode(json)
        return save(context, decoded.copy(source = RuleBundleCodec.SOURCE_IMPORT))
    }

    /** 兼容插件 UI 的命名。 */
    @Synchronized
    fun importJson(context: Context, json: String): RuleBundleCodec.Bundle =
        installFromJson(context, json)

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
    ): RuleBundleCodec.Bundle = setEnabledBatch(context, listOf(rule), enabled)

    /**
     * 批量启停：只 load/save 一次，避免逐条写整个 rule_bundle.json。
     * UI 层应只更新受影响的卡片，不要整页 reload。
     */
    @Synchronized
    fun setEnabledBatch(
        context: Context,
        rules: List<RuleBundleCodec.Rule>,
        enabled: Boolean,
    ): RuleBundleCodec.Bundle {
        if (rules.isEmpty()) return load(context)
        val identities = rules.map { it.systemRuleName }.toSet()
        val bundle = load(context)
        var changed = false
        val updated = bundle.rules.map { existing ->
            if (existing.systemRuleName in identities) {
                if (existing.enabled != enabled) changed = true
                existing.copy(enabled = enabled)
            } else {
                existing
            }
        }
        if (!changed) return bundle
        return save(
            context,
            bundle.copy(rules = updated, source = RuleBundleCodec.SOURCE_LOCAL),
        )
    }

    private fun sameIdentity(a: RuleBundleCodec.Rule, b: RuleBundleCodec.Rule): Boolean =
        a.systemRuleName == b.systemRuleName

    /** 规则页导出；空规则包也能导出结构，方便用户手工编辑。 */
    @Synchronized
    fun exportJson(context: Context, pretty: Boolean = true): String =
        RuleBundleCodec.encode(load(context), pretty = pretty)

    fun hasBundle(context: Context): Boolean {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return file.isFile && file.length() > 4
    }

    private fun emptyBundle(): RuleBundleCodec.Bundle = RuleBundleCodec.Bundle(
        common = "",
        category = "",
        rules = emptyList(),
        version = RuleBundleCodec.VERSION,
        date = 0L,
        source = RuleBundleCodec.SOURCE_BUILTIN,
    )

    private fun stampOf(file: File): Long =
        if (file.isFile) file.lastModified() * 31L + file.length() else -1L
}
