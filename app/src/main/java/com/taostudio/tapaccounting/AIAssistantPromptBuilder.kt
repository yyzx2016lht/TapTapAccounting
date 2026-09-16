package com.taostudio.tapaccounting

import android.content.Context

private const val ASSISTANT_REPLY_RULES =
    "直接对用户说完整的人话；不要输出场景标签、JSON、英文状态词（如 BILL_SAVED / NO_BILL）或内部指令。"

internal fun buildAssistantStyleInstruction(
    ctx: Context,
    defaultCustomReplyStyleGuide: String
): String {
    val customPrompt = shortenForModel(
        Prefs.getAiChatReplyStyleCustomPrompt(ctx).trim(),
        800,
        preserveTail = false
    )
    return when (Prefs.getAiChatReplyStyle(ctx)) {
        "off" ->
            "回复风格：平实自然，2-4 句，语气中性清楚，少用或不用 emoji。"
        "gentle" ->
            "回复风格：温柔轻声，句子偏短，先接住情绪再回答；2-4 句；emoji 少用。"
        "concise" ->
            "回复风格：简洁克制，1-2 句说重点，不铺垫，不用 emoji。"
        "cute" ->
            "回复风格：可爱俏皮一点，可带少量颜文字；2-4 句，别太长太吵。"
        "playful" ->
            "回复风格：活泼有聊天感，可先碎碎念一句反应，但仍要回答正题；emoji 适量。"
        "custom" ->
            if (customPrompt.isNotBlank()) {
                "回复风格（用户自定义，高优先）：$customPrompt"
            } else {
                defaultCustomReplyStyleGuide
            }
        else ->
            "回复风格：平实清楚，像正常朋友聊天；2-4 句；emoji 偶尔可用，别刻意卖萌。"
    }
}

internal fun buildAssistantSystemPrompt(
    ctx: Context,
    defaultCustomReplyStyleGuide: String
): String {
    val styleInstruction = buildAssistantStyleInstruction(ctx, defaultCustomReplyStyleGuide)
    val aiName = Prefs.getAiChatName(ctx).trim().ifBlank { "小记" }
    val aiIdentity = shortenForModel(Prefs.getAiChatIdentity(ctx).trim(), 160, preserveTail = false)
    val userName = Prefs.getUserChatName(ctx).trim().ifBlank { "我" }
    val userProfile = shortenForModel(Prefs.getUserProfileDesc(ctx).trim(), 200, preserveTail = false)
    return buildString {
        appendLine(AIPrompts.CHAT_ASSISTANT_PROMPT_DEFAULT.trim())
        appendLine()
        appendLine("【身份设定】")
        appendLine("你的名字是「$aiName」。用户称呼是「$userName」。")
        if (aiIdentity.isNotBlank()) {
            appendLine("你的身份简介：$aiIdentity")
        }
        appendLine("当用户问“你是谁/你叫什么”时，优先使用这个名字自我介绍，不要说自己不知道名字。")
        if (userProfile.isNotBlank() && userProfile != "点击设置名字和头像") {
            appendLine("用户档案参考：$userProfile")
        }
        appendLine()
        appendLine("【回复风格】")
        appendLine(styleInstruction)
        appendLine(ASSISTANT_REPLY_RULES)
    }.trim()
}

internal fun buildOpenConversationSystemPrompt(ctx: Context): String {
    val aiName = Prefs.getAiChatName(ctx).trim()
    val userName = Prefs.getUserChatName(ctx).trim().ifBlank { "我" }
    val userProfile = shortenForModel(Prefs.getUserProfileDesc(ctx).trim(), 200, preserveTail = false)
    val customPrompt = shortenForModel(
        Prefs.getAiChatReplyStyleCustomPrompt(ctx).trim(),
        800,
        preserveTail = false
    )
    val useCustomStyle = Prefs.getAiChatReplyStyle(ctx) == "custom" && customPrompt.isNotBlank()
    return buildString {
        appendLine(AIPrompts.CHAT_OPEN_CONVERSATION_PROMPT_DEFAULT.trim())
        if (aiName.isNotBlank()) {
            appendLine()
            appendLine("【称呼】用户可能叫你「$aiName」，用户自称「$userName」。")
            appendLine("被问「你是谁」时，用这个名字自我介绍即可，不必强调自己是记账助手。")
        }
        if (userProfile.isNotBlank() && userProfile != "点击设置名字和头像") {
            appendLine("用户档案（参考）：$userProfile")
        }
        if (useCustomStyle) {
            appendLine()
            appendLine("【回复风格偏好】$customPrompt")
        }
        appendLine()
        appendLine(ASSISTANT_REPLY_RULES)
    }.trim()
}

internal fun buildAccountingCasualChatSystemPrompt(
    ctx: Context,
    defaultCustomReplyStyleGuide: String
): String {
    val base = buildAssistantSystemPrompt(ctx, defaultCustomReplyStyleGuide)
    return buildString {
        appendLine(base)
        appendLine()
        appendLine("【当前场景】你在记账助手里陪用户闲聊。请正常、完整地回答对方的问题。")
        appendLine("语气亲切，但更偏记账助手：简洁务实，少废话；可偶尔轻轻提一句「有记账需求随时说」。")
        appendLine("不要拒绝聊天，不要要求用户必须先切换模式才能说话。")
        appendLine("你无法读取本地账单或统计数据，也无法真正写入账单。严禁假装已经记账成功、编造「记账成功/已记下/可乐 10 元」这类入账回执；若用户像在记账，请明确说需要走记账识别，并请对方再说一次或换个更明确的说法。")
        appendLine("用户询问历史账单、金额或排行时，明确说明无法代查，并引导其使用账单搜索或统计页；禁止猜测结果。")
    }.trim()
}

internal fun buildAccountingAssistantUserPrompt(
    userInput: String,
    billSummary: String,
    extractorReplyHint: String
): String {
    val scene = if (billSummary.isBlank()) "NO_BILL" else "BILL_SAVED"
    return buildString {
        appendLine("场景：$scene")
        appendLine("用户原话：$userInput")
        if (billSummary.isNotBlank()) appendLine("账单摘要：$billSummary")
        if (extractorReplyHint.isNotBlank()) appendLine("上游识别备注：$extractorReplyHint")
        append("请直接回复用户。")
    }
}
