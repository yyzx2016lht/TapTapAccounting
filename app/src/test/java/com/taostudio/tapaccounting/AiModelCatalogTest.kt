package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelCatalogTest {

    private val sampleCatalog = """
        {
          "opencode-go": {
            "name": "OpenCode Go",
            "models": {
              "deepseek-v4.1-flash": {
                "name": "DeepSeek V4.1 Flash",
                "modalities": { "input": ["text", "image"], "output": ["text"] },
                "reasoning": true,
                "limit": { "context": 1000000 }
              },
              "glm-5.3": {
                "name": "GLM-5.3",
                "modalities": { "input": ["text"], "output": ["text"] },
                "reasoning": true,
                "limit": { "context": 1000000 }
              }
            }
          },
          "deepseek": {
            "models": {
              "deepseek-v4-flash": {
                "modalities": { "input": ["text", "image"] },
                "limit": { "context": 1000000 }
              }
            }
          },
          "unrelated": {
            "models": {
              "whatever": { "modalities": { "input": ["text", "image"] } }
            }
          }
        }
    """.trimIndent()

    @Test
    fun parseCatalogKeepsOnlyRequestedProviders() {
        val catalog = AiModelCatalog.parseCatalog(sampleCatalog, setOf("opencode-go", "deepseek"))
        assertEquals(setOf("opencode-go", "deepseek"), catalog.keys)
        assertFalse(catalog.containsKey("unrelated"))
    }

    @Test
    fun parseCatalogExtractsModalitiesReasoningAndContext() {
        val catalog = AiModelCatalog.parseCatalog(sampleCatalog, setOf("opencode-go"))
        val models = catalog.getValue("opencode-go")

        val deepseek = models.getValue("deepseek-v4.1-flash")
        assertEquals("DeepSeek V4.1 Flash", deepseek.name)
        assertEquals(setOf("text", "image"), deepseek.inputs)
        assertTrue(deepseek.supportsImage)
        assertTrue(deepseek.reasoning)
        assertEquals(1_000_000, deepseek.contextWindow)

        val glm = models.getValue("glm-5.3")
        assertEquals(setOf("text"), glm.inputs)
        assertFalse(glm.supportsImage)
    }

    @Test
    fun parseCatalogSkipsMalformedModels() {
        val raw = """
            {
              "prov": {
                "models": {
                  "broken-name": { "modalities": { "input": ["text"] } },
                  "not-an-object": 42
                }
              }
            }
        """.trimIndent()
        val catalog = AiModelCatalog.parseCatalog(raw, setOf("prov"))
        val models = catalog.getValue("prov")
        assertEquals(setOf("broken-name"), models.keys)
        assertEquals("broken-name", models.getValue("broken-name").name)
    }

    @Test
    fun parseCatalogReturnsEmptyForBlankInput() {
        assertTrue(AiModelCatalog.parseCatalog("", setOf("opencode-go")).isEmpty())
        assertTrue(AiModelCatalog.parseCatalog(sampleCatalog, emptySet()).isEmpty())
    }

    @Test
    fun encodeDecodeRoundTripPreservesModelMetadata() {
        val catalog = AiModelCatalog.parseCatalog(sampleCatalog, setOf("opencode-go", "deepseek"))
        val decoded = AiModelCatalog.decodeCatalog(AiModelCatalog.encodeCatalog(catalog))
        assertEquals(catalog, decoded)
    }

    @Test
    fun decodeCatalogToleratesGarbage() {
        assertTrue(AiModelCatalog.decodeCatalog("not json").isEmpty())
        assertTrue(AiModelCatalog.decodeCatalog(null).isEmpty())
    }

    @Test
    fun resolveSupportsImagePrefersOverrideThenTrustedThenCatalog() {
        val textOnly = AiModelMeta("glm-5.3", "GLM-5.3", setOf("text"), true, 1_000_000)
        val image = AiModelMeta("deepseek-v4.1-flash", "DeepSeek", setOf("text", "image"), true, 1_000_000)

        // 手动标记优先于目录
        assertTrue(
            AiModelCatalog.resolveSupportsImage("glm-5.3", null, "image", textOnly)
        )
        assertFalse(
            AiModelCatalog.resolveSupportsImage("deepseek-v4.1-flash", null, "text", image)
        )
        // 无可信默认时按目录
        assertTrue(AiModelCatalog.resolveSupportsImage("deepseek-v4.1-flash", null, null, image))
        assertFalse(AiModelCatalog.resolveSupportsImage("glm-5.3", null, null, textOnly))
        // 预设推荐模型受信任
        assertTrue(AiModelCatalog.resolveSupportsImage("kimi-k2.5", "kimi-k2.5", null, null))
        // 目录里查不到的模型按纯文本处理
        assertFalse(AiModelCatalog.resolveSupportsImage("brand-new-model", null, null, null))
    }

    @Test
    fun opencodeGoPresetUsesZenBaseUrlAndCatalogId() {
        val preset = AiProviderRegistry.presetFor(AiProviderRegistry.PROVIDER_OPENCODE_GO)
        assertEquals("https://opencode.ai/zen/go", preset?.baseUrl)
        assertEquals("opencode-go", preset?.catalogProviderId)
        assertTrue(preset?.supportsVision == true)
    }

    @Test
    fun deepseekPresetNowSupportsVision() {
        val preset = AiProviderRegistry.presetFor(AiProviderRegistry.PROVIDER_DEEPSEEK)
        assertEquals("deepseek", preset?.catalogProviderId)
        assertTrue(preset?.supportsVision == true)
        assertEquals("deepseek-v4-flash", preset?.defaultVisionModel)
    }

    @Test
    fun allBuiltInPresetsHaveCatalogMapping() {
        assertEquals(
            AiProviderRegistry.allPresets().size,
            AiProviderRegistry.allPresets().count { !it.catalogProviderId.isNullOrBlank() }
        )
    }
}
