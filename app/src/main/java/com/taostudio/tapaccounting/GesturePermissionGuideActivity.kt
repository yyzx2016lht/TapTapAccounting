package com.taostudio.tapaccounting

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

class GesturePermissionGuideActivity : AppCompatActivity() {
    private companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 7301
    }

    private lateinit var summaryText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var tryOverlayButton: MaterialButton

    private data class StatusStyle(
        val label: String,
        val textColor: Int,
        val bgColor: Int,
        val verified: Boolean
    )

    private data class GuideItem(
        val title: String,
        val desc: String,
        val status: StatusStyle,
        val actionText: String,
        val required: Boolean,
        val autoDetectable: Boolean,
        val onAction: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        renderGuideItems()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            renderGuideItems()
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        val header = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_left)
            setColorFilter(Color.parseColor("#333333"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = selectableBorderless()
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(8)
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.quick_accounting_ready)
            setTextColor(Color.parseColor("#333333"))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        root.addView(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        content.addView(CardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            useCompatPadding = false
            addView(LinearLayout(this@GesturePermissionGuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(TextView(this@GesturePermissionGuideActivity).apply {
                    text = getString(R.string.prepare_gesture_action)
                    setTextColor(Color.parseColor("#1A2744"))
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                summaryText = TextView(this@GesturePermissionGuideActivity).apply {
                    setTextColor(Color.parseColor("#6D7785"))
                    textSize = 12f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(6), 0, 0)
                }
                addView(summaryText)
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(12)
        })

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(buildOverlayPreviewCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(12)
        })

        content.addView(listContainer)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        return root
    }

    private fun renderGuideItems() {
        val items = buildGuideItems()
        val verifiedRequired = items.count { it.required && it.autoDetectable && it.status.verified }
        val requiredCount = items.count { it.required && it.autoDetectable }
        val missing = (requiredCount - verifiedRequired).coerceAtLeast(0)
        summaryText.text = if (missing == 0) {
            getString(R.string.prepared_count_ready_fmt, verifiedRequired, requiredCount)
        } else {
            getString(R.string.prepared_count_pending_fmt, missing)
        }
        val overlayReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        tryOverlayButton.text = getString(
            if (overlayReady) R.string.try_overlay else R.string.try_overlay_need_permission
        )

        listContainer.removeAllViews()
        items.forEachIndexed { index, item ->
            listContainer.addView(createGuideRow(item), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = if (index == items.lastIndex) 0 else dp(10)
            })
        }
    }

    private fun buildOverlayPreviewCard(): View {
        return CardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            addView(LinearLayout(this@GesturePermissionGuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(TextView(this@GesturePermissionGuideActivity).apply {
                    text = getString(R.string.overlay_preview_title)
                    setTextColor(Color.parseColor("#1A2744"))
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@GesturePermissionGuideActivity).apply {
                    text = getString(R.string.overlay_preview_desc)
                    setTextColor(Color.parseColor("#6D7785"))
                    textSize = 12f
                    setPadding(0, dp(4), 0, dp(12))
                })
                addView(FrameLayout(this@GesturePermissionGuideActivity).apply {
                    background = roundedBg(Color.parseColor("#EDEFFA"), dp(14).toFloat())
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    addView(LinearLayout(this@GesturePermissionGuideActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = roundedBg(Color.WHITE, dp(16).toFloat())
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        val amountRow = LinearLayout(this@GesturePermissionGuideActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        amountRow.addView(TextView(this@GesturePermissionGuideActivity).apply {
                            text = "0.00"
                            setTextColor(Color.parseColor("#1A2744"))
                            textSize = 28f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        amountRow.addView(TextView(this@GesturePermissionGuideActivity).apply {
                            text = getString(R.string.type_expense)
                            setTextColor(Color.parseColor("#5C6BC0"))
                            textSize = 12f
                            setPadding(dp(10), dp(4), dp(10), dp(4))
                            background = roundedBg(Color.parseColor("#EDEFFA"), dp(12).toFloat())
                        })
                        addView(amountRow)
                        addView(TextView(this@GesturePermissionGuideActivity).apply {
                            text = "餐饮  ·  微信"
                            setTextColor(Color.parseColor("#8A98AD"))
                            textSize = 13f
                            setPadding(0, dp(10), 0, 0)
                        })
                    })
                })
                tryOverlayButton = MaterialButton(this@GesturePermissionGuideActivity).apply {
                    textSize = 13f
                    cornerRadius = dp(10)
                    isAllCaps = false
                    insetTop = 0
                    insetBottom = 0
                    styleGuideButton(this, emphasize = true)
                    setOnClickListener { previewOverlay() }
                }
                addView(tryOverlayButton, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(40)
                ).apply { topMargin = dp(12) })
            })
        }
    }

    private fun previewOverlay() {
        val overlayReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (!overlayReady) {
            openOverlaySettings()
            return
        }
        OverlayService.startCompat(this, Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY
        })
    }

    private fun styleGuideButton(button: MaterialButton, emphasize: Boolean) {
        if (emphasize) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#5C6BC0"))
            button.strokeWidth = 0
            button.setTextColor(Color.WHITE)
        } else {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            button.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#C5CEF0"))
            button.strokeWidth = dp(1)
            button.setTextColor(Color.parseColor("#5C6BC0"))
        }
    }

    private fun buildGuideItems(): List<GuideItem> {
        val overlayReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val batteryReady = isIgnoringBatteryOptimizations()
        val backgroundRestricted = isBackgroundRestricted()
        val notificationReady = isNotificationPermissionReady()
        val backgroundReady = batteryReady && !backgroundRestricted

        return listOf(
            GuideItem(
                title = getString(R.string.overlay_permission_title),
                desc = getString(R.string.overlay_permission_desc),
                status = if (overlayReady) ready(getString(R.string.ready_label)) else actionNeeded(getString(R.string.pending_label)),
                actionText = if (overlayReady) getString(R.string.view_settings_label) else getString(R.string.go_enable),
                required = true,
                autoDetectable = true,
                onAction = ::openOverlaySettings
            ),
            GuideItem(
                title = getString(R.string.background_run_title),
                desc = getString(R.string.background_run_desc),
                status = when {
                    backgroundReady -> ready(getString(R.string.ready_label))
                    backgroundRestricted -> actionNeeded(getString(R.string.restricted_label))
                    else -> actionNeeded(getString(R.string.pending_setup_label))
                },
                actionText = if (backgroundReady) getString(R.string.view_settings_label) else getString(R.string.go_settings),
                required = true,
                autoDetectable = true,
                onAction = ::openAppDetailsForBattery
            ),
            GuideItem(
                title = getString(R.string.autostart_lock_title),
                desc = getString(R.string.autostart_lock_desc),
                status = manual(getString(R.string.suggest_confirm_label)),
                actionText = getString(R.string.view_steps),
                required = true,
                autoDetectable = false,
                onAction = ::showStartupAndLockGuide
            ),
            GuideItem(
                title = getString(R.string.notification_permission_title),
                desc = getString(R.string.notification_permission_desc),
                status = if (notificationReady) ready(getString(R.string.enabled_label)) else optional(getString(R.string.optional_label)),
                actionText = if (notificationReady) getString(R.string.view_settings_label) else getString(R.string.go_enable),
                required = false,
                autoDetectable = true,
                onAction = ::requestNotificationPermissionOrOpenSettings
            )
        )
    }

    private fun createGuideRow(item: GuideItem): View {
        return CardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            useCompatPadding = false
            addView(LinearLayout(this@GesturePermissionGuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))

                val titleRow = LinearLayout(this@GesturePermissionGuideActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                titleRow.addView(TextView(this@GesturePermissionGuideActivity).apply {
                    text = item.title
                    setTextColor(Color.parseColor("#1A2744"))
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                titleRow.addView(TextView(this@GesturePermissionGuideActivity).apply {
                    text = item.status.label
                    setTextColor(item.status.textColor)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(dp(9), dp(4), dp(9), dp(4))
                    background = roundedBg(item.status.bgColor, dp(10).toFloat())
                })
                addView(titleRow)

                addView(TextView(this@GesturePermissionGuideActivity).apply {
                    text = item.desc
                    setTextColor(Color.parseColor("#7B8794"))
                    textSize = 12f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(6), 0, dp(10))
                })

                val needsAction = item.required && item.autoDetectable && !item.status.verified
                addView(MaterialButton(this@GesturePermissionGuideActivity).apply {
                    text = item.actionText
                    textSize = 13f
                    cornerRadius = dp(10)
                    isAllCaps = false
                    insetTop = 0
                    insetBottom = 0
                    styleGuideButton(this, emphasize = needsAction)
                    setOnClickListener { item.onAction() }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(40)
                ))
            })
        }
    }

    private fun ready(label: String) =
        StatusStyle(label, Color.parseColor("#1B7F4B"), Color.parseColor("#E8F7EF"), true)

    private fun actionNeeded(label: String) =
        StatusStyle(label, Color.parseColor("#C24132"), Color.parseColor("#FDECE9"), false)

    private fun manual(label: String) =
        StatusStyle(label, Color.parseColor("#9A6500"), Color.parseColor("#FFF5D8"), false)

    private fun optional(label: String) =
        StatusStyle(label, Color.parseColor("#607D8B"), Color.parseColor("#EDF4F7"), true)

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
    }

    private fun isBackgroundRestricted(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                am.isBackgroundRestricted
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun openOverlaySettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
        } else {
            appDetailsIntent()
        }
        startSettingsActivity(intent)
    }

    private fun openAppDetailsForBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                    startActivity(intent)
                    return
                }
            }
        }
        startSettingsActivity(appDetailsIntent())
    }

    private fun openAutoStartSettings() {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"))
        )
        val started = intents.any { intent ->
            runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
        }
        if (!started) {
            Utils.toast(this, getString(R.string.autostart_hint))
            startSettingsActivity(appDetailsIntent())
        }
    }

    private fun showStartupAndLockGuide() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.autostart_lock_title))
            .setMessage(getString(R.string.autostart_guide_message))
            .setPositiveButton(getString(R.string.open_autostart_settings)) { _, _ -> openAutoStartSettings() }
            .setNegativeButton(getString(R.string.got_it), null)
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun requestNotificationPermissionOrOpenSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
            return
        }
        openNotificationSettings()
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            appDetailsIntent()
        }
        startSettingsActivity(intent)
    }

    private fun isNotificationPermissionReady(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val appNotificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val channelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.getNotificationChannel(OverlayService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
        return permissionGranted && appNotificationsEnabled && channelEnabled
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }

    private fun startSettingsActivity(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure {
                Utils.toast(this, getString(R.string.cannot_open_settings))
                runCatching { startActivity(appDetailsIntent()) }
            }
    }

    private fun selectableBorderless(): android.graphics.drawable.Drawable? {
        val out = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        return ContextCompat.getDrawable(this, out.resourceId)
    }

    private fun roundedBg(color: Int, radius: Float): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
