package com.taostudio.tapaccounting

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ChatAudioRecordController(
    private val context: ChatActivity,
    private val sampleRate: Int,
    private val channelConfig: Int,
    private val audioFormat: Int,
    private val audioBufferSizeProvider: () -> Int,
    private val btnVoiceHoldProvider: () -> MaterialButton,
    private val onVoiceHoldRecording: (Boolean) -> Unit,
    private val getAudioRecord: () -> AudioRecord?,
    private val setAudioRecord: (AudioRecord?) -> Unit,
    private val getAudioFile: () -> File?,
    private val setAudioFile: (File?) -> Unit,
    private val getRecordingThread: () -> Thread?,
    private val setRecordingThread: (Thread?) -> Unit,
    private val isRecording: () -> Boolean,
    private val setIsRecording: (Boolean) -> Unit,
    private val getRecordingStartAt: () -> Long,
    private val setRecordingStartAt: (Long) -> Unit,
    private val startRecordingButtonPulse: () -> Unit,
    private val stopRecordingButtonPulse: () -> Unit,
    private val showVoiceRecordOverlay: (Boolean) -> Unit,
    private val hideVoiceRecordOverlay: () -> Unit,
    private val clearPendingLongPress: () -> Unit
) {
    @android.annotation.SuppressLint("MissingPermission")
    fun startVoiceRecording(): Boolean {
        if (isRecording()) return true
        val tempFile = File(context.cacheDir, "chat_voice_input.wav")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            audioBufferSizeProvider()
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        return try {
            record.startRecording()
            setAudioRecord(record)
            setAudioFile(tempFile)
            setIsRecording(true)
            setRecordingStartAt(System.currentTimeMillis())
            val btnVoiceHold = btnVoiceHoldProvider()
            btnVoiceHold.text = context.getString(R.string.release_to_send)
            onVoiceHoldRecording(true)
            startRecordingButtonPulse()
            showVoiceRecordOverlay(false)
            setRecordingThread(Thread { writeAudioDataToFile(tempFile) })
            getRecordingThread()?.start()
            true
        } catch (_: Exception) {
            setIsRecording(false)
            try {
                record.release()
            } catch (_: Exception) {
            }
            false
        }
    }

    fun stopVoiceRecording(onFileReady: (File?, Int) -> Unit) {
        clearPendingLongPress()
        if (!isRecording()) {
            onFileReady(null, 0)
            return
        }
        setIsRecording(false)
        stopRecordingButtonPulse()
        onVoiceHoldRecording(false)
        hideVoiceRecordOverlay()
        try {
            getAudioRecord()?.stop()
        } catch (_: Exception) {
        }
        try {
            getAudioRecord()?.release()
        } catch (_: Exception) {
        }
        setAudioRecord(null)
        try {
            getRecordingThread()?.join(1200)
        } catch (_: Exception) {
        }
        setRecordingThread(null)
        val durationMs = (System.currentTimeMillis() - getRecordingStartAt()).coerceAtLeast(400L)
        val durationSec = kotlin.math.ceil(durationMs / 1000.0).toInt().coerceAtLeast(1)
        val readyFile = getAudioFile()?.takeIf { it.exists() && it.length() > 44L }
        onFileReady(readyFile, durationSec)
    }

    fun copyVoiceFileToStorage(tempFile: File): File {
        val voiceDir = File(context.filesDir, "chat_voice").also { it.mkdirs() }
        val dest = File(voiceDir, "voice_${System.currentTimeMillis()}.wav")
        tempFile.inputStream().use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        return dest
    }

    private fun writeAudioDataToFile(file: File) {
        val data = ByteArray(audioBufferSizeProvider())
        try {
            FileOutputStream(file).use { os ->
                os.write(ByteArray(44), 0, 44)
                var totalAudioLen = 0L
                while (isRecording()) {
                    val read = getAudioRecord()?.read(data, 0, data.size) ?: AudioRecord.ERROR_INVALID_OPERATION
                    when {
                        read > 0 -> {
                            os.write(data, 0, read)
                            totalAudioLen += read
                        }
                        read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_INVALID_OPERATION -> break
                    }
                }
                updateWavHeader(file, totalAudioLen)
            }
        } catch (_: IOException) {
            // P1-27: 录音线程异常面收严，避免 stop/release 竞态崩溃
        } catch (_: IllegalStateException) {
        } catch (_: RuntimeException) {
        }
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val channels = 1
        val byteRate = 16 * longSampleRate * channels / 8
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte()
        header[5] = (totalDataLen shr 8 and 0xffL).toByte()
        header[6] = (totalDataLen shr 16 and 0xffL).toByte()
        header[7] = (totalDataLen shr 24 and 0xffL).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[20] = 1
        header[22] = channels.toByte()
        header[24] = (longSampleRate and 0xffL).toByte()
        header[25] = (longSampleRate shr 8 and 0xffL).toByte()
        header[26] = (longSampleRate shr 16 and 0xffL).toByte()
        header[27] = (longSampleRate shr 24 and 0xffL).toByte()
        header[28] = (byteRate and 0xffL).toByte()
        header[29] = (byteRate shr 8 and 0xffL).toByte()
        header[30] = (byteRate shr 16 and 0xffL).toByte()
        header[31] = (byteRate shr 24 and 0xffL).toByte()
        header[32] = (1 * 16 / 8).toByte()
        header[34] = 16
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte()
        header[41] = (totalAudioLen shr 8 and 0xffL).toByte()
        header[42] = (totalAudioLen shr 16 and 0xffL).toByte()
        header[43] = (totalAudioLen shr 24 and 0xffL).toByte()
        runCatching {
            java.io.RandomAccessFile(file, "rw").use {
                it.seek(0)
                it.write(header)
            }
        }
    }
}

