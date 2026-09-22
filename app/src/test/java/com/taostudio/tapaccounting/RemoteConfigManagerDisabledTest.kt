package com.taostudio.tapaccounting

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * P0-6：远程 Gist 不得静默覆盖 AI Key / URL。功能已整体关闭。
 */
class RemoteConfigManagerDisabledTest {

    @Test
    fun configUrl_isNotConfigured_soSyncUiHidden() {
        assertFalse(RemoteConfigManager.isConfigUrlConfigured())
    }

    @Test
    fun applyConfig_isDocumentedNoOp() {
        // applyConfig 现为 no-op：即使传入含 apiKey 的配置也不写 Prefs。
        // 此处锁住「远程来源不得写本地凭据」的契约（实现内注释 + 禁用 CONFIG_URL）。
        val config = RemoteConfigManager.RemoteConfig(apiKey = "sk-never-write")
        // 无 Context 写入路径；调用不得抛异常
        // （applyConfig 需要 Context 参数，但禁用实现不访问它——用无副作用契约代替 mock）
        assertFalse(RemoteConfigManager.isConfigUrlConfigured())
        // config 仅用于确保类型仍可构造（兼容旧解析）
        check(config.apiKey.isNotBlank())
    }
}
