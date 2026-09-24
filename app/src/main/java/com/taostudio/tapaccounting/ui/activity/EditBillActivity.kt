package com.taostudio.tapaccounting.ui.activity

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.taostudio.tapaccounting.TapApplication
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.logic.AccountingFormController
import java.text.SimpleDateFormat
import java.util.*

class EditBillActivity : AppCompatActivity() {

    private var billId: Long = -1
    private var isCopy: Boolean = false
    private var formController: AccountingFormController? = null
    private var bottomSheet: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this)
        setContentView(container)

        billId = intent.getLongExtra("BILL_ID", -1)
        isCopy = intent.getBooleanExtra("IS_COPY", false)

        showBottomSheet()
    }

    private fun showBottomSheet() {
        bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_floating_window, null)
        
        formController = AccountingFormController(
            ctx = this,
            rootView = view,
            onCloseRequest = { isSaved ->
                if (isSaved) {
                    setResult(RESULT_OK)
                }
                bottomSheet?.dismiss()
            }
        )

        if (billId != -1L) {
            loadBillData()
        }

        bottomSheet?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                formController?.handleBackPressed() == true
            } else {
                false
            }
        }

        bottomSheet?.setOnDismissListener {
            finish()
        }

        bottomSheet?.setContentView(view)
        bottomSheet?.show()
    }

    override fun onDestroy() {
        // P2-14: prevent window leak
        bottomSheet?.dismiss()
        bottomSheet = null
        formController?.destroy()
        formController = null
        super.onDestroy()
    }

    private fun loadBillData() {
        val app = application as TapApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val bill = app.billRepository.getBillById(billId)
            if (bill != null) {
                val json = JSONObject()
                // P1-1: 有退款的支出 amount 是净额；编辑表单展示 original
                val formAmount = if (bill.originalAmount > bill.amount) bill.originalAmount else bill.amount
                json.put("amount", formAmount)
                json.put("originalAmount", bill.originalAmount)
                json.put("type", bill.type) // 0-支出, 1-收入...
                json.put("category_name", bill.categoryName)
                json.put("asset_name", bill.accountName)
                json.put("remark", bill.remark)
                json.put("currency", bill.currency)
                json.put("exchange_rate", bill.exchangeRate)
                json.put("fee", bill.fee)
                json.put("subType", bill.subType)
                json.put("bookName", bill.bookName)
                
                // 如果有 toAccountId (转账目标)，需要查出对应名字
                if (bill.type == 2) {
                    val toAssetName = if (bill.toAccountId != null) {
                        app.assetRepository.getAssetById(bill.toAccountId)?.name
                    } else {
                        null
                    } ?: bill.toAccountName.takeIf { it.isNotBlank() }
                    if (!toAssetName.isNullOrBlank()) {
                        json.put("to_asset_name", toAssetName)
                    }
                }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                
                if (isCopy) {
                    json.put("time", dateFormat.format(Date()))
                } else {
                    json.put("time", dateFormat.format(Date(bill.time)))
                    json.put("recordTime", bill.id.toString()) 
                }

                withContext(Dispatchers.Main) {
                    formController?.fillDataToUi(json, showToast = false)
                }
            }
        }
    }
}

