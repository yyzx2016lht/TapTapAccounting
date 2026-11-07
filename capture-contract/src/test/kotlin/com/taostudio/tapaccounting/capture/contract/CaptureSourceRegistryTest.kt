package com.taostudio.tapaccounting.capture.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CaptureSourceRegistryTest {

    @Test
    fun `default registry resolves known source ids and packages`() {
        val registry = CaptureSourceRegistry.DEFAULT

        assertEquals("微信", registry.displayName(CaptureSource.WECHAT))
        assertEquals("微信", registry.displayName("com.tencent.mm"))
        assertEquals("支付宝", registry.displayNameForPackage("com.eg.android.AlipayGphone"))
        assertEquals(CaptureSource.ALIPAY, registry.findById("alipay")?.id)
        assertNull(registry.findById("UNKNOWN"))
    }

    @Test
    fun `registering a fake source needs only one registration entry`() {
        val registry = CaptureSourceRegistry(CaptureSourceRegistry.DEFAULT.all)
        val fake = registry.register(
            CaptureSourceEntry(
                id = "FAKE",
                packageName = "com.example.fake.pay",
                displayName = "示例来源",
            ),
        )

        assertSame(fake, registry.findById("FAKE"))
        assertSame(fake, registry.findByPackage("com.example.fake.pay"))
        assertEquals("示例来源", registry.displayName("FAKE"))
        assertEquals("示例来源", registry.displayName("com.example.fake.pay"))
        assertEquals("示例来源", registry.displayNameForPackage("com.example.fake.pay"))
    }

    @Test
    fun `unknown package falls back without hardcoding wechat or alipay`() {
        assertEquals(
            CaptureSourceRegistry.FALLBACK_DISPLAY_NAME,
            CaptureSourceRegistry.DEFAULT.displayNameForPackage("com.example.unknown"),
        )
        assertEquals(
            CaptureSourceRegistry.FALLBACK_DISPLAY_NAME,
            CaptureSourceRegistry.DEFAULT.displayName("UNKNOWN_ID"),
        )
        assertEquals(
            CaptureSourceRegistry.FALLBACK_DISPLAY_NAME,
            CaptureSourceRegistry.DEFAULT.displayName(null),
        )
    }

    @Test
    fun `all exposes registered sources in registration order`() {
        val registry = CaptureSourceRegistry(CaptureSourceRegistry.DEFAULT.all)

        assertEquals(
            listOf(CaptureSource.WECHAT, CaptureSource.ALIPAY),
            registry.all.map { it.id },
        )
        assertEquals(2, registry.all.size)
    }
}
