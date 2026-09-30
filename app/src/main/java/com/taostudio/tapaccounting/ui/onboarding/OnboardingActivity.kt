package com.taostudio.tapaccounting.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.taostudio.tapaccounting.GesturePermissionGuideActivity
import com.taostudio.tapaccounting.MainActivity
import com.taostudio.tapaccounting.OverlayService
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.ui.import.ImportOnboardingActivity
import com.taostudio.tapaccounting.tap.TapModel

/**
 * 首次启动引导页。
 *
 * 设计取舍：
 * - 4 页 ViewPager2，允许左右滑动回看，不强制线性前进；
 * - 「跳过」在任意页可用，等于放弃引导（会写入完成标记，不再打扰）；
 * - 手势开关先记在内存里，退出引导时统一落盘并拉起服务，
 *   这样用户中途返回不会留下"开关是开的但权限没配"的半残状态。
 */
class OnboardingActivity : AppCompatActivity() {

    private companion object {
        private const val PAGE_WELCOME = 0
        private const val PAGE_GESTURE = 1
        private const val PAGE_PREPARE = 2
        private const val PAGE_IMPORT = 3
        private const val PAGE_COUNT = 4
    }

    private lateinit var pager: ViewPager2
    private lateinit var indicatorContainer: LinearLayout
    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnSkip: TextView
    private lateinit var btnSecondary: TextView
    private lateinit var tvStep: TextView

    private val gestureFragment = OnboardingGestureFragment()
    private val prepareFragment = OnboardingPrepareFragment()
    private val importFragment = OnboardingImportFragment()
    private val welcomeFragment = OnboardingWelcomeFragment()

