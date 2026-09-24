package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.RecurringFrequency
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus
import java.util.Calendar
import kotlin.math.abs

class RecurringBillingService(
    private val db: AppDatabase
) {
    suspend fun scanRecentBills(
        limit: Int = 500,
        amountTolerance: Double = 1.0
    ): Int {
        val bills = db.billDao().getRecentExpenseBills(limit)
        val candidates = RecurringBillDetector.detect(bills, amountTolerance)
        val candidateKeys = candidates.map {
            patternSignature(
                merchantKey = it.merchantKey,
                bookName = it.bookName,
                categoryName = it.categoryName,
                accountName = it.accountName,
                toAccountName = "",
                billType = Bill.TYPE_EXPENSE,
                billSubType = Bill.SUBTYPE_NORMAL
            )
        }.toSet()
        var changed = 0
        for (candidate in candidates) {
            val existing = db.recurringPatternDao().getBySignature(
                merchantKey = candidate.merchantKey,
                bookName = candidate.bookName,
                categoryName = candidate.categoryName,
                accountName = candidate.accountName,
                toAccountName = "",
                billType = Bill.TYPE_EXPENSE,
                billSubType = Bill.SUBTYPE_NORMAL
            )
            if (existing?.status == RecurringStatus.DISMISSED) continue

            val detected = RecurringBillDetector.toPattern(candidate)
            if (existing == null) {
                db.recurringPatternDao().insert(detected)
                changed++
            } else if (candidate.lastSeenAt > existing.lastSeenAt) {
                db.recurringPatternDao().update(
                    existing.copy(
                        categoryId = candidate.categoryId,
                        categoryName = candidate.categoryName,
                        accountName = candidate.accountName,
                        bookName = candidate.bookName,
                        amountApprox = candidate.medianAmount,
                        amountTolerance = candidate.tolerance,
                        frequency = candidate.frequency,
                        dayOfMonthHint = candidate.dayOfMonthHint,
                        lastSeenAt = candidate.lastSeenAt,
                        nextExpectedAt = calculateNextExpected(candidate.lastSeenAt, candidate.frequency),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                changed++
            }
        }
        db.recurringPatternDao()
            .getByStatus(RecurringStatus.SUGGESTED)
            .filter { pattern ->
                patternSignature(
                    merchantKey = pattern.merchantKey,
                    bookName = pattern.bookName,
                    categoryName = pattern.categoryName,
                    accountName = pattern.accountName,
                    toAccountName = pattern.toAccountName,
                    billType = pattern.billType,
                    billSubType = pattern.billSubType
                ) !in candidateKeys
            }
            .forEach { stalePattern ->
                db.recurringPatternDao().deleteById(stalePattern.id)
                changed++
            }
        return changed
    }

    suspend fun createManualPattern(
        merchantText: String,
        amountApprox: Double,
        amountTolerance: Double,
        frequency: RecurringFrequency,
        dayOfMonthHint: Int?,
        bookName: String,
        categoryId: Long?,
        categoryName: String?,
        accountName: String?,
        toAccountName: String? = null,
        billType: Int = Bill.TYPE_EXPENSE,
        billSubType: Int = Bill.SUBTYPE_NORMAL
    ): RecurringPattern {
        val now = System.currentTimeMillis()
        val lastSeenAt = lastSeenForHint(now, frequency, dayOfMonthHint)
        val pattern = RecurringPattern(
            merchantKey = normalizeMerchantKey(merchantText),
            categoryId = categoryId,
            categoryName = categoryName,
            accountName = accountName,
            toAccountName = toAccountName.orEmpty(),
            bookName = bookName,
            billType = billType,
            billSubType = billSubType,
            amountApprox = amountApprox,
            amountTolerance = amountTolerance.coerceAtLeast(0.0),
            frequency = frequency,
            dayOfMonthHint = dayOfMonthHint,
            lastSeenAt = lastSeenAt,
            nextExpectedAt = calculateNextExpected(lastSeenAt, frequency),
            status = RecurringStatus.CONFIRMED,
            createdAt = now,
            updatedAt = now
        )
        val existing = db.recurringPatternDao().getBySignature(
            merchantKey = pattern.merchantKey,
            bookName = pattern.bookName,
            categoryName = pattern.categoryName,
            accountName = pattern.accountName,
            toAccountName = pattern.toAccountName,
            billType = pattern.billType,
            billSubType = pattern.billSubType
        )
        return if (existing == null) {
            pattern.copy(id = db.recurringPatternDao().insert(pattern))
        } else {
            val updated = pattern.copy(id = existing.id, createdAt = existing.createdAt)
            db.recurringPatternDao().update(updated)
            updated
        }
    }

    suspend fun matchNewBill(bill: Bill): RecurringPattern? {
        if (bill.subType == Bill.SUBTYPE_REFUND) return null
        val merchantKey = normalizeMerchantKey(bill.remark.ifBlank { bill.categoryName })
        if (merchantKey.isBlank()) return null

        val patterns = db.recurringPatternDao().getAll()
            .filter { it.status == RecurringStatus.CONFIRMED || it.status == RecurringStatus.SUGGESTED }
        val matched = patterns.firstOrNull { pattern ->
                    pattern.bookName == bill.bookName &&
                    pattern.billType == bill.type &&
                    pattern.billSubType == bill.subType &&
                    pattern.merchantKey == merchantKey &&
                    (pattern.categoryId == null || bill.categoryId == pattern.categoryId) &&
                    (pattern.accountName.isNullOrBlank() || pattern.accountName == bill.accountName) &&
                    (pattern.toAccountName.isBlank() || pattern.toAccountName == bill.toAccountName) &&
                    abs(bill.amount - pattern.amountApprox) <= pattern.amountTolerance.coerceAtLeast(1.0)
        } ?: return null

        val updated = matched.copy(
            amountApprox = (matched.amountApprox + bill.amount) / 2.0,
            lastSeenAt = bill.time,
            nextExpectedAt = calculateNextExpected(bill.time, matched.frequency),
            updatedAt = System.currentTimeMillis()
        )
        db.recurringPatternDao().update(updated)
        return updated
    }

    suspend fun getDuePatternsForPrompt(now: Long = System.currentTimeMillis()): List<RecurringPattern> {
        return db.recurringPatternDao().getByStatus(RecurringStatus.CONFIRMED)
            .filter { pattern -> isDueForPrompt(pattern, now) }
            .sortedBy { it.nextExpectedAt ?: Long.MAX_VALUE }
    }

    suspend fun isDueForPrompt(pattern: RecurringPattern, now: Long = System.currentTimeMillis()): Boolean {
        val expectedAt = pattern.nextExpectedAt ?: return false
        val todayStart = dayBounds(now).first
        val expectedDayStart = dayBounds(expectedAt).first
        return pattern.status == RecurringStatus.CONFIRMED &&
                expectedDayStart <= todayStart &&
                !hasMatchingBillOnExpectedDay(pattern, expectedAt)
    }

    suspend fun recordDuePattern(pattern: RecurringPattern, amount: Double): Bill {
        val billTime = pattern.nextExpectedAt?.coerceAtMost(System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val account = pattern.accountName
            ?.takeIf { it.isNotBlank() }
            ?.let { db.assetDao().getAssetByName(it) }
        val toAccount = pattern.toAccountName
            .takeIf { it.isNotBlank() }
            ?.let { db.assetDao().getAssetByName(it) }
        val bill = Bill(
            type = pattern.billType,
            subType = pattern.billSubType,
            amount = amount,
            originalAmount = amount,
            categoryId = if (pattern.billType == Bill.TYPE_TRANSFER) null else pattern.categoryId,
            accountId = account?.id,
            toAccountId = toAccount?.id,
            categoryName = pattern.categoryName.orEmpty(),
            accountName = pattern.accountName.orEmpty(),
            toAccountName = pattern.toAccountName,
            time = billTime,
            remark = pattern.merchantKey,
            bookName = pattern.bookName
        )
        val saved = BillMutationService.insertBillAndApplyImpact(db, bill)
        db.recurringPatternDao().update(
            pattern.copy(
                amountApprox = amount,
                lastSeenAt = billTime,
                nextExpectedAt = calculateNextExpected(billTime, pattern.frequency),
                updatedAt = System.currentTimeMillis()
            )
        )
        return saved
    }

    suspend fun dismissPattern(pattern: RecurringPattern) {
        db.recurringPatternDao().update(
            pattern.copy(
                status = RecurringStatus.DISMISSED,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun hasMatchingBillOnExpectedDay(pattern: RecurringPattern, expectedAt: Long): Boolean {
        val (start, end) = dayBounds(expectedAt)
        return db.billDao().getBillsBetweenTimesList(start, end).any { bill ->
            bill.type == pattern.billType &&
                    bill.subType == pattern.billSubType &&
                    bill.bookName == pattern.bookName &&
                    (pattern.billType == Bill.TYPE_TRANSFER || pattern.categoryId == null || bill.categoryId == pattern.categoryId) &&
                    (pattern.accountName.isNullOrBlank() || bill.accountName == pattern.accountName) &&
                    (pattern.toAccountName.isBlank() || bill.toAccountName == pattern.toAccountName) &&
                    normalizeMerchantKey(bill.remark.ifBlank { bill.categoryName }) == pattern.merchantKey &&
                    abs(bill.amount - pattern.amountApprox) <= pattern.amountTolerance.coerceAtLeast(1.0)
        }
    }

    private fun dayBounds(timeMillis: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return start to (cal.timeInMillis - 1)
    }

    companion object {
        fun normalizeMerchantKey(text: String): String =
            text.trim().lowercase().replace("\\s+".toRegex(), "")

        fun calculateNextExpected(
            lastSeen: Long,
            frequency: RecurringFrequency,
            dayOfMonthHint: Int? = null
        ): Long {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = lastSeen
            when (frequency) {
                RecurringFrequency.WEEKLY -> cal.add(java.util.Calendar.DAY_OF_YEAR, 7)
                RecurringFrequency.MONTHLY -> {
                    // P2-7: 用 dayOfMonthHint 锚定日，避免 1/31→2/28→3/28 月末漂移
                    val hintDay = dayOfMonthHint ?: cal.get(java.util.Calendar.DAY_OF_MONTH)
                    cal.add(java.util.Calendar.MONTH, 1)
                    val maxDay = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                    cal.set(java.util.Calendar.DAY_OF_MONTH, hintDay.coerceIn(1, maxDay))
                }
                RecurringFrequency.YEARLY -> cal.add(java.util.Calendar.YEAR, 1)
            }
            return cal.timeInMillis
        }

        private fun lastSeenForHint(
            now: Long,
            frequency: RecurringFrequency,
            dayOfMonthHint: Int?
        ): Long {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = now
            if (frequency == RecurringFrequency.MONTHLY && dayOfMonthHint != null) {
                val safeDay = dayOfMonthHint.coerceIn(1, cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
                cal.set(java.util.Calendar.DAY_OF_MONTH, safeDay)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 12)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val isSameDay = startOfDay(cal.timeInMillis) == startOfDay(now)
                if (cal.timeInMillis > now || isSameDay) {
                    cal.add(java.util.Calendar.MONTH, -1)
                }
                return cal.timeInMillis
            }
            return now
        }

        private fun startOfDay(timeMillis: Long): Long {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = timeMillis
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        private fun patternSignature(
            merchantKey: String,
            bookName: String,
            categoryName: String?,
            accountName: String?,
            toAccountName: String,
            billType: Int,
            billSubType: Int
        ): String = listOf(
            merchantKey,
            bookName,
            categoryName.orEmpty(),
            accountName.orEmpty(),
            toAccountName,
            billType.toString(),
            billSubType.toString()
        ).joinToString("\u001F")
    }
}
