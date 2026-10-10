package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Test

class AiModelAutoPickerTest {

    private fun pick(
        slot: AiModelAutoPicker.Slot,
        live: List<String>,
        preferred: String,
        imageCapable: ((String) -> Boolean?)? = null
    ): String = AiModelAutoPicker.pick(slot, live, preferred, imageCapable)

    // ── 文字槽 ──────────────────────────────────────────────

    @Test
    fun keepsPreferredWhenPresentInLiveList() {
        val live = listOf("mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.5-asr")
        assertEquals(
            "mimo-v2.6-flash",
            pick(AiModelAutoPicker.Slot.TEXT, live, "mimo-v2.6-flash")
        )
    }

    @Test
    fun upgradesToNewerSameFamilyWhenPreferredDisappears() {
        // 厂商下线 v2.6：列表里只剩 v2.7，预设首选应自动顶上。
        val live = listOf("mimo-v2.7-flash", "mimo-v2.7-pro", "mimo-v2.5-asr", "mimo-v2.5-tts")
        assertEquals(
            "mimo-v2.7-flash",
            pick(AiModelAutoPicker.Slot.TEXT, live, "mimo-v2.6-flash")
        )
    }

    @Test
    fun neverDowngradesWhenLiveCacheIsOlderThanPreferred() {
        // 过期缓存里只有旧一代：继续信任预设默认，不降级。
        val stale = listOf("mimo-v2.5", "mimo-v2.5-pro", "mimo-v2.5-asr")
        assertEquals(
            "mimo-v2.6-flash",
            pick(AiModelAutoPicker.Slot.TEXT, stale, "mimo-v2.6-flash")
        )
    }

    @Test
    fun replacesDeprecatedManualChoiceWithCheaperNewerTier() {
        // 老用户手选的 mimo-v2.5 已不在列表里：自动换到同定位更轻量的 v2.6-flash，而不是 pro。
        val live = listOf("mimo-v2.6-pro", "mimo-v2.6-flash", "mimo-v2.5-asr", "mimo-v2.5-tts")
        assertEquals(
            "mimo-v2.6-flash",
            pick(AiModelAutoPicker.Slot.TEXT, live, "mimo-v2.5")
        )
    }

    @Test
    fun keepsPreferredWhenOnlyNonChatModelsRemain() {
        val live = listOf("mimo-v2.5-asr", "mimo-v2.5-tts", "bge-m3", "bge-reranker-v2")
        assertEquals(
            "mimo-v2.6-flash",
            pick(AiModelAutoPicker.Slot.TEXT, live, "mimo-v2.6-flash")
        )
    }

    @Test
    fun upgradesAcrossTierWhenPreferredTierDisappears() {
        // 同代里首选定位消失（flash 下线、pro 还在）：换到同代的 pro，而不是守着已下线的型号。
        val live = listOf("mimo-v2.6-pro", "mimo-v2.5-asr")
        assertEquals(
            "mimo-v2.6-pro",
            pick(AiModelAutoPicker.Slot.TEXT, live, "mimo-v2.6-flash")
        )
    }

    @Test
    fun emptyLiveListKeepsPreferred() {
        assertEquals(
            "mimo-v2.6-flash",
            pick(AiModelAutoPicker.Slot.TEXT, emptyList(), "mimo-v2.6-flash")
        )
    }

    @Test
    fun blankPreferredPicksBestEligibleLiveModel() {
        val live = listOf("acme-3x", "acme-4-flash", "acme-4-pro", "acme-asr-9")
        assertEquals(
            "acme-4-flash",
            pick(AiModelAutoPicker.Slot.TEXT, live, "")
        )
    }

    // ── 图片槽 ──────────────────────────────────────────────

    @Test
    fun visionSlotPicksOnlyImageCapableModels() {
        val live = listOf("acme-text-9", "acme-vl-9", "acme-asr-9")
        val imageCapable: (String) -> Boolean? = { it.contains("-vl-") }
        assertEquals(
            "acme-vl-9",
            pick(AiModelAutoPicker.Slot.VISION, live, "acme-vl-8", imageCapable)
        )
    }

    @Test
    fun visionSlotKeepsPreferredWhenCapabilityUnknown() {
        val live = listOf("acme-vl-9")
        assertEquals(
            "acme-vl-8",
            pick(AiModelAutoPicker.Slot.VISION, live, "acme-vl-8") { null }
        )
    }

    @Test
    fun visionSlotKeepsPreferredWhenHigherVersionsLackImageSupport() {
        val live = listOf("acme-next-10")
        val imageCapable: (String) -> Boolean? = { false }
        assertEquals(
            "acme-vl-8",
            pick(AiModelAutoPicker.Slot.VISION, live, "acme-vl-8", imageCapable)
        )
    }

    // ── 语音槽 ──────────────────────────────────────────────