    /** 避免 ViewPager2 的 onPageSelected 在初始化阶段回调干扰按钮文案。 */
    private var currentPage = PAGE_WELCOME
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pager = findViewById(R.id.pager_onboarding)
        indicatorContainer = findViewById(R.id.indicator_container)
        btnPrimary = findViewById(R.id.btn_primary)
        btnSkip = findViewById(R.id.btn_skip)
        btnSecondary = findViewById(R.id.btn_secondary)
        tvStep = findViewById(R.id.tv_step)

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = PAGE_COUNT
            override fun createFragment(position: Int): Fragment = when (position) {
                PAGE_WELCOME -> welcomeFragment
                PAGE_GESTURE -> gestureFragment
                PAGE_PREPARE -> prepareFragment
                else -> importFragment
            }
        }
        // 手势页需要被"返回"时复用同一个实例，关掉离屏销毁
        pager.offscreenPageLimit = PAGE_COUNT - 1

        buildIndicators()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                renderChrome()
                if (position == PAGE_PREPARE && prepareFragment.isAdded) {
                    prepareFragment.render()
                }
            }
        })

        btnSkip.setOnClickListener { completeOnboarding(launchImport = false) }
        btnSecondary.setOnClickListener { completeOnboarding(launchImport = false) }
        btnPrimary.setOnClickListener { onPrimaryClicked() }
        prepareFragmentListener()

        renderChrome()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置/权限引导页返回后刷新准备项
        if (currentPage == PAGE_PREPARE && prepareFragment.isAdded) {
            prepareFragment.render()
        }
    }

    private fun prepareFragmentListener() {
        // 准备项页的两个按钮在 fragment 布局里，这里通过 view 查找绑定
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: androidx.fragment.app.FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?
                ) {
                    if (f !is OnboardingPrepareFragment) return
                    v.findViewById<View>(R.id.btn_ob_prepare)?.setOnClickListener {
                        startActivity(
                            Intent(this@OnboardingActivity, GesturePermissionGuideActivity::class.java)
                        )
                    }
                    v.findViewById<View>(R.id.btn_ob_recheck)?.setOnClickListener {
                        f.render()
                    }
                }
            },
            false
        )
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: androidx.fragment.app.FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?
                ) {
                    if (f !is OnboardingImportFragment) return
                    v.findViewById<View>(R.id.btn_ob_import)?.setOnClickListener {
                        Prefs.setOnboardingImportOffered(this@OnboardingActivity)
                        completeOnboarding(launchImport = true)
                    }
                }
            },
            false
        )
    }

    private fun onPrimaryClicked() {
        when (currentPage) {
            PAGE_IMPORT -> completeOnboarding(launchImport = false)
            PAGE_PREPARE -> {
                // 准备项页的主按钮语义是"进入应用"
                completeOnboarding(launchImport = false)
            }
            else -> pager.setCurrentItem(currentPage + 1, true)
        }
    }

    private fun buildIndicators() {
        val dp = resources.displayMetrics.density
        val size = (7 * dp).toInt()
        val gap = (6 * dp).toInt()
        indicatorContainer.removeAllViews()
        repeat(PAGE_COUNT) { index ->
            val dot = ImageView(this).apply {
                setImageResource(R.drawable.bg_onboarding_dot)
                alpha = if (index == 0) 1f else 0.25f
            }
            indicatorContainer.addView(dot, LinearLayout.LayoutParams(size, size).apply {
                marginStart = if (index == 0) 0 else gap
                marginEnd = 0
            })
        }
    }

    private fun renderChrome() {
        val dotCount = indicatorContainer.childCount
        for (i in 0 until dotCount) {
            indicatorContainer.getChildAt(i).alpha = if (i == currentPage) 1f else 0.25f
        }
        tvStep.text = getString(R.string.ob_step_of, currentPage + 1, PAGE_COUNT)

        val isLast = currentPage == PAGE_IMPORT || currentPage == PAGE_PREPARE
        btnPrimary.text = if (isLast) {
            getString(R.string.ob_enter_app)
        } else {
            getString(R.string.ob_next)
        }

        // 第 1 页没有"稍后再说"，它跟"跳过"重复
        btnSecondary.visibility = if (currentPage == PAGE_PREPARE) View.VISIBLE else View.GONE
        btnSkip.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
    }

    /**
     * 提交手势选择并结束引导。
     *
     * 手势开关只在真正退出引导时才落盘 —— 这样"开关是开的但权限还没配"的
     * 中间态不会跨会话留存（用户中途杀死进程等于没改过）。
     */
    private fun commitGestureSelection() {
        val flip = gestureFragment.flipSelected
        val tap = gestureFragment.tapSelected
        if (!flip && !tap) return
        Prefs.setFlipEnabled(this, flip)
        Prefs.setDoubleTapEnabled(this, tap)
        if (tap) {
            // 敲击模型：没挑过机型时给个推荐值，否则检测层拿不到模型
            if (Prefs.getTapModel(this).isEmpty()) {
                Prefs.setTapModel(this, TapModel.recommend(this).path)
            }
            // 关键：补上敲击动作默认值，否则敲了没反应
            Prefs.ensureTapActionsConfigured(this)
        }
        if (Prefs.isFlipEnabled(this) || Prefs.isDoubleTapEnabled(this)) {
            OverlayService.startCompat(this, Intent(this, OverlayService::class.java))
        }
    }

    private fun completeOnboarding(launchImport: Boolean) {
        if (finishing) return
        finishing = true
        commitGestureSelection()
        Prefs.setOnboardingCompleted(this, true)
        Prefs.setHomeOnboardingSeen(this)
        Prefs.setSensitivityOnboardingSeen(this)

        if (launchImport) {
            startActivity(
                Intent(this, ImportOnboardingActivity::class.java)
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        finish()
    }

    override fun onBackPressed() {
        if (currentPage > PAGE_WELCOME) {
            pager.setCurrentItem(currentPage - 1, true)
            return
        }
        // 第一页再按返回：等同跳过，避免用户被引导页困住
        completeOnboarding(launchImport = false)
    }
}
