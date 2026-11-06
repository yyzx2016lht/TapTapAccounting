package com.taostudio.tapaccounting.capture.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.taostudio.tapaccounting.AIService
import com.taostudio.tapaccounting.RuleKeywordPolicy
import com.taostudio.tapaccounting.capture.CaptureApp
import com.taostudio.tapaccounting.capture.contract.LocalBillType
import com.taostudio.tapaccounting.capture.data.CapturePrefs
import com.taostudio.tapaccounting.capture.data.RuleLearningTask
import com.taostudio.tapaccounting.capture.data.RuleLearningTaskDao
import com.taostudio.tapaccounting.capture.parse.RuleLearningPolicy
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.AccountingRule
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

    data class RuleLearnEntry(
        val note: String,
        val categoryName: String,
        val billType: Int,
    )

    data class LearnedRule(
        val keyword: String,
        val categoryName: String,
        val billType: Int = 0,
    ) {
        val toastText: String get() = RuleLearningPolicy.toastText(keyword, categoryName)
    }

    /** 拆分入账成功后批量学习 */
    suspend fun scheduleSplit(context: Context, captureId: String, entries: List<RuleLearnEntry>) {
        if (CapturePrefs.getRuleLearnMode(context) == CapturePrefs.RULE_LEARN_OFF) {
            clearPendingTasks(context, captureId)
            return
        }
        if (entries.isEmpty()) return
        scope.launch {
            runCatching {
                val learnedList = learnEntries(context.applicationContext, entries)
                learnedList.firstOrNull()?.let {
                    RuleLearningToast.show(context, it)
                }
            }.onFailure { Log.w(TAG, "split rule learning failed: $captureId", it) }
        }
    }

    /**
     * 批量学习给定的条目列表：
     * 1. 过滤不合格条目（空备注、空分类、分类叶子 == "其他" 或 "差额调整"、非普通收支）。
     * 2. 本地短词（<=8 字符无空格/标点/动词且不等于叶子分类）直接作为关键词。
     * 3. 较长或需要 AI 提炼的条目，合并一次调用 AIService.suggestRuleKeywords。
     * 4. 保存学习到的规则至 accounting_rules 并同步至 category_rules。
     */
    suspend fun learnEntries(context: Context, entries: List<RuleLearnEntry>): List<LearnedRule> =
        withContext(Dispatchers.IO) {
            if (CapturePrefs.getRuleLearnMode(context) == CapturePrefs.RULE_LEARN_OFF) {
                return@withContext emptyList()
            }
            if (entries.isEmpty()) return@withContext emptyList()

            // 过滤有效收支与合法条目
            val eligible = entries.mapNotNull { entry ->
                val note = entry.note.trim()
                val categoryName = entry.categoryName.trim()
                val billType = entry.billType
                if (note.isEmpty() || categoryName.isEmpty()) return@mapNotNull null
                if (billType != LocalBillType.TYPE_EXPENSE && billType != LocalBillType.TYPE_INCOME) return@mapNotNull null
                val leaf = RuleLearningPolicy.leafCategoryName(categoryName)
                if (leaf.isEmpty() || leaf == "其他" || leaf == "差额调整") return@mapNotNull null
                RuleLearnEntry(note, categoryName, billType)
            }
            if (eligible.isEmpty()) return@withContext emptyList()

            val aiInputs = eligible.map {
                AIService.RuleKeywordInput(
                    note = it.note,
                    categoryName = it.categoryName,
                    billType = it.billType,
                )
            }

            val keywords = try {
                AIService.suggestRuleKeywords(context, aiInputs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "suggestRuleKeywords failed in learnEntries", t)
                emptyList()
            }

            val app = CaptureApp.from(context)
            val db = app.database
            val accountingRuleDao = db.accountingRuleDao()

            val learned = mutableListOf<LearnedRule>()
            val processedKeywordsInBatch = mutableSetOf<String>()

            for (i in eligible.indices) {
                val entry = eligible[i]
                val rawKeyword = keywords.getOrNull(i)?.trim().orEmpty()
                if (rawKeyword.isEmpty()) continue

                val lower = rawKeyword.lowercase()
                if (processedKeywordsInBatch.contains(lower)) {
                    continue
                }
                processedKeywordsInBatch.add(lower)

                val targetCategory = entry.categoryName
                val existingAcc = accountingRuleDao.findAllByKeyword(rawKeyword)
                val decision = RuleLearningPolicy.decideUnified(existingAcc, rawKeyword, targetCategory)

                var wasLearned = false
                val now = System.currentTimeMillis()
                when (decision) {
                    RuleLearningPolicy.UnifiedDecision.Insert -> {
                        accountingRuleDao.upsert(
                            RuleLearningPolicy.newAccountingRule(
                                keyword = rawKeyword,
                                targetCategory = targetCategory,
                                targetType = entry.billType,
                                learnedAt = now,
                            )
                        )
                        wasLearned = true
                    }
                    is RuleLearningPolicy.UnifiedDecision.Update -> {
                        accountingRuleDao.update(
                            decision.rule.copy(
                                targetCategory = targetCategory,
                                targetType = entry.billType,
                                source = AccountingRule.Source.AI,
                                learnedAt = now,
                                updatedAt = now,
                                enabled = true,
                            )
                        )
                        wasLearned = true
                    }
                    RuleLearningPolicy.UnifiedDecision.SkipSameCategory,
                    RuleLearningPolicy.UnifiedDecision.SkipProtected -> {
                        // 跳过
                    }
                }

                if (wasLearned) {
                    learned += LearnedRule(rawKeyword, targetCategory, entry.billType)
                }
            }

            learned
        }

    /** 入账成功后调用：满足条件就入队，并立刻在后台尝试学习。 */
    suspend fun schedule(context: Context, captureId: String) {
        if (CapturePrefs.getRuleLearnMode(context) == CapturePrefs.RULE_LEARN_OFF) {
            clearPendingTasks(context, captureId)
            return
        }

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

    private suspend fun clearPendingTasks(context: Context, captureId: String?) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dao = CaptureApp.from(context).database.ruleLearningTaskDao()
                if (captureId != null) {
                    dao.deleteByCaptureId(captureId)
                } else {
                    dao.deleteAll()
                }
            }
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
            if (CapturePrefs.getRuleLearnMode(context) == CapturePrefs.RULE_LEARN_OFF) {
                clearPendingTasks(context, captureId)
                return@withContext null
            }

            val app = CaptureApp.from(context)
            val capture = app.repository.findById(captureId) ?: return@withContext null
            // 拆分 capture 排除单条规则学习
            if (capture.splitRequested) return@withContext null
            if (!RuleLearningPolicy.isEligible(capture)) return@withContext null

            val note = capture.note?.trim().orEmpty()
            val categoryName = capture.categoryName?.trim().orEmpty()
            val billType = capture.billType ?: return@withContext null
            if (note.isEmpty() || categoryName.isEmpty()) return@withContext null

            // 若已有启用的 NOTE/ANY 规则能把备注解析到同一分类，跳过
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
                if (CapturePrefs.getRuleLearnMode(context) == CapturePrefs.RULE_LEARN_OFF) {
                    clearPendingTasks(context, null)
                    return@withContext emptyList()
                }

                val app = CaptureApp.from(context)
                val dao = app.database.ruleLearningTaskDao()
                val learned = mutableListOf<LearnedRule>()

                for (task in dao.listPending(50)) {
                    val result = try {
                        processTask(context, app, dao, task)
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
        context: Context,
        app: CaptureApp,
        taskDao: RuleLearningTaskDao,
        task: RuleLearningTask,
    ): LearnedRule? {
        if (CapturePrefs.getRuleLearnMode(context) == CapturePrefs.RULE_LEARN_OFF) {
            taskDao.deleteAll()
            return null
        }

        if (task.attempts >= RuleLearningPolicy.MAX_FAILED_ATTEMPTS) {
            taskDao.deleteById(task.id)
            return null
        }

        // 用户可能在后台学习前改了备注/分类：以数据库最新状态为准，过期任务直接丢弃。
        val capture = app.repository.findById(task.captureId)
        if (capture != null) {
            val sameNote = capture.note?.trim().orEmpty() == task.note.trim()
            val sameCategory = capture.categoryName?.trim().orEmpty() == task.categoryName.trim()
            if (capture.splitRequested || !sameNote || !sameCategory || !RuleLearningPolicy.isEligible(capture)) {
                taskDao.deleteById(task.id)
                return null
            }
        }

        val targetCategory = capture?.categoryName?.trim().orEmpty()
            .ifEmpty { task.categoryName.trim() }

        val keyword = try {
            val res = AIService.suggestRuleKeywords(
                ctx = context,
                items = listOf(
                    AIService.RuleKeywordInput(
                        note = task.note,
                        categoryName = targetCategory,
                        billType = task.billType,
                    )
                )
            )
            res.firstOrNull()?.trim().orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "suggestRuleKeyword error for task: ${task.captureId}", t)
            failTask(taskDao, task)
            return null
        }

        // 空关键词是 AI 明确判断“没有可复用词”，重试也不会变好，直接结束。
        if (keyword.isEmpty()) {
            taskDao.deleteById(task.id)
            return null
        }

        val db = app.database
        val accountingRuleDao = db.accountingRuleDao()

        val existingAcc = accountingRuleDao.findAllByKeyword(keyword)
        val now = System.currentTimeMillis()
        val learned = when (val decision = RuleLearningPolicy.decideUnified(existingAcc, keyword, targetCategory)) {
            RuleLearningPolicy.UnifiedDecision.Insert -> {
                accountingRuleDao.upsert(
                    RuleLearningPolicy.newAccountingRule(
                        keyword = keyword,
                        targetCategory = targetCategory,
                        targetType = task.billType,
                        learnedAt = now,
                    )
                )
                LearnedRule(keyword, targetCategory, task.billType)
            }

            is RuleLearningPolicy.UnifiedDecision.Update -> {
                accountingRuleDao.update(
                    decision.rule.copy(
                        targetCategory = targetCategory,
                        targetType = task.billType,
                        source = AccountingRule.Source.AI,
                        learnedAt = now,
                        updatedAt = now,
                        enabled = true,
                    )
                )
                LearnedRule(keyword, targetCategory, task.billType)
            }

            RuleLearningPolicy.UnifiedDecision.SkipSameCategory,
            RuleLearningPolicy.UnifiedDecision.SkipProtected -> null
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
