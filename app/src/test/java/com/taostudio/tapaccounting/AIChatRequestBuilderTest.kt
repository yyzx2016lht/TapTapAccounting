package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIChatRequestBuilderTest {

    @Test
    fun multiTurnRequestKeepsConversationOrder() {
        val request = buildMultiTurnChatRequest(
            model = "test-model",
            temperature = 0.7,
            systemPrompt = "system",
            historyTurns = listOf(
                ChatTurn("user", "first"),
                ChatTurn("assistant", "reply")
            ),
            userText = "second"
        )

        val messages = request.getAsJsonArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map {
            it.asJsonObject.get("role").asString
        })
        assertEquals("second", messages.last().asJsonObject.get("content").asString)
    }

    @Test
    fun kimiK25UsesCompatibleThinkingAndTemperature() {
        val request = buildTextChatRequest(
            model = "kimi-k2.5",
            temperature = 0.3,
            userText = "hello",
            enableThinking = false
        )

        val adapted = adaptChatRequestForProvider(AiProviderRegistry.PROVIDER_KIMI, request)

        assertFalse(adapted.has("enable_thinking"))
        assertEquals("disabled", adapted.getAsJsonObject("thinking").get("type").asString)
        assertEquals(0.3, adapted.get("temperature").asDouble, 0.0)
    }

    @Test
    fun deepSeekStreamRequestsUsageForCacheLogging() {
        val request = buildTextChatRequest(
            model = "deepseek-v4-flash",
            temperature = 0.3,
            userText = "hello",
            stream = true,
            enableThinking = false
        )

        val adapted = adaptChatRequestForProvider(AiProviderRegistry.PROVIDER_DEEPSEEK, request)

        assertEquals("disabled", adapted.getAsJsonObject("thinking").get("type").asString)
        assertTrue(adapted.getAsJsonObject("stream_options").get("include_usage").asBoolean)
    }

    @Test
    fun mimoAsrDoesNotReceiveTextModelThinkingOptions() {
        val request = buildTextChatRequest(
            model = "mimo-v2.5-asr",
            temperature = 0.1,
            userText = "transcribe",
            enableThinking = false
        )

        val adapted = adaptChatRequestForProvider(AiProviderRegistry.PROVIDER_MIMO, request)

        assertFalse(adapted.has("enable_thinking"))
        assertFalse(adapted.has("thinking"))
    }

    @Test
    fun qwenHybridModelReceivesExplicitThinkingOff() {
        val request = buildTextChatRequest(
            model = "qwen3.5-flash",
            temperature = 0.3,
            userText = "hello",
            enableThinking = false
        )

        val adapted = adaptChatRequestForProvider(AiProviderRegistry.PROVIDER_QWEN, request)

        assertFalse(adapted.get("enable_thinking").asBoolean)
    }

    @Test
    fun siliconFlowHybridModelTogglesThinking() {
        val off = adaptChatRequestForProvider(
            AiProviderRegistry.PROVIDER_SILICONFLOW,
            buildTextChatRequest(model = "Qwen/Qwen3-14B", temperature = 0.3, userText = "hi", enableThinking = false)
        )
        val on = adaptChatRequestForProvider(
            AiProviderRegistry.PROVIDER_SILICONFLOW,
            buildTextChatRequest(model = "Qwen/Qwen3-14B", temperature = 0.3, userText = "hi", enableThinking = true)
        )

        assertFalse(off.get("enable_thinking").asBoolean)
        assertTrue(on.get("enable_thinking").asBoolean)
    }

    @Test
    fun siliconFlowInstructModelDoesNotReceiveThinkingOff() {
        val adapted = adaptChatRequestForProvider(
            AiProviderRegistry.PROVIDER_SILICONFLOW,
            buildTextChatRequest(
                model = "Qwen/Qwen3-VL-30B-A3B-Instruct",
                temperature = 0.3,
                userText = "hi",
                enableThinking = false
            )
        )

        assertFalse(adapted.has("enable_thinking"))
    }
}