    @Test
    fun speechSlotUpgradesToNewerAsrModel() {
        val live = listOf("mimo-v2.6-asr", "mimo-v2.6-flash", "mimo-v2.5-tts")
        assertEquals(
            "mimo-v2.6-asr",
            pick(AiModelAutoPicker.Slot.SPEECH, live, "mimo-v2.5-asr")
        )
    }

    @Test
    fun speechSlotKeepsPreferredWhenNoAsrModelInLiveList() {
        val live = listOf("mimo-v2.6-flash", "mimo-v2.5-tts")
        assertEquals(
            "mimo-v2.5-asr",
            pick(AiModelAutoPicker.Slot.SPEECH, live, "mimo-v2.5-asr")
        )
    }

    // ── 其他提供商的通用性（自动选择不限于 MiMo）──────────

    @Test
    fun deepseekAutoUpgradesToNewerFlashTier() {
        val live = listOf("deepseek-v4.1-flash", "deepseek-v4.1-pro", "deepseek-v3.2")
        assertEquals(
            "deepseek-v4.1-flash",
            pick(AiModelAutoPicker.Slot.TEXT, live, "deepseek-v4-flash")
        )
    }

    @Test
    fun kimiAutoUpgradesAcrossMajorVersion() {
        val live = listOf("kimi-k3", "kimi-k2.6-thinking")
        assertEquals(
            "kimi-k3",
            pick(AiModelAutoPicker.Slot.TEXT, live, "kimi-k2.5")
        )
    }

    @Test
    fun qwenAutoUpgradesFlashTier() {
        val live = listOf("qwen3.6-flash", "qwen3.6-plus", "qwen3-asr-flash")
        assertEquals(
            "qwen3.6-flash",
            pick(AiModelAutoPicker.Slot.TEXT, live, "qwen3.5-flash")
        )
    }

    @Test
    fun siliconFlowKeepsSimilarSizeWhenUpgrading() {
        val live = listOf("Qwen/Qwen3.5-235B-A22B", "Qwen/Qwen3.5-14B", "deepseek-ai/DeepSeek-V3.2")
        assertEquals(
            "Qwen/Qwen3.5-14B",
            pick(AiModelAutoPicker.Slot.TEXT, live, "Qwen/Qwen3-14B")
        )
    }

    @Test
    fun opencodeGoAutoUpgradesFlashTier() {
        val live = listOf("deepseek-v4.2-flash", "deepseek-v4.2-pro", "glm-4.7")
        assertEquals(
            "deepseek-v4.2-flash",
            pick(AiModelAutoPicker.Slot.TEXT, live, "deepseek-v4.1-flash")
        )
    }

    @Test
    fun qwenVisionAutoUpgradesOnlyWhenImageCapable() {
        val live = listOf("qwen3.6-flash", "qwen3.6-text-only-72b")
        val imageCapable: (String) -> Boolean? = { it.contains("flash") }
        assertEquals(
            "qwen3.6-flash",
            pick(AiModelAutoPicker.Slot.VISION, live, "qwen3.5-flash", imageCapable)
        )
    }

    @Test
    fun qwenSpeechAutoUpgradesAsrModel() {
        val live = listOf("qwen3.5-asr-flash", "qwen3.5-flash")
        assertEquals(
            "qwen3.5-asr-flash",
            pick(AiModelAutoPicker.Slot.SPEECH, live, "qwen3-asr-flash")
        )
    }

    @Test
    fun blankPresetDefaultsStillResolveFromLiveList() {
        // 将来新增提供商即使不写默认值，也能从实时列表里选出一个。
        val live = listOf("any-v3-flash", "any-v3-pro", "any-asr-v1")
        assertEquals(
            "any-v3-flash",
            pick(AiModelAutoPicker.Slot.TEXT, live, "")
        )
    }

    // ── 解析工具 ────────────────────────────────────────────

    @Test
    fun familyPrefixIgnoresVersionAndTier() {
        assertEquals("mimo-v", AiModelAutoPicker.familyPrefix("mimo-v2.6-flash"))
        assertEquals("mimo-v", AiModelAutoPicker.familyPrefix("mimo-v2.5"))
        assertEquals("qwen/qwen", AiModelAutoPicker.familyPrefix("Qwen/Qwen3-14B"))
    }

    @Test
    fun versionOfParsesFirstNumericSegment() {
        assertEquals(2 to 6, AiModelAutoPicker.versionOf("mimo-v2.6-flash"))
        assertEquals(2 to 5, AiModelAutoPicker.versionOf("mimo-v2.5-asr"))
        assertEquals(3 to 0, AiModelAutoPicker.versionOf("Qwen/Qwen3-14B"))
        assertEquals(0 to 0, AiModelAutoPicker.versionOf("FunAudioLLM/SenseVoiceSmall"))
    }
}
