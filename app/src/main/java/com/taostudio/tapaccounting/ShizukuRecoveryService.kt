package com.taostudio.tapaccounting

import android.content.Context
import android.util.Log

object ShizukuRecoveryService {
    private const val TAG = "ShizukuRecovery"
    private const val SCRIPT = "/data/local/tmp/tapaccounting_shizuku_recovery.sh"
    private const val MARKER = "/data/local/tmp/tapaccounting_shizuku_recovery.enabled"
    private const val PID_FILE = "/data/local/tmp/tapaccounting_shizuku_recovery.pid"
    const val LOG_FILE = "/data/local/tmp/tapaccounting-shizuku-recovery.log"

    fun ensureStarted(context: Context): Boolean {
        if (!Prefs.isShizukuPersistenceEnabled(context)) return false
        if (!ShizukuSafe.isReady(context)) {
            Log.d(TAG, "ensureStarted skipped: Shizuku is not ready")
            return false
        }

        val pkg = context.packageName
        val command = buildStartCommand(pkg)
        val output = ShizukuShell.exec(command)
        Log.d(TAG, "ensureStarted: ${output.trim()}")
        Logger.d(context, TAG, "ensureStarted: ${output.trim()}")
        return output.contains("started") || output.contains("already-running")
    }

    fun stop(context: Context): Boolean {
        if (!ShizukuSafe.isReady(context)) {
            Log.d(TAG, "stop skipped: Shizuku is not ready")
            return false
        }

        val output = ShizukuShell.exec(
            """
            rm -f $MARKER
            if [ -f $PID_FILE ]; then
              pid="${'$'}(cat $PID_FILE 2>/dev/null)"
              if [ -n "${'$'}pid" ]; then
                kill "${'$'}pid" >/dev/null 2>&1
              fi
              rm -f $PID_FILE
            fi
            echo stopped
            """.trimIndent()
        )
        Log.d(TAG, "stop: ${output.trim()}")
        Logger.d(context, TAG, "stop: ${output.trim()}")
        return output.contains("stopped")
    }

    fun status(context: Context): String {
        if (!ShizukuSafe.isReady(context)) return "Shizuku not ready"
        return ShizukuShell.exec(
            """
            marker=missing
            [ -f $MARKER ] && marker=present
            pid=none
            alive=no
            if [ -f $PID_FILE ]; then
              pid="${'$'}(cat $PID_FILE 2>/dev/null)"
              if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" >/dev/null 2>&1; then
                alive=yes
              fi
            fi
            echo "marker=${'$'}marker pid=${'$'}pid alive=${'$'}alive log=$LOG_FILE"
            """.trimIndent()
        ).trim()
    }

    private fun buildStartCommand(pkg: String): String {
        return """
            cat > $SCRIPT <<'TAPACCOUNTING_RECOVERY'
            #!/system/bin/sh
            PKG="@PKG@"
            USER_ID=0
            MARKER="@MARKER@"
            PID_FILE="@PID_FILE@"
            LOG_FILE="@LOG_FILE@"
            CHECK_INTERVAL=10

            log() {
              echo "__D__(date '+%Y-%m-%d %H:%M:%S') __D__*" >> "__D__LOG_FILE"
            }

            package_exists() {
              pm path --user "__D__USER_ID" "__D__PKG" >/dev/null 2>&1
            }

            is_stopped() {
              dumpsys package "__D__PKG" 2>/dev/null | grep -m 1 "User __D__USER_ID:" | grep -q "stopped=true"
            }

            has_process() {
              pidof "__D__PKG" >/dev/null 2>&1
            }

            apply_allowlists() {
              cmd deviceidle whitelist "+__D__PKG" >/dev/null 2>&1
              am set-inactive --user "__D__USER_ID" "__D__PKG" false >/dev/null 2>&1
              cmd appops set --user "__D__USER_ID" "__D__PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
              cmd appops set --user "__D__USER_ID" "__D__PKG" RUN_IN_BACKGROUND allow >/dev/null 2>&1
              cmd appops set --user "__D__USER_ID" "__D__PKG" START_FOREGROUND allow >/dev/null 2>&1
              cmd appops set --user "__D__USER_ID" "__D__PKG" WAKE_LOCK allow >/dev/null 2>&1
              cmd appops set --user "__D__USER_ID" "__D__PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
              cmd appops set --user "__D__USER_ID" "__D__PKG" HIGH_SAMPLING_RATE_SENSORS allow >/dev/null 2>&1
              cmd appops set --user "__D__USER_ID" "__D__PKG" android:access_background_sensor allow >/dev/null 2>&1
            }

            clear_stopped() {
              cmd package set-stopped-state --user "__D__USER_ID" "__D__PKG" false >/dev/null 2>&1
            }

            restart_app() {
              am start-foreground-service --user "__D__USER_ID" -n "__D__PKG/.OverlayService" >> "__D__LOG_FILE" 2>&1
              sleep 3
              has_process && return 0

              am broadcast --user "__D__USER_ID" -n "__D__PKG/.BootReceiver" -a "android.intent.action.BOOT_COMPLETED" >> "__D__LOG_FILE" 2>&1
              sleep 3
              has_process && return 0
            }

            recover_if_needed() {
              package_exists || return 0
              apply_allowlists
              has_process && return 0

              if is_stopped; then
                log "detected stopped=true; clearing stopped state"
                clear_stopped
                sleep 1
              else
                log "process missing but package is not stopped"
              fi

              log "attempting restart"
              restart_app
              if has_process; then
                log "restart succeeded pid=__D__(pidof "__D__PKG" 2>/dev/null)"
              else
                log "restart failed"
              fi
            }

            echo __D____D__ > "__D__PID_FILE"
            log "daemon started interval=__D__{CHECK_INTERVAL}s"
            while [ -f "__D__MARKER" ]; do
              recover_if_needed
              sleep "__D__CHECK_INTERVAL"
            done
            log "daemon stopped"
            rm -f "__D__PID_FILE"
            TAPACCOUNTING_RECOVERY
            chmod 700 $SCRIPT
            touch $MARKER
            if [ -f $PID_FILE ]; then
              old_pid="${'$'}(cat $PID_FILE 2>/dev/null)"
              if [ -n "${'$'}old_pid" ] && kill -0 "${'$'}old_pid" >/dev/null 2>&1; then
                echo already-running pid="${'$'}old_pid"
                exit 0
              fi
            fi
            nohup sh $SCRIPT >/dev/null 2>&1 &
            sleep 1
            if [ -f $PID_FILE ]; then
              echo started pid="$(cat $PID_FILE 2>/dev/null)"
            else
              echo started
            fi
            """.trimIndent()
            .replace("@PKG@", pkg)
            .replace("@MARKER@", MARKER)
            .replace("@PID_FILE@", PID_FILE)
            .replace("@LOG_FILE@", LOG_FILE)
            .replace("__D__", "$")
    }
}
