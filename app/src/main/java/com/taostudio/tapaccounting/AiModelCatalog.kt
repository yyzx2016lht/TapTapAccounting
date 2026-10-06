package com.taostudio.tapaccounting

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * 单个模型的输入能力元数据（来自 models.dev）。
 */
data class AiModelMeta(
    val id: String,
    val name: String,
    val inputs: Set<String>,
    val reasoning: Boolean,
    val contextWindow: Int
) {
    val supportsImage: Boolean get() = inputs.contains(MODALITY_IMAGE)

    companion object {
        const val MODALITY_IMAGE = "image"
    }
}

/**
 * 模型输入能力目录。
 *
 * 数据来源为 models.dev（运行时拉取，仅裁剪保留本 App 已支持的提供商）。
 * 实时 `/v1/models` 决定「某提供商有哪些模型」，本目录决定「某模型支持哪些输入模态」。
 *
 * 目录不可用（未拉取 / 拉取失败 / 模型 ID 对不上）时调用方一律回退旧行为：
 * 视觉列表不过滤，由 provider 级能力标记兜底。
 */
object AiModelCatalog {
    const val OVERRIDE_IMAGE = "image"
    const val OVERRIDE_TEXT = "text"

    private const val CATALOG_URL = "https://models.dev/api.json"
    private const val HTTP_CONNECT_TIMEOUT_SECONDS = 30L
    private const val HTTP_READ_TIMEOUT_SECONDS = 90L
    private const val TTL_MS = 24L * 60 * 60 * 1000

    @Volatile
    private var memoryCache: Pair<String, Map<String, Map<String, AiModelMeta>>>? = null

    /** 本地目录是否仍在有效期内（避免每次测试连接都重复下载）。 */
    fun isFresh(ctx: Context): Boolean {
        val fetchedAt = Prefs.getAiModelCatalogFetchedAt(ctx)
        return fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < TTL_MS
    }

