package com.taostudio.tapaccounting.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.CurrencyData
import com.taostudio.tapaccounting.CurrencyInfo
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.logic.CurrencyManager

class CurrencyManagerActivity : AppCompatActivity() {

    private companion object {
        /** 主币种：默认启用且不可取消。 */
        const val PRIMARY_CURRENCY = "CNY"
    }

    private lateinit var rvList: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var tvSelectedSummary: TextView
    private lateinit var tvPageHint: TextView
    private lateinit var layoutLoading: View
    private lateinit var layoutEmpty: View
    private lateinit var adapter: CurrencyAdapter
    private var allCurrencies: List<CurrencyInfo> = emptyList()
    
    // Memory cache of enabled currencies code
    private val enabledSet = HashSet<String>()

    private fun openExchangeRatePage() {
        val intent = android.content.Intent(this, ExchangeRateActivity::class.java)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency_manager)
        
        // Load initial state
        enabledSet.addAll(CurrencyManager.getEnabledCurrencies(this))
        // Ensure CNY is always there
        enabledSet.add("CNY")

        initViews()
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btn_header_action).setOnClickListener { openExchangeRatePage() }
        findViewById<View>(R.id.layout_rate_settings_entry).setOnClickListener { openExchangeRatePage() }
        etSearch = findViewById(R.id.et_search)
        tvSelectedSummary = findViewById(R.id.tv_selected_summary)
        tvPageHint = findViewById(R.id.tv_page_hint)
        layoutLoading = findViewById(R.id.layout_loading)
        layoutEmpty = findViewById(R.id.layout_empty)
        rvList = findViewById(R.id.rv_currency_list)
        rvList.layoutManager = LinearLayoutManager(this)

        updateSummary()
        loadCurrenciesAsync()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (::adapter.isInitialized) {
                    adapter.filter(s.toString())
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun loadCurrenciesAsync() {
        layoutLoading.visibility = View.VISIBLE
        rvList.visibility = View.INVISIBLE
        layoutEmpty.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.Default) {
            val rateCodes = CurrencyManager.getSupportedCurrencies()
            val currencies = CurrencyData.getAllCurrencies(rateCodes)
            withContext(Dispatchers.Main) {
                allCurrencies = currencies
                adapter = CurrencyAdapter(allCurrencies)
                rvList.adapter = adapter
                layoutLoading.visibility = View.GONE
                rvList.visibility = View.VISIBLE
                updateSummary()
                updateEmptyState(adapter.itemCount == 0)
            }
        }
    }

    private fun updateSummary() {
        val selectedCount = enabledSet.size
        tvSelectedSummary.text = getString(R.string.enabled_currency_count, selectedCount)
        tvPageHint.text = if (selectedCount <= 1) {
            getString(R.string.currency_default_hint)
        } else {
            getString(R.string.currency_hint_multi)
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        super.onPause()
        // Save whenever leaving this screen
        CurrencyManager.setEnabledCurrencies(this, enabledSet.toList())
        CurrencyManager.updateRates(this)
    }

    inner class CurrencyAdapter(private val originalList: List<CurrencyInfo>) : RecyclerView.Adapter<CurrencyAdapter.ViewHolder>() {
        
        // We want to show:
        // 1. Enabled items at the top (sorted roughly)
        // 2. Disabled items below
        // But the user requested search. Search usually filters the whole list.
        // Let's implement this: 
        // If search is empty: Show "My Currencies" section (Implicitly at top) then others
        // Actually, a single list with Checked state is simplest. 
        // Let's just sort: Enabled first, then others. Both alphabetical.
        
        private var displayedList: List<CurrencyInfo> = sortList(originalList)

        private fun sortList(list: List<CurrencyInfo>): List<CurrencyInfo> {
            return list.sortedWith(compareByDescending<CurrencyInfo> { enabledSet.contains(it.code) }
                .thenBy { it.code })
        }

        fun filter(query: String) {
            val q = query.trim()
            displayedList = if (q.isEmpty()) {
                sortList(originalList)
            } else {
                // When searching, we just show matches, but keeping checked ones visually checked
                originalList.filter { it.matches(q) }
                    .sortedWith(compareByDescending<CurrencyInfo> { enabledSet.contains(it.code) }.thenBy { it.code })
            }
            notifyDataSetChanged()
            updateEmptyState(displayedList.isEmpty())
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_currency_select, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = displayedList.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = displayedList[position]
            holder.bind(item)
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvFlag: TextView = itemView.findViewById(R.id.tv_currency_flag)
            val tvCode: TextView = itemView.findViewById(R.id.tv_currency_code)
            val tvSymbol: TextView = itemView.findViewById(R.id.tv_currency_symbol)
            val tvName: TextView = itemView.findViewById(R.id.tv_currency_name)
            val cbSelect: CheckBox = itemView.findViewById(R.id.cb_select)
            val tvPrimaryBadge: TextView = itemView.findViewById(R.id.tv_primary_badge)

            fun bind(item: CurrencyInfo) {
                tvFlag.text = item.flagEmoji
                tvCode.text = item.code
                tvSymbol.text = item.symbol
                tvName.text = "${item.nameZh} · ${item.countryZh}"

                val isChecked = enabledSet.contains(item.code)
                cbSelect.isChecked = isChecked
                tvPrimaryBadge.visibility =
                    if (item.code == PRIMARY_CURRENCY) View.VISIBLE else View.GONE

                // CNY 是主币种，不可取消。
                // 不再设 alpha / isEnabled：变暗 + 灰勾在 Android 上等于「不可用」的通用信号，
                // 而主币种恰恰是默认且必须开启的那一个。现在整行保持正常对比度、勾保持品牌色，
                // 由「主币种」徽章解释它为什么点不动（勾本身不挂监听，XML 里也是 clickable=false）。
                cbSelect.isEnabled = true
                if (item.code == PRIMARY_CURRENCY) {
                    cbSelect.setOnClickListener(null)
                    itemView.setOnClickListener(null)
                } else {
                    itemView.setOnClickListener {
                        toggleSelection(item)
                    }
                    // Allow clicking checkbox directly too, but better just item click
                    cbSelect.setOnClickListener {
                        toggleSelection(item)
                    }
                }
            }

            private fun toggleSelection(item: CurrencyInfo) {
                if (enabledSet.contains(item.code)) {
                    enabledSet.remove(item.code)
                } else {
                    enabledSet.add(item.code)
                }
                notifyItemChanged(adapterPosition)
                updateSummary()
            }
        }
    }
}

