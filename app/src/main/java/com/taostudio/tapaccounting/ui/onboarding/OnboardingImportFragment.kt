package com.taostudio.tapaccounting.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.taostudio.tapaccounting.R

/**
 * 引导第 4 页：导入历史账单（可选）。
 *
 * 真正的导入入口是 [com.taostudio.tapaccounting.ui.import.ImportOnboardingActivity]，
 * 由宿主启动；用户可能导入很久，所以本页只做入口，不阻塞引导完成。
 */
class OnboardingImportFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_import, container, false)
}