    /**
     * 从 models.dev 拉取并裁剪目录。失败不抛出，返回 false（调用方静默降级）。
     */
    suspend fun refreshFromNetwork(
        ctx: Context,
        providerIds: Set<String> = AiProviderRegistry.catalogProviderIds()
    ): Boolean = withContext(Dispatchers.IO) {
        if (providerIds.isEmpty()) return@withContext false
        // 目录仍在有效期内且已覆盖目标提供商时跳过下载（失败时 fetchedAt 不会更新，会自动重试）。
        if (isFresh(ctx) && load(ctx).keys.containsAll(providerIds)) return@withContext true
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(HTTP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(HTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(CATALOG_URL)
                .header("Accept", "application/json")
                .header("User-Agent", AI_USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("empty body")
                val catalog = parseCatalog(body.string(), providerIds)
                if (catalog.isEmpty()) throw IllegalStateException("empty catalog")
                Prefs.setAiModelCatalog(ctx, encodeCatalog(catalog))
                Prefs.setAiModelCatalogFetchedAt(ctx, System.currentTimeMillis())
                memoryCache = null
                Logger.d(ctx, "AiModelCatalog", "refresh ok providers=${catalog.keys.joinToString(",")}")
            }
            true
        }.getOrElse {
            Logger.d(ctx, "AiModelCatalog", "refresh failed errType=${it.javaClass.simpleName}")
            false
        }
    }

    /** 本地目录对该提供商是否可用：必须至少有一个实时模型 ID 能在目录里对上。 */
    fun isProviderCatalogUsable(ctx: Context, providerId: String?, liveModels: List<String>): Boolean {
        val catalog = providerId?.let { load(ctx)[it] } ?: return false
        return liveModels.any { it in catalog }
    }

    /**
     * 模型是否支持图片输入。
     * 返回 null 表示目录不可用（未拉取 / 该提供商无数据），调用方应回退旧行为；
     * 目录可用时：手动标记 > 预设默认视觉模型（可信）> 目录数据 > 未收录按纯文本。
     */
    fun supportsImageInput(
        ctx: Context,
        providerId: String?,
        modelId: String,
        trustedModel: String? = null
    ): Boolean? {
        if (providerId.isNullOrBlank()) return null
        val catalog = load(ctx)[providerId] ?: return null
        if (catalog.isEmpty()) return null
        val override = Prefs.getAiModelCapabilityOverride(ctx, providerId, modelId)
        return resolveSupportsImage(
            modelId = modelId,
            trustedModel = trustedModel,
            override = override,
            meta = catalog[modelId]
        )
    }

    /**
     * 视觉槽位可选的模型列表。
     * 返回 null 表示目录不可用，调用方回退为不过滤（保持旧行为）。
     */
    fun visionModelsForPicker(
        ctx: Context,
        providerId: String?,
        liveModels: List<String>,
        trustedDefault: String?
    ): List<String>? {
        if (providerId.isNullOrBlank() || liveModels.isEmpty()) return null
        if (!isProviderCatalogUsable(ctx, providerId, liveModels)) return null
        return liveModels.filter { model ->
            model == trustedDefault || supportsImageInput(ctx, providerId, model) == true
        }
    }

    /** 模型列表行尾能力角标；null 表示目录不可用（不显示角标）。true=支持图片，false=纯文本。 */
    fun imageCapabilityBadge(
        ctx: Context,
        providerId: String?,
        modelId: String,
        trustedDefault: String? = null
    ): Boolean? {
        if (providerId.isNullOrBlank()) return null
        val catalog = load(ctx)[providerId] ?: return null
        if (catalog.isEmpty()) return null
        return supportsImageInput(ctx, providerId, modelId, trustedDefault) == true
    }

    // ───────────────────────── 内部实现 ─────────────────────────

    private fun load(ctx: Context): Map<String, Map<String, AiModelMeta>> {
        val raw = Prefs.getAiModelCatalog(ctx)
        memoryCache?.let { (cachedRaw, catalog) -> if (cachedRaw == raw) return catalog }
        val catalog = decodeCatalog(raw)
        memoryCache = raw to catalog
        return catalog
    }

    internal fun resolveSupportsImage(
        modelId: String,
        trustedModel: String?,
        override: String?,
        meta: AiModelMeta?
    ): Boolean = when {
        override != null -> override == OVERRIDE_IMAGE
        modelId == trustedModel -> true
        meta != null -> meta.supportsImage
        else -> false
    }

    internal fun encodeCatalog(catalog: Map<String, Map<String, AiModelMeta>>): String {
        val root = JSONObject()
        catalog.forEach { (providerId, models) ->
            val providerObj = JSONObject()
            models.forEach { (modelId, meta) ->
                providerObj.put(modelId, JSONObject().apply {
                    put("n", meta.name)
                    put("i", JSONArray(meta.inputs.toList()))
                    put("r", meta.reasoning)
                    put("c", meta.contextWindow)
                })
            }
            root.put(providerId, providerObj)
        }
        return root.toString()
    }

    internal fun decodeCatalog(raw: String?): Map<String, Map<String, AiModelMeta>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val out = LinkedHashMap<String, Map<String, AiModelMeta>>()
            val providerKeys = root.keys()
            while (providerKeys.hasNext()) {
                val providerId = providerKeys.next()
                val providerObj = root.optJSONObject(providerId) ?: continue
                val models = LinkedHashMap<String, AiModelMeta>()
                val modelKeys = providerObj.keys()
                while (modelKeys.hasNext()) {
                    val modelId = modelKeys.next()
                    val obj = providerObj.optJSONObject(modelId) ?: continue
                    val inputs = mutableSetOf<String>()
                    obj.optJSONArray("i")?.let { arr ->
                        for (index in 0 until arr.length()) {
                            arr.optString(index).takeIf { it.isNotBlank() }?.let(inputs::add)
                        }
                    }
                    models[modelId] = AiModelMeta(
                        id = modelId,
                        name = obj.optString("n", modelId),
                        inputs = inputs,
                        reasoning = obj.optBoolean("r", false),
                        contextWindow = obj.optInt("c", 0)
                    )
                }
                out[providerId] = models
            }
            out
        }.getOrDefault(emptyMap())
    }

    /**
     * 解析 models.dev 全量 JSON，只保留 [keepProviders]。
     * 使用流式读取，跳过无关提供商，避免在移动端构建整棵 JSON 树。
     */
    internal fun parseCatalog(raw: String, keepProviders: Set<String>): Map<String, Map<String, AiModelMeta>> {
        if (raw.isBlank() || keepProviders.isEmpty()) return emptyMap()
        return runCatching {
            val out = LinkedHashMap<String, Map<String, AiModelMeta>>()
            JsonReader(StringReader(raw)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val providerId = reader.nextName()
                    if (providerId in keepProviders) {
                        val element = JsonParser.parseReader(reader)
                        if (element is JsonObject) {
                            val models = parseProviderModels(element)
                            if (models.isNotEmpty()) out[providerId] = models
                        }
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
            out
        }.getOrDefault(emptyMap())
    }

    internal fun parseProviderModels(provider: JsonObject): Map<String, AiModelMeta> {
        val models = provider.getAsJsonObject("models") ?: return emptyMap()
        val out = LinkedHashMap<String, AiModelMeta>()
        models.entrySet().forEach { (id, element) ->
            val obj = element as? JsonObject ?: return@forEach
            val inputs = obj.getAsJsonObject("modalities")
                ?.getAsJsonArray("input")
                ?.mapNotNull { (it as? JsonPrimitive)?.asString }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
            val reasoning = (obj.get("reasoning") as? JsonPrimitive)?.asBoolean == true
            val context = (obj.getAsJsonObject("limit")?.get("context") as? JsonPrimitive)?.asInt ?: 0
            val name = (obj.get("name") as? JsonPrimitive)?.asString.orEmpty().ifBlank { id }
            out[id] = AiModelMeta(
                id = id,
                name = name,
                inputs = inputs,
                reasoning = reasoning,
                contextWindow = context
            )
        }
        return out
    }
}
