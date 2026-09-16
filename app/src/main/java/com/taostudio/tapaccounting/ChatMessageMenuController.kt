package com.taostudio.tapaccounting

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.BadTokenException
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView

class ChatMessageMenuController(
    private val context: ChatActivity,
    private val parseVoicePayload: (String) -> VoicePayload,
    private val hideVoiceTranscript: (ChatDisplayItem) -> Unit,
    private val transcribeVoiceMessage: (ChatDisplayItem, Boolean, Boolean) -> Unit,
    private val isVoiceTranscriptVisible: (ChatDisplayItem) -> Boolean,
    private val copyToClipboard: (String, String, String) -> Unit,
    private val enterVoiceSelectionMode: (ChatDisplayItem?) -> Unit,
    private val requestDeleteFromLongPressMenu: (ChatDisplayItem) -> Unit,
    private val isVoiceMode: () -> Boolean,
    private val setVoiceMode: (Boolean) -> Unit,
    private val updateVoiceModeUi: () -> Unit,
    private val etInputProvider: () -> EditText,
    private val showSoftKeyboard: (View) -> Unit,
    private val updateInputActionUi: () -> Unit,
    private val deleteBillsFromMenu: (ChatDisplayItem) -> Unit,
    private val openBillCalendar: (ChatDisplayItem) -> Unit,
    private val selectAllInTextView: (TextView) -> Unit,
    private val sharePlainText: (String) -> Unit,
    private val readAloud: (String) -> Unit
) {
    private fun canShowPopup(anchor: View): Boolean {
        if (!anchor.isAttachedToWindow || anchor.windowToken == null) return false
        if (context.isFinishing) return false
        return !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && context.isDestroyed)
    }

    private fun safeShowAsDropDown(popup: PopupWindow, anchor: View, xOff: Int, yOff: Int) {
        if (!canShowPopup(anchor)) return
        try {
            popup.showAsDropDown(anchor, xOff, yOff)
        } catch (_: BadTokenException) {
        } catch (_: IllegalStateException) {
        }
    }

    fun showVoiceMessageMenu(anchor: View, item: ChatDisplayItem) {
        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_msg_menu, null)
        val popup = createMessagePopup(popupView)

        popupView.findViewById<View>(R.id.menu_item_copy).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_edit_resend).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_transcribe).visibility = View.VISIBLE
        val voice = item.voice ?: parseVoicePayload(item.content)
        val hasTranscript = voice.transcript.trim().isNotBlank()
        val transcriptVisible = hasTranscript && isVoiceTranscriptVisible(item)
        popupView.findViewById<View>(R.id.menu_item_retranscribe).visibility =
            if (hasTranscript) View.VISIBLE else View.GONE
        popupView.findViewById<View>(R.id.menu_item_copy_transcript).visibility =
            if (hasTranscript) View.VISIBLE else View.GONE
        val tvTranscribe = popupView.findViewById<TextView>(R.id.tv_menu_transcribe)
        val ivTranscribe = popupView.findViewById<ImageView>(R.id.iv_menu_transcribe)
        tvTranscribe.text = when {
            !hasTranscript -> "转文字"
            transcriptVisible -> "收起文本"
            else -> "查看转写"
        }
        ivTranscribe.setImageResource(
            when {
                !hasTranscript -> R.drawable.ic_mic
                transcriptVisible -> R.drawable.ic_chevron_down_small
                else -> R.drawable.ic_check_circle
            }
        )
        ivTranscribe.setColorFilter(Color.WHITE)

        popupView.findViewById<View>(R.id.menu_item_transcribe).setOnClickListener {
            popup.dismiss()
            if (hasTranscript && transcriptVisible) {
                hideVoiceTranscript(item)
            } else {
                transcribeVoiceMessage(item, true, false)
            }
        }
        popupView.findViewById<View>(R.id.menu_item_retranscribe).setOnClickListener {
            popup.dismiss()
            transcribeVoiceMessage(item, true, true)
        }
        popupView.findViewById<View>(R.id.menu_item_copy_transcript).setOnClickListener {
            popup.dismiss()
            val transcript = voice.transcript.trim()
            if (transcript.isNotBlank()) {
                copyToClipboard("voice_transcript", transcript, "已复制转写文本")
            }
        }
        popupView.findViewById<View>(R.id.menu_item_multiselect).setOnClickListener {
            popup.dismiss()
            enterVoiceSelectionMode(item)
        }
        popupView.findViewById<View>(R.id.menu_item_delete).setOnClickListener {
            popup.dismiss()
            requestDeleteFromLongPressMenu(item)
        }
        safeShowAsDropDown(popup, anchor, -40, -anchor.height - 16)
    }

    fun showTranscriptMenu(anchor: View, item: ChatDisplayItem) {
        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_transcript_menu, null)
        val popup = createMessagePopup(popupView)

        popupView.findViewById<View>(R.id.popup_transcript_menu_root).setOnClickListener {
            popup.dismiss()
            hideVoiceTranscript(item)
        }
        safeShowAsDropDown(popup, anchor, -20, -anchor.height - 12)
    }

        fun showBillMessageMenu(anchor: View, item: ChatDisplayItem) {
        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_msg_menu, null)
        val popup = createMessagePopup(popupView)

        popupView.findViewById<View>(R.id.menu_item_copy).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_select_all).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_share).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_read_aloud).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_edit_resend).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_transcribe).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_retranscribe).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_copy_transcript).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_multiselect).visibility = View.GONE

        val editResend = popupView.findViewById<View>(R.id.menu_item_edit_resend)
        editResend.findViewById<TextView>(android.R.id.text1) // no id on text
        (editResend as ViewGroup).getChildAt(1)?.let { child ->
            if (child is TextView) child.text = context.getString(R.string.chat_bill_open_calendar)
        }

        val deletableCount = ChatBillUiHelper.deletableBills(item).size
        val deleteItem = popupView.findViewById<View>(R.id.menu_item_delete)
        (deleteItem as ViewGroup).getChildAt(1)?.let { child ->
            if (child is TextView) {
                child.text = if (deletableCount >= 2) {
                    context.getString(R.string.chat_bill_menu_delete_all)
                } else {
                    context.getString(R.string.delete)
                }
            }
        }
        deleteItem.visibility = if (deletableCount > 0) View.VISIBLE else View.GONE

        popupView.findViewById<View>(R.id.menu_item_copy).setOnClickListener {
            popup.dismiss()
            val summary = ChatBillUiHelper.buildCopySummary(item)
            copyToClipboard("chat_bill_summary", summary, "已复制")
        }
        popupView.findViewById<View>(R.id.menu_item_share).setOnClickListener {
            popup.dismiss()
            sharePlainText(ChatBillUiHelper.buildCopySummary(item))
        }
        popupView.findViewById<View>(R.id.menu_item_read_aloud).setOnClickListener {
            popup.dismiss()
            readAloud(ChatBillUiHelper.buildCopySummary(item))
        }
        editResend.setOnClickListener {
            popup.dismiss()
            openBillCalendar(item)
        }
        deleteItem.setOnClickListener {
            popup.dismiss()
            deleteBillsFromMenu(item)
        }
        safeShowAsDropDown(popup, anchor, -40, -anchor.height - 16)
    }

    fun showTextMessageMenu(anchor: View, item: ChatDisplayItem) {
        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_msg_menu, null)
        val popup = createMessagePopup(popupView)

        popupView.findViewById<View>(R.id.menu_item_copy).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_select_all).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_share).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_read_aloud).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_edit_resend).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_transcribe).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_retranscribe).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_copy_transcript).visibility = View.GONE

        popupView.findViewById<View>(R.id.menu_item_copy).setOnClickListener {
            popup.dismiss()
            val text = item.content.trim()
            if (text.isEmpty()) return@setOnClickListener
            copyToClipboard("chat_message", text, "已复制")
        }
        popupView.findViewById<View>(R.id.menu_item_select_all).setOnClickListener {
            popup.dismiss()
            val tv = anchor as? TextView ?: return@setOnClickListener
            val text = item.content.trim()
            if (text.isEmpty()) return@setOnClickListener
            selectAllInTextView(tv)
        }
        popupView.findViewById<View>(R.id.menu_item_share).setOnClickListener {
            popup.dismiss()
            val text = item.content.trim()
            if (text.isEmpty()) return@setOnClickListener
            sharePlainText(text)
        }
        popupView.findViewById<View>(R.id.menu_item_read_aloud).setOnClickListener {
            popup.dismiss()
            val text = item.content.trim()
            if (text.isEmpty()) return@setOnClickListener
            readAloud(text)
        }
        popupView.findViewById<View>(R.id.menu_item_edit_resend).setOnClickListener {
            popup.dismiss()
            val text = item.content.trim()
            if (text.isEmpty()) return@setOnClickListener
            if (isVoiceMode()) {
                setVoiceMode(false)
                updateVoiceModeUi()
            }
            val etInput = etInputProvider()
            etInput.setText(text)
            etInput.setSelection(text.length)
            etInput.requestFocus()
            showSoftKeyboard(etInput)
            updateInputActionUi()
        }
        popupView.findViewById<View>(R.id.menu_item_multiselect).setOnClickListener {
            popup.dismiss()
            enterVoiceSelectionMode(item)
        }
        popupView.findViewById<View>(R.id.menu_item_delete).setOnClickListener {
            popup.dismiss()
            requestDeleteFromLongPressMenu(item)
        }
        safeShowAsDropDown(popup, anchor, -40, -anchor.height - 16)
    }

    private fun createMessagePopup(contentView: View): PopupWindow {
        return PopupWindow(
            contentView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
    }
}

