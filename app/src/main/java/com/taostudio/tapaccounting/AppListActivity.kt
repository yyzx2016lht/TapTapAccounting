package com.taostudio.tapaccounting

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

import android.view.View
import android.widget.ProgressBar
import kotlinx.coroutines.*

class AppListActivity : AppCompatActivity() {

    private val selectedPackageNames = mutableSetOf<String>()
    private var allFilteredApps: List<ApplicationInfo> = emptyList() // 保存完整列表用于搜索恢复
    private lateinit var adapter: ArrayAdapter<String>
    private val displayNames = mutableListOf<String>()
    private var currentDisplayingApps: List<ApplicationInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        checkShizukuStatus()

        val listView = findViewById<ListView>(R.id.lv_apps)
        val etSearch = findViewById<EditText>(R.id.et_search_app)
        val pbLoading = findViewById<ProgressBar>(R.id.pb_loading)
        val pm = packageManager

        // 1. 初始化白名单数据
        selectedPackageNames.addAll(Prefs.getAppWhiteList(this))

        // 2. 设置适配器
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, displayNames)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        // 3. 异步获取并排序应用
        pbLoading.visibility = View.VISIBLE
        listView.visibility = View.GONE

        // P1-29: 可取消的 UI 作用域
        val uiScope = kotlinx.coroutines.MainScope()
        uiScope.launch {
            val allApps = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.GET_META_DATA).filter { app ->
                    val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val hasLauncher = pm.getLaunchIntentForPackage(app.packageName) != null
                    app.packageName != packageName && (hasLauncher || !isSystemApp)
                }.sortedWith(
                    compareByDescending<ApplicationInfo> {
                        selectedPackageNames.contains(it.packageName)
                    }.thenBy {
                        it.loadLabel(pm).toString()
                    }
                )
            }

            allFilteredApps = allApps
            pbLoading.visibility = View.GONE
            listView.visibility = View.VISIBLE
            updateListView(allFilteredApps)
        }

        // 4. 点击事件
        listView.setOnItemClickListener { _, _, position, _ ->
            val app = currentDisplayingApps[position]
            val pkg = app.packageName

            if (listView.isItemChecked(position)) {
                selectedPackageNames.add(pkg)
            } else {
                selectedPackageNames.remove(pkg)
            }
        }

        // 5. 搜索逻辑
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val keyword = s.toString().lowercase()
                val searchResult = allFilteredApps.filter {
                    it.loadLabel(pm).toString().lowercase().contains(keyword) ||
                            it.packageName.lowercase().contains(keyword)
                }
                updateListView(searchResult)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 6. 保存逻辑
        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            Prefs.setAppWhiteList(this, selectedPackageNames)
            Utils.toast(this, getString(R.string.whitelist_saved_fmt, selectedPackageNames.size))
            finish()
        }
    }

    private fun updateListView(list: List<ApplicationInfo>) {
        currentDisplayingApps = list
        displayNames.clear()
        val pm = packageManager
        list.forEach { 
            val label = it.loadLabel(pm).toString()
            displayNames.add("$label (${it.packageName})")
        }
        adapter.notifyDataSetChanged()

        // 恢复勾选状态
        val listView = findViewById<ListView>(R.id.lv_apps)
        list.forEachIndexed { index, app ->
            listView.setItemChecked(index, selectedPackageNames.contains(app.packageName))
        }
    }

    // ... checkShizukuStatus 保持不变 ...
    private fun checkShizukuStatus() {
        if (!ShizukuSafe.isBinderAlive()) {
            Utils.toast(this, getString(R.string.shizuku_not_running))
        } else if (!ShizukuSafe.hasPermission(this)) {
            Utils.toast(this, getString(R.string.shizuku_unauthorized))
        }
    }
}

