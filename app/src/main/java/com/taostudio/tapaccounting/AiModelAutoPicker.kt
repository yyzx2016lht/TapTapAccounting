package com.taostudio.tapaccounting

/**
 * 基于实时模型列表（`GET /v1/models` 的缓存）的默认模型自动选择。
 *
 * 目标：厂商下线/更名模型时不必等 App 发版——只要实时列表里出现更新一代的
 * 同类模型，预设默认值就会被自动顶替；列表里没有更新的一代时始终保留预设
 * 默认值（不降级，避免用过期缓存误伤较新的预设）。
 *
 * 约束：
 * - 文字/图片槽排除语音、向量、重排、图像生成等非对话条目；
 * - 图片槽优先信任 [AiModelCatalog] 的图片输入标注（目录不可用时不冒险替换）；
 * - 语音槽只接受 ASR/转写类模型。
 */
object AiModelAutoPicker {

    enum class Slot { TEXT, VISION, SPEECH }

    /** 明显不是对话模型的条目特征（语音、向量、重排、图像生成）。 */
    private val NON_CHAT_HINTS = listOf(
        "asr", "tts", "whisper", "transcrib", "sensevoice", "speech",
        "embed", "bge", "rerank", "voiceclone", "voicedesign",
        "flux", "diffusion", "sdxl"
    )

    /** 语音槽只接受的转写类模型特征。 */
    private val SPEECH_HINTS = listOf("asr", "whisper", "transcrib", "sensevoice", "speech")

    private val VERSION_REGEX = Regex("(\\d+)(?:\\.(\\d+))?")
    private val TOKEN_SPLIT = Regex("[^a-z0-9]+")
    private val PURE_VERSION_TOKEN = Regex("^v?\\d+(\\.\\d+)?$")

    private val LIGHT_TIER_HINTS = listOf("flash", "mini", "turbo", "small", "air", "lite", "nano")
    private val HEAVY_TIER_HINTS = listOf("pro", "plus", "max", "ultra", "premium")

    /**
     * 返回该槽位应使用的模型。
     *
     * @param liveModels 实时模型列表（空列表表示未知，直接返回 [preferred]）
     * @param preferred  预设首选模型（通常来自 [AiProviderPreset] 或用户手选值）
     * @param imageCapable 图片输入能力探测，返回 null 表示目录不可用
     */
    fun pick(
        slot: Slot,
        liveModels: List<String>,
        preferred: String,
        imageCapable: ((String) -> Boolean?)? = null
    ): String {
        val preferredModel = preferred.trim()
        val live = liveModels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (live.isEmpty()) return preferredModel
        if (preferredModel.isNotEmpty() && preferredModel in live) return preferredModel

        val candidates = live.filter { eligibleForSlot(it, slot, imageCapable) }
        if (candidates.isEmpty()) return preferredModel
        if (preferredModel.isEmpty()) return bestOf(candidates, preferredModel)

        val prefix = familyPrefix(preferredModel)
        val family = if (prefix.isNotEmpty()) candidates.filter { familyPrefix(it) == prefix } else emptyList()
        val pool = family.ifEmpty { candidates }

        val preferredVersion = versionKey(preferredModel)
        val poolVersion = pool.maxOf { versionKey(it) }
        // 不降级：列表里只有比预设默认更旧的一代时，继续信任预设默认；
        // 同代但首选不在列表里（例如同版本换定位）时允许顶替。
        if (poolVersion < preferredVersion) return preferredModel

        return bestOf(pool.filter { versionKey(it) == poolVersion }, preferredModel)
    }

    private fun eligibleForSlot(model: String, slot: Slot, imageCapable: ((String) -> Boolean?)?): Boolean =
        when (slot) {
            Slot.SPEECH -> containsAny(model, SPEECH_HINTS)
            Slot.TEXT -> !containsAny(model, NON_CHAT_HINTS)
            Slot.VISION -> !containsAny(model, NON_CHAT_HINTS) && imageCapable?.invoke(model) == true
        }

    /** 同代内先保持与预设相同定位（flash↔flash、pro↔pro），再取与预设词形最接近者。 */
    private fun bestOf(candidates: List<String>, preferred: String): String =
        candidates.sortedWith(
            compareByDescending<String> { versionKey(it) }
                .thenBy { tierClass(it) != tierClass(preferred) }
                .thenByDescending { tokenOverlap(it, preferred) }
                .thenBy { tierClass(it) }
                .thenBy { it.length }
                .thenBy { it }
        ).first()

    /** 版本号前的族名，例如 `mimo-v2.6-flash` → `mimo-v`、`Qwen/Qwen3-14B` → `qwen/qwen`。 */
    internal fun familyPrefix(model: String): String =
        model.lowercase().takeWhile { !it.isDigit() }.trimEnd('-', '/', '.', '_', ' ')

    /** 取模型名里第一段数字作为版本，例如 `mimo-v2.6-flash` → (2, 6)。 */
    internal fun versionOf(model: String): Pair<Int, Int> {
        val match = VERSION_REGEX.find(model) ?: return 0 to 0
        val major = match.groupValues[1].toIntOrNull() ?: 0
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        return major to minor
    }

    /** 数值化版本键，用于比较与同代分组。 */
    private fun versionKey(model: String): Int {
        val (major, minor) = versionOf(model)
        return major * 10000 + minor
    }

    private fun tierClass(model: String): Int {
        val name = model.lowercase()
        return when {
            containsAny(name, LIGHT_TIER_HINTS) -> 0
            containsAny(name, HEAVY_TIER_HINTS) -> 2
            else -> 1
        }
    }

    private fun tokenOverlap(model: String, other: String): Int {
        val otherTokens = tokens(other)
        return tokens(model).count { it in otherTokens }
    }

    private fun tokens(model: String): Set<String> =
        model.lowercase().split(TOKEN_SPLIT)
            .filter { it.isNotEmpty() && !PURE_VERSION_TOKEN.matches(it) }
            .toSet()

    private fun containsAny(model: String, hints: List<String>): Boolean {
        val name = model.lowercase()
        return hints.any { it in name }
    }
}
