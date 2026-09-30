package com.taostudio.tapaccounting.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.taostudio.tapaccounting.R

/** 引导第 1 页：品牌欢迎 + 核心卖点。无交互，纯展示。 */
class OnboardingWelcomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_welcome, container, false)
}
