package com.taostudio.tapaccounting.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.taostudio.tapaccounting.R

/**
 * 引导第 2 页：开启快捷记账。
 *
 * 这里只负责把开关读写到 [com.taostudio.tapaccounting.Prefs]，
 * 「开启后拉起 OverlayService」由宿主 [OnboardingActivity] 统一处理——
 * 因为服务启动需要 Activity 上下文，且失败时要给权限提示。
 */
class OnboardingGestureFragment : Fragment() {

    private lateinit var cardFlip: MaterialCardView
    private lateinit var cardTap: MaterialCardView
    private lateinit var switchFlip: SwitchMaterial
    private lateinit var switchTap: SwitchMaterial

    /** 用户在本页做过的改动，退出引导时由宿主统一提交（避免中途返回留下半开状态）。 */
    var flipSelected: Boolean = false
        private set
    var tapSelected: Boolean = false
        private set

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_gesture, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cardFlip = view.findViewById(R.id.card_ob_flip)
        cardTap = view.findViewById(R.id.card_ob_tap)
        switchFlip = view.findViewById(R.id.switch_ob_flip)
        switchTap = view.findViewById(R.id.switch_ob_tap)

        // 整行可点，避免只有开关本体能点（小控件，误触率高）
        cardFlip.setOnClickListener { switchFlip.toggle() }
        cardTap.setOnClickListener { switchTap.toggle() }

        switchFlip.setOnCheckedChangeListener { _, checked -> flipSelected = checked }
        switchTap.setOnCheckedChangeListener { _, checked -> tapSelected = checked }
    }

    /** 回到本页（如从准备项返回）时刷新选中态。 */
    fun syncFromSelection() {
        if (!isAdded) return
        if (switchFlip.isChecked != flipSelected) switchFlip.isChecked = flipSelected
        if (switchTap.isChecked != tapSelected) switchTap.isChecked = tapSelected
    }
}
