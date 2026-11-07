package com.taostudio.tapaccounting.capture.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataFilterTest {

    @Test
    fun matches_acceptsPaymentKeywords() {
        assertTrue(DataFilter.matches("微信支付凭证：你已向星巴克支付32.00元"))
        assertTrue(DataFilter.matches("支付宝：向张三转账50元"))
        assertTrue(DataFilter.matches("工商银行：您尾号1234于12:00消费支出128.50元"))
        assertTrue(DataFilter.matches("美团外卖：扣款成功23.5元"))
        assertTrue(DataFilter.matches("退款入账：20.00元已存入零钱"))
        assertTrue(DataFilter.matches("¥50.00"))
        assertTrue(DataFilter.matches("￥100.00"))
    }

    @Test
    fun matches_rejectsNonPaymentChatMessages() {
        assertFalse(DataFilter.matches("在干嘛呢？"))
        assertFalse(DataFilter.matches("今晚去哪里吃火锅？"))
        assertFalse(DataFilter.matches("明天早上九点开会，请准时参加"))
        assertFalse(DataFilter.matches("收到，谢谢"))
        assertFalse(DataFilter.matches("哈哈哈哈笑死我了"))
        assertFalse(DataFilter.matches("你的快递已到达中转站"))
        assertFalse(DataFilter.matches("验证码为654321，5分钟内有效"))
        assertFalse(DataFilter.matches(null))
        assertFalse(DataFilter.matches(""))
        assertFalse(DataFilter.matches("   "))
    }

    @Test
    fun isWechatNoticeAllowed_acceptsOfficialWechatPay() {
        assertTrue(DataFilter.isWechatNoticeAllowed("微信支付", "微信支付凭证"))
        assertTrue(DataFilter.isWechatNoticeAllowed("微信支付", "已支付35.00元"))
        assertTrue(DataFilter.isWechatNoticeAllowed("微信支付", "已存入零钱50.00元"))
        assertTrue(DataFilter.isWechatNoticeAllowed("服务通知", "微信支付凭证"))
        assertTrue(DataFilter.isWechatNoticeAllowed("张三", "你已收到一笔微信转账"))
    }

    @Test
    fun isWechatNoticeAllowed_rejectsFriendAndGroupChats() {
        assertFalse(DataFilter.isWechatNoticeAllowed("张三", "在吗？"))
        assertFalse(DataFilter.isWechatNoticeAllowed("李四", "[语音]"))
        assertFalse(DataFilter.isWechatNoticeAllowed("王五", "[图片]"))
        assertFalse(DataFilter.isWechatNoticeAllowed("家庭相亲相爱群", "妈妈: 吃饭了吗"))
        assertFalse(DataFilter.isWechatNoticeAllowed("工作群", "张总: 明天下发通知"))
        assertFalse(DataFilter.isWechatNoticeAllowed("腾讯新闻", "今日热点新闻汇总"))
        assertFalse(DataFilter.isWechatNoticeAllowed("订阅号消息", "xxx公众号更新了"))
    }
}
