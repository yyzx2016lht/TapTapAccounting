package com.taostudio.tapaccounting.capture.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * `filesDir/rule_bundle.json` 的纯逻辑编解码器。
 *
 * 文件结构固定为：
 * `{common, category, rules:[...], version, date, source}`。
 * 每条规则保留 AutoAccounting 规则 JSON 的字段命名（app/type/name/enabled/js/
 * systemRuleName/id/struct/autoRecord/updateAt），并额外记录：
 * - `source`：内置 / 我的 / AI 学习 / 导入，用于规则卡片展示；
 * - `creator`：system / user，决定规则是否可编辑、删除。
 *
 * 这里不碰 Android API，方便 JVM 单测覆盖序列化、校验与排序。
 */
object RuleBundleCodec {

    const val VERSION = 1

    const val SOURCE_BUILTIN = "builtin"
    const val SOURCE_LOCAL = "local"
    const val SOURCE_USER = "user"
    const val SOURCE_AI = "ai"
    const val SOURCE_IMPORT = "import"

    const val CREATOR_SYSTEM = "system"
    const val CREATOR_USER = "user"

    const val TYPE_DATA = "DATA"
    const val TYPE_NOTICE = "NOTICE"

    private val RULE_NAME = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

    data class Rule(
        val app: String,
        /** DATA / NOTICE。 */
        val type: String,
        val name: String,
        val enabled: Boolean,
        val js: String,
        val systemRuleName: String,
        val source: String = SOURCE_USER,
        val creator: String = CREATOR_USER,
        val id: Long = 0L,
        val struct: String = "",
        val autoRecord: Boolean = false,
        val updateAt: Long = 0L,
        /** 自定义规则表单的原始字段，仅用于回填编辑；原始 JS 编辑后清空。 */
        val meta: JSONObject? = null,
    ) {
        val isUser: Boolean get() = creator == CREATOR_USER
        val typeLabel: String get() = if (type == TYPE_NOTICE) "通知" else "数据"
        val sourceLabel: String get() = sourceLabel(source, creator)

        fun toJson(): JSONObject = JSONObject().apply {
            put("app", app)
            put("type", type)
            put("name", name)
            put("enabled", enabled)
            put("js", js)
            put("systemRuleName", systemRuleName)
            put("id", id)
            put("struct", struct)
            put("autoRecord", autoRecord)
            put("updateAt", updateAt)
            put("source", source)
            put("creator", creator)
            meta?.let { put("meta", it) }
        }

        companion object {
            fun isValidRuleName(name: String): Boolean = RULE_NAME.matches(name)

            /**
             * 解析单条规则；缺少 app/type/js/systemRuleName 的条目返回 null。
             * [fallbackSource] 用于老资产文件：没有 source 时安全地标成“内置”。
             */
            fun fromJson(obj: JSONObject, fallbackSource: String = SOURCE_IMPORT): Rule? {
                if (obj.isNull("app") || obj.isNull("js") || obj.isNull("systemRuleName")) return null
                val app = obj.optString("app").trim()
                val type = obj.optString("type").trim().uppercase(Locale.US)
                val js = obj.optString("js")
                val systemRuleName = obj.optString("systemRuleName").trim()
                if (app.isBlank() || js.isBlank() || !isValidRuleName(systemRuleName)) return null
                if (type != TYPE_DATA && type != TYPE_NOTICE) return null

                val rawSource = obj.optString("source").trim().lowercase(Locale.US)
                val rawCreator = obj.optString("creator").trim().lowercase(Locale.US)
                val creator = when (rawCreator) {
                    CREATOR_USER -> CREATOR_USER
                    CREATOR_SYSTEM -> CREATOR_SYSTEM
                    else -> when (rawSource) {
                        SOURCE_USER, SOURCE_AI -> CREATOR_USER
                        else -> CREATOR_SYSTEM
                    }
                }
                val source = when (rawSource) {
                    SOURCE_BUILTIN, SOURCE_USER, SOURCE_AI, SOURCE_IMPORT -> rawSource
                    SOURCE_LOCAL -> SOURCE_IMPORT
                    else -> if (creator == CREATOR_USER) SOURCE_USER else fallbackSource
                }

                return Rule(
                    app = app,
                    type = type,
                    name = obj.optString("name").trim().ifBlank { systemRuleName },
                    enabled = booleanValue(obj, "enabled", true),
                    js = js,
                    systemRuleName = systemRuleName,
                    source = source,
                    creator = creator,
                    id = obj.optLong("id", 0L),
                    struct = obj.optString("struct", ""),
                    autoRecord = booleanValue(obj, "autoRecord", false),
                    updateAt = obj.optLong("updateAt", 0L),
                    meta = obj.optJSONObject("meta"),
                )
            }
        }
    }

