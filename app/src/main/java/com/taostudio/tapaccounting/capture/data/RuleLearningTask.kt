package com.taostudio.tapaccounting.capture.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 待执行的 AI 规则学习任务。
 *
 * 入账成功后只做一次轻量入队，真正的 AI 提取在后台完成；失败留在表里，
 * 插件下次启动时补跑，最多失败重试 2 次（首次 + 2 次重试 = 最多 3 次尝试）。
 */
@Entity(
    tableName = "rule_learning_tasks",
    indices = [Index(value = ["captureId"], unique = true)],
)
data class RuleLearningTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 首次入账成功的待记 id；同一笔只学习一次。 */
    val captureId: String,
    val note: String,
    /** 完整分类路径（如 "吃的 - 水果"），与 CategoryRule.targetCategoryName 一致。 */
    val categoryName: String,
    val billType: Int,
    /** 已失败次数；>= [RuleLearningPolicy.MAX_FAILED_ATTEMPTS] 时丢弃。 */
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface RuleLearningTaskDao {
    @Query("SELECT * FROM rule_learning_tasks ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun listPending(limit: Int = 20): List<RuleLearningTask>

    @Query("SELECT * FROM rule_learning_tasks WHERE captureId = :captureId LIMIT 1")
    suspend fun findByCaptureId(captureId: String): RuleLearningTask?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(task: RuleLearningTask): Long

    @Query("UPDATE rule_learning_tasks SET attempts = :attempts WHERE id = :id")
    suspend fun updateAttempts(id: Long, attempts: Int): Int

    @Query("DELETE FROM rule_learning_tasks WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM rule_learning_tasks WHERE captureId = :captureId")
    suspend fun deleteByCaptureId(captureId: String): Int

    @Query("DELETE FROM rule_learning_tasks")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM rule_learning_tasks")
    suspend fun count(): Int
}
