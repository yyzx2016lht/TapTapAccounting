package com.taostudio.tapaccounting.capture.ingest

import android.content.Context
import android.util.Log
import com.taostudio.tapaccounting.BuildConfig
import com.taostudio.tapaccounting.capture.contract.CaptureContract
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 主应用落库成功后，给 com.android.phone 里的 relay 回 ACK，让它删掉 52046 的队列文件。
 *
 * relay 不可用/端口没开时静默失败；队列会由 relay 的 5 秒重试继续投递，
 * 主应用按 captureId 幂等，不会重复建待记。
 */
internal object CaptureRelayAck {

    private const val TAG = "TapCaptureRelayAck"

    fun ack(captureId: String) {
        if (captureId.isBlank()) return
        try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", CaptureContract.RELAY_PORT),
                    200,
                )
                socket.soTimeout = 400
                val out = DataOutputStream(socket.getOutputStream())
                val bytes = JSONObject()
                    .put("token", BuildConfig.CAPTURE_INGEST_TOKEN)
                    .put(CaptureContract.RELAY_ACK_FIELD, captureId)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()

                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                if (length in 1..65_535) {
                    val response = ByteArray(length)
                    input.readFully(response)
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "relay ack skipped: ${t.javaClass.simpleName}")
        }
    }
}
