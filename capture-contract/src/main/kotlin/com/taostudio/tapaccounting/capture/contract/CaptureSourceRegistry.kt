package com.taostudio.tapaccounting.capture.contract

import java.util.Locale

/**
 * 一个可被 hook 捕获的付款来源。
 *
 * 新增来源（例如抖音、美团）只需要在 [CaptureSourceRegistry.DEFAULT] 里追加一条注册项，
 * UI 不需要再写 when/if 分支。
 */
data class CaptureSourceEntry(
    /** 与 [CapturePayload.source] 一致，例如 [CaptureSource.WECHAT]。 */
    val id: String,
    /** 应用包名，例如 `com.tencent.mm`。 */
    val packageName: String,
    /** 用户可见的来源名称。 */
    val displayName: String,
)

/**
 * 来源注册表：source id、包名、显示名三者互查。
 *
 * 不依赖 Android，插件 / hook / App 都可以直接引用。
 */
class CaptureSourceRegistry(entries: List<CaptureSourceEntry> = emptyList()) {

    private val entriesById = LinkedHashMap<String, CaptureSourceEntry>()

    init {
        entries.forEach(::register)
    }

    /** 当前已注册的全部来源（按注册顺序）。 */
    val all: List<CaptureSourceEntry>
        get() = entriesById.values.toList()

    /**
     * 注册或覆盖一个来源。
     *
     * 同一个 id 重复注册时会覆盖旧项，不会留下过期包名映射。
     */
    fun register(entry: CaptureSourceEntry): CaptureSourceEntry {
        val normalized = entry.copy(
            id = entry.id.trim().uppercase(Locale.ROOT),
            packageName = entry.packageName.trim(),
            displayName = entry.displayName.trim(),
        )
        require(normalized.id.isNotEmpty()) { "source id 不能为空" }
        require(normalized.packageName.isNotEmpty()) { "packageName 不能为空" }
        require(normalized.displayName.isNotEmpty()) { "displayName 不能为空" }
        entriesById[normalized.id] = normalized
        return normalized
    }

    /** 按 source id 查询；未知返回 null。 */
    fun findById(id: String?): CaptureSourceEntry? =
        entriesById[id?.trim()?.uppercase(Locale.ROOT).orEmpty()]

    /** 按包名查询；未知返回 null。 */
    fun findByPackage(packageName: String?): CaptureSourceEntry? {
        val key = packageName?.trim().orEmpty()
        if (key.isEmpty()) return null
        return all.firstOrNull { it.packageName == key }
    }

    /** id 或包名都能解析，方便兼容历史数据。 */
    fun find(sourceOrPackage: String?): CaptureSourceEntry? =
        findById(sourceOrPackage) ?: findByPackage(sourceOrPackage)

    /**
     * 用户可见的来源名。
     *
     * 未知来源统一返回 [FALLBACK_DISPLAY_NAME]，不把包名直接暴露给用户。
     */
    fun displayName(sourceOrPackage: String?): String {
        val key = sourceOrPackage?.trim().orEmpty()
        if (key.isEmpty()) return FALLBACK_DISPLAY_NAME
        return find(key)?.displayName ?: FALLBACK_DISPLAY_NAME
    }

    /** 按包名取用户可见来源名；未知包名返回 [FALLBACK_DISPLAY_NAME]。 */
    fun displayNameForPackage(packageName: String?): String =
        findByPackage(packageName)?.displayName ?: FALLBACK_DISPLAY_NAME

    companion object {
        /** 无法判断来源时的兜底文案；不指向任何具体应用。 */
        const val FALLBACK_DISPLAY_NAME: String = "其他应用"

        /**
         * 生产默认注册表。
         *
         * 以后新增来源只在这里追加一项。
         */
        val DEFAULT: CaptureSourceRegistry = CaptureSourceRegistry(
            listOf(
                CaptureSourceEntry(
                    id = CaptureSource.WECHAT,
                    packageName = "com.tencent.mm",
                    displayName = "微信",
                ),
                CaptureSourceEntry(
                    id = CaptureSource.ALIPAY,
                    packageName = "com.eg.android.AlipayGphone",
                    displayName = "支付宝",
                ),
            ),
        )
    }
}

/** 默认注册表的便捷入口，避免 UI 到处写 `CaptureSourceRegistry.DEFAULT`。 */
object CaptureSources {
    val all: List<CaptureSourceEntry>
        get() = CaptureSourceRegistry.DEFAULT.all

    fun findById(id: String?): CaptureSourceEntry? =
        CaptureSourceRegistry.DEFAULT.findById(id)

    fun findByPackage(packageName: String?): CaptureSourceEntry? =
        CaptureSourceRegistry.DEFAULT.findByPackage(packageName)

    fun displayName(sourceOrPackage: String?): String =
        CaptureSourceRegistry.DEFAULT.displayName(sourceOrPackage)

    fun displayNameForPackage(packageName: String?): String =
        CaptureSourceRegistry.DEFAULT.displayNameForPackage(packageName)
}
