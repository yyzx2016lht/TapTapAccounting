package com.taostudio.tapaccounting.capture.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.data.CategoryRule
import com.taostudio.tapaccounting.capture.data.RuleLearningTask
import com.taostudio.tapaccounting.capture.data.RuleLearningTaskDao
import com.taostudio.tapaccounting.capture.parse.RuleLearningPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 入账成功后的 AI 规则学习副作用。
 *
 * 调用方只负责 [schedule]（毫秒级入队）；AI 提取、写规则、toast 全在后台完成。
 * 任何失败都不会抛回入账路径，任务留在 `rule_learning_tasks` 里等下次启动补跑，
 * 首次失败后最多重试 2 次（总计 3 次尝试）。
 */
object RuleLearningManager {

    private const val TAG = "TapRuleLearning"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    data class LearnedRule(
        val keyword: String,
        val categoryName: String,
    ) {
        val toastText: String get() = RuleLearningPolicy.toastText(keyword, categoryName)
    }

    /** 入账成功后调用：满足条件就入队，并立刻在后台尝试学习。 */
    suspend fun schedule(context: Context, captureId: String) {
        val task = try {
            enqueue(context, captureId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 学习是后台副作用，数据库异常也绝不能影响已完成的入账。
            Log.w(TAG, "enqueue rule learning failed: $captureId", t)
            null
        } ?: return
        scope.launch {
            runCatching { process(context.applicationContext, notify = true) }
                .onFailure { Log.w(TAG, "rule learning failed: ${task.captureId}", it) }
        }
    }

    /** 插件启动时补跑历史失败任务；静默执行，不打扰用户。 */
    fun processPendingInBackground(context: Context) {
        scope.launch {
            runCatching { process(context.applicationContext, notify = false) }
                .onFailure { Log.w(TAG, "pending rule learning failed", it) }
        }
    }

    /** 单测/调试可直接等待处理完成。 */
    suspend fun processNow(context: Context, notify: Boolean = false): List<LearnedRule> =
        process(context.applicationContext, notify)

    private suspend fun enqueue(context: Context, captureId: String): RuleLearningTask? =
        withContext(Dispatchers.IO) {
            val app = CaptureApp.from(context)
            val capture = app.repository.findById(captureId) ?: return@withContext null
            if (!RuleLearningPolicy.isEligible(capture)) return@withContext null

            val note = capture.note?.trim().orEmpty()
            val categoryName = capture.categoryName?.trim().orEmpty()
            val billType = capture.billType ?: return@withContext null
            if (note.isEmpty() || categoryName.isEmpty()) return@withContext null

            // 若已有规则能直接将该备注命中该分类，无需重复调用 AI 学习
            val existingRuleCat = com.taostudio.tapaccounting.capture.parse.CaptureRuleMatcher.resolveNoteCategory(context, note)
            if (existingRuleCat == categoryName) return@withContext null

            val dao = app.database.ruleLearningTaskDao()
            dao.findByCaptureId(captureId)?.let { return@withContext it }

            dao.insertIgnore(
                RuleLearningTask(
                    captureId = capture.id,
                    note = note,
                    categoryName = categoryName,
                    billType = billType,
                    attempts = 0,
                    createdAt = System.currentTimeMillis(),
                )
            )
            dao.findByCaptureId(captureId)
        }

    private suspend fun process(context: Context, notify: Boolean): List<LearnedRule> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val app = CaptureApp.from(context)
                val dao = app.database.ruleLearningTaskDao()
                val client = CaptureBridgeClient(context)
                val learned = mutableListOf<LearnedRule>()

                for (task in dao.listPending(50)) {
                    val result = try {
                        processTask(app, dao, client, task)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "process rule learning task failed: ${task.captureId}", t)
                        failTask(dao, task)
                        null
                    }
                    if (result != null) {
                        learned += result
                        if (notify) RuleLearningToast.show(context, result)
                    }
                }
                learned
            }
        }

    private suspend fun processTask(
        app: CaptureApp,
        taskDao: RuleLearningTaskDao,
        client: CaptureBridgeClient,
        task: RuleLearningTask,
    ): LearnedRule? {
        if (task.attempts >= RuleLearningPolicy.MAX_FAILED_ATTEMPTS) {
            taskDao.deleteById(task.id)
            return null
        }

        // 用户可能在后台学习前改了备注/分类：以数据库最新状态为准，过期任务直接丢弃。
        val capture = app.repository.findById(task.captureId)
        if (capture != null) {
            val sameNote = capture.note?.trim().orEmpty() == task.note.trim()
            val sameCategory = capture.categoryName?.trim().orEmpty() == task.categoryName.trim()
            if (!sameNote || !sameCategory || !RuleLearningPolicy.isEligible(capture)) {
                taskDao.deleteById(task.id)
                return null
            }
        }

        val suggestion = client.suggestRuleKeyword(
            note = task.note,
            categoryName = task.categoryName,
            billType = task.billType,
        )
        if (!suggestion.ok) {
            failTask(taskDao, task)
            return null
        }

        val keyword = suggestion.keyword.trim()
        // 空关键词是 AI 明确判断“没有可复用词”，重试也不会变好，直接结束。
        if (keyword.isEmpty()) {
            taskDao.deleteById(task.id)
            return null
        }

        val targetCategory = capture?.categoryName?.trim().orEmpty()
            .ifEmpty { task.categoryName.trim() }
        val ruleDao = app.database.categoryRuleDao()
        val existing = ruleDao.findByKeyword(keyword)
        val learned = when (val decision = RuleLearningPolicy.decide(existing, keyword, targetCategory)) {
            RuleLearningPolicy.Decision.Insert -> {
                ruleDao.upsert(
                    RuleLearningPolicy.newAiRule(
                        keyword = keyword,
                        targetCategoryName = targetCategory,
                        learnedAt = System.currentTimeMillis(),
                    )
                )
                LearnedRule(keyword, targetCategory)
            }

            is RuleLearningPolicy.Decision.Update -> {
                ruleDao.update(
                    decision.rule.copy(
                        targetCategoryName = targetCategory,
                        source = CategoryRule.SOURCE_AI,
                        learnedAt = System.currentTimeMillis(),
                        enabled = true,
                    )
                )
                LearnedRule(keyword, targetCategory)
            }

            RuleLearningPolicy.Decision.SkipSameCategory,
            RuleLearningPolicy.Decision.SkipProtected -> null
        }

        taskDao.deleteById(task.id)
        return learned
    }

    private suspend fun failTask(dao: RuleLearningTaskDao, task: RuleLearningTask) {
        val nextAttempts = task.attempts + 1
        if (nextAttempts >= RuleLearningPolicy.MAX_FAILED_ATTEMPTS) {
            dao.deleteById(task.id)
        } else {
            dao.updateAttempts(task.id, nextAttempts)
        }
    }
}

/** 学习成功后的 toast：只显示二级分类，失败静默。 */
internal object RuleLearningToast {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, learned: RuleLearningManager.LearnedRule) {
        val appContext = context.applicationContext
        mainHandler.post {
            runCatching {
                Toast.makeText(appContext, learned.toastText, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