    data class Bundle(
        val common: String,
        val category: String = "",
        val rules: List<Rule> = emptyList(),
        val version: Int = VERSION,
        val date: Long = System.currentTimeMillis(),
        val source: String = SOURCE_BUILTIN,
    ) {
        val userCount: Int get() = rules.count { it.isUser }
        val systemCount: Int get() = rules.count { !it.isUser }
        val enabledCount: Int get() = rules.count { it.enabled }
        val sourceLabel: String get() = sourceLabel(source, "")

        fun toJson(): JSONObject = JSONObject().apply {
            put("common", common)
            put("category", category)
            put("rules", JSONArray().apply { rules.forEach { put(it.toJson()) } })
            put("version", version)
            put("date", date)
            put("source", source)
        }
    }

    /** 兼容规则列表接口返回 Boolean / 1-0 / "true"-"false" 三种 enabled 形式。 */
    fun booleanValue(obj: JSONObject, key: String, default: Boolean): Boolean {
        if (!obj.has(key) || obj.isNull(key)) return default
        return when (val value = obj.opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase(Locale.US)) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> default
            }
            else -> default
        }
    }

    fun sourceLabel(source: String, creator: String): String = when (source) {
        SOURCE_BUILTIN -> "内置"
        SOURCE_AI -> "AI 学习"
        SOURCE_IMPORT, SOURCE_LOCAL -> "导入"
        SOURCE_USER -> "我的"
        else -> if (creator == CREATOR_USER) "我的" else "导入"
    }

    /** 经费从本机同步时：用户规则原样保留，系统规则整体替换为最新拉取结果。 */
    fun mergeForSync(
        existing: Bundle,
        syncedRules: List<Rule>,
        common: String,
        category: String,
        date: Long = System.currentTimeMillis(),
    ): Bundle {
        val userRules = existing.rules.filter { it.isUser }
        return Bundle(
            common = common.ifBlank { existing.common },
            category = category.ifBlank { existing.category },
            rules = userRules + syncedRules,
            version = VERSION,
            date = date,
            source = SOURCE_LOCAL,
        )
    }

    /** 引擎执行顺序：用户规则永远排在系统/导入规则前。 */
    fun orderedForEngine(rules: List<Rule>): List<Rule> =
        rules.filter { it.isUser } + rules.filterNot { it.isUser }

    fun encode(bundle: Bundle, pretty: Boolean = false): String =
        if (pretty) bundle.toJson().toString(2) else bundle.toJson().toString()

    /**
     * 严格解析 bundle。导入文件、读写本地文件都先走这里；
     * 结构不合法直接抛异常，调用方据此保留旧文件。
     */
    fun decode(text: String): Bundle {
        val root = try {
            JSONObject(text)
        } catch (t: Throwable) {
            throw IllegalArgumentException("不是合法的 JSON：${t.message ?: t.javaClass.simpleName}")
        }
        val common = root.optString("common")
        require(common.isNotBlank()) { "缺少 common 规则脚本" }
        val array = root.optJSONArray("rules")
            ?: throw IllegalArgumentException("缺少 rules 数组")
        val rules = ArrayList<Rule>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index)
                ?: throw IllegalArgumentException("第 ${index + 1} 条规则不是对象")
            val rule = Rule.fromJson(obj)
                ?: throw IllegalArgumentException(
                    "第 ${index + 1} 条规则缺少 app/type/js/systemRuleName，或规则变量名非法",
                )
            rules.add(rule)
        }
        return Bundle(
            common = common,
            category = root.optString("category", ""),
            rules = rules,
            version = root.optInt("version", VERSION),
            date = root.optLong("date", 0L),
            source = root.optString("source", SOURCE_IMPORT).ifBlank { SOURCE_IMPORT },
        )
    }

    /** 读取 APK 内置资产，给“尚无 rule_bundle.json”的场景兜底。 */
    fun fromAssetJson(text: String): Bundle {
        val root = JSONObject(text)
        val common = root.optString("common")
        require(common.isNotBlank()) { "内置规则缺少 common 脚本" }
        val array = root.optJSONArray("rules") ?: JSONArray()
        val rules = ArrayList<Rule>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            Rule.fromJson(obj, fallbackSource = SOURCE_BUILTIN)?.let { rules.add(it) }
        }
        return Bundle(
            common = common,
            category = "",
            rules = rules,
            version = VERSION,
            date = 0L,
            source = SOURCE_BUILTIN,
        )
    }
}
