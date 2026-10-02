package com.taostudio.tapaccounting.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.CurrencyData
import com.taostudio.tapaccounting.CurrencyInfo
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.logic.CurrencyManager
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class ExchangeRateActivity : AppCompatActivity() {

    private lateinit var rvRates: RecyclerView
    private lateinit var spInterval: Spinner
    private lateinit var tvRateSummary: TextView
    private lateinit var tvLastUpdate: TextView
    private lateinit var btnRefresh: ImageView
    private lateinit var layoutEmpty: View
    
    private val intervals = listOf(
        "15 分钟" to 15,
        "30 分钟" to 30,
        "1 小时" to 60,
        "2 小时" to 120,
        "6 小时" to 360,
        "12 小时" to 720,
        "1 天" to 1440
    )

    private lateinit var adapter: RateAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exchange_rate)
        
        initViews()
        loadData()
        refreshRatesSilentlyIfNeeded()
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        rvRates = findViewById(R.id.rv_rates)
        spInterval = findViewById(R.id.spinner_interval)
        tvRateSummary = findViewById(R.id.tv_rate_summary)
        tvLastUpdate = findViewById(R.id.tv_last_update)
        btnRefresh = findViewById(R.id.btn_refresh)
        layoutEmpty = findViewById(R.id.layout_empty)

        btnRefresh.setOnClickListener {
            btnRefresh.animate().rotationBy(360f).setDuration(500).start()
            CurrencyManager.updateRates(this) { success ->
                if (success) {
                    loadData()
                    Toast.makeText(this, getString(R.string.rate_updated), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.rate_update_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }

        setupIntervalSpinner()
    }

    private fun setupIntervalSpinner() {
        val adapterStr = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervals.map { it.first })
        adapterStr.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spInterval.adapter = adapterStr

        val currentInterval = CurrencyManager.getRefreshInterval(this)
        val index = intervals.indexOfFirst { it.second == currentInterval }
        if (index >= 0) {
            spInterval.setSelection(index)
        }

        spInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = intervals[position].second
                CurrencyManager.setRefreshInterval(this@ExchangeRateActivity, selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadData() {
        val lastUpdate = CurrencyManager.getLastUpdateTime(this)
        if (lastUpdate > 0) {
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            tvLastUpdate.text = getString(R.string.last_update_fmt, df.format(Date(lastUpdate)))
        } else {
            tvLastUpdate.text = getString(R.string.last_update_never)
        }

        // Only show rates for selected currencies (excluding CNY which is base 1:1)
        val enabledCodes = CurrencyManager.getEnabledCurrencies(this).filter { it != "CNY" }
        val items = enabledCodes.mapNotNull { code ->
            val info = CurrencyData.getInfo(code)
            val rate = CurrencyManager.getRate(code)
            if (info != null && rate != null) {
                RateItem(info, rate)
            } else null
        }

        tvRateSummary.text = getString(R.string.currency_count_fmt, items.size)
        layoutEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rvRates.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        adapter = RateAdapter(items)
        rvRates.layoutManager = LinearLayoutManager(this)
        rvRates.adapter = adapter
    }

    private fun refreshRatesSilentlyIfNeeded() {
        val enabledCodes = CurrencyManager.getEnabledCurrencies(this).filter { it != "CNY" }
        val hasMissingRate = enabledCodes.any { CurrencyManager.getRate(it) == null }
        if (!hasMissingRate) return

        CurrencyManager.updateRates(this) { success ->
            if (success) {
                loadData()
            }
        }
    }

    data class RateItem(val info: CurrencyInfo, val rate: Double)

    inner class RateAdapter(private val items: List<RateItem>) : RecyclerView.Adapter<RateAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_exchange_rate, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvFlag.text = item.info.flagEmoji
            holder.tvCode.text = "${item.info.code} ${item.info.nameZh}"
            
            // 1 人民币(CNY) = X 目标币种
            holder.tvInfo.text = "1 人民币（CNY） = ${formatRate(item.rate)} ${item.info.code}"

            // 1 目标币种 ≈ 反向人民币(CNY)。
            // 小数位按数量级自适应：韩元的反向汇率是 0.004958，固定两位小数会打成 0.00，
            // 看上去像功能坏了。
            val reverseRate = if (item.rate != 0.0) 1.0 / item.rate else 0.0
            holder.tvReverseInfo.text = "≈ ${formatRate(reverseRate)} CNY"
            holder.tvReverseUnit.text = "1 ${item.info.code}"
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvFlag: TextView = view.findViewById(R.id.tv_flag)
            val tvCode: TextView = view.findViewById(R.id.tv_code)
            val tvInfo: TextView = view.findViewById(R.id.tv_info)
            val tvReverseInfo: TextView = view.findViewById(R.id.tv_reverse_info)
            val tvReverseUnit: TextView = view.findViewById(R.id.tv_reverse_unit)
        }
    }

    companion object {
        /**
         * 汇率格式化：按数量级选小数位，避免小额货币被四舍五入成 0.00。
         *
         * 恒定两位小数对 KRW（1 KRW ≈ 0.00496 CNY）这类汇率是有害的。
         * 一律 [Locale.US]，界面与数据库里都是字面小数点，不能跟系统区域跑。
         */
        private fun formatRate(value: Double): String {
            val magnitude = abs(value)
            val pattern = when {
                magnitude >= 1000 -> "#,##0.##"
                magnitude >= 1 -> "#,##0.####"
                magnitude >= 0.01 -> "0.####"
                magnitude == 0.0 -> "0"
                else -> "0.######"
            }
            return DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(value)
        }
    }
}

