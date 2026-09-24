package com.taostudio.tapaccounting.data.backup

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.CategoryNameNormalizer
import com.taostudio.tapaccounting.logic.CurrencyManager
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object CsvManager {

    private data class ImportedBillType(
        val type: Int,
        val subType: Int,
        val excludeFromStats: Boolean = false
    )

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val sdfDateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val qianJiTypeMap = mapOf(
        "支出" to ImportedBillType(Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL),
        "收入" to ImportedBillType(Bill.TYPE_INCOME, Bill.SUBTYPE_NORMAL),
        "转账" to ImportedBillType(Bill.TYPE_TRANSFER, Bill.SUBTYPE_NORMAL),
        "退款" to ImportedBillType(Bill.TYPE_INCOME, Bill.SUBTYPE_REFUND),
        "还款" to ImportedBillType(Bill.TYPE_TRANSFER, Bill.SUBTYPE_REPAYMENT),
        "不计收支" to ImportedBillType(Bill.TYPE_EXPENSE, Bill.SUBTYPE_NORMAL, excludeFromStats = true),
        "报销" to ImportedBillType(Bill.TYPE_INCOME, Bill.SUBTYPE_NORMAL)
    )

    private val header = listOf(
        "time",
        "id",
        "type",
        "subType",
        "amount",
        "originalAmount",
        "currency",
        "exchangeRate",
        "categoryName",
        "accountName",
        "toAccountName",
        "remark",
        "fee",
        "bookName",
        "relatedBillId",
        "excludeFromStats"
    )

    fun export(bills: List<Bill>, outputStream: OutputStream) {
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF")
            writer.write(header.joinToString(","))
            writer.newLine()
            for (bill in bills) {
                val row = listOf(
                    sdf.format(Date(bill.time)),
                    bill.id.toString(),
                    bill.type.toString(),
                    bill.subType.toString(),
                    bill.amount.toString(),
                    bill.originalAmount.toString(),
                    bill.currency,
                    bill.exchangeRate.toString(),
                    csvEscape(bill.categoryName),
                    csvEscape(bill.accountName),
                    csvEscape(bill.toAccountName),
                    csvEscape(bill.remark),
                    bill.fee.toString(),
                    csvEscape(bill.bookName),
                    bill.relatedBillId?.toString().orEmpty(),
                    if (bill.excludeFromStats) "1" else "0"
                )
                writer.write(row.joinToString(","))
                writer.newLine()
            }
        }
    }

    fun import(inputStream: InputStream, fallbackBookName: String? = null): List<Bill> {
        val lines = readAllLinesWithFallback(inputStream)
        if (lines.isEmpty()) return emptyList()

        val headerLine = lines.first().trimStart('\uFEFF')
        val headers = parseCsvRow(headerLine).map { it.trim().trim('"') }

        return if (isQianJiHeader(headers)) {
            importQianJi(lines, headers, fallbackBookName)
        } else {
            importFlipCsv(lines, headers, fallbackBookName)
        }
    }

    private fun importFlipCsv(
        lines: List<String>,
        headers: List<String>,
        fallbackBookName: String?
    ): List<Bill> {
        fun idx(name: String) = headers.indexOf(name)

        val iTime = idx("time")
        val iId = idx("id")
        val iType = idx("type")
        val iSubType = idx("subType")
        val iAmount = idx("amount")
        val iOriginalAmount = idx("originalAmount")
        val iCurrency = idx("currency")
        val iExchangeRate = idx("exchangeRate")
        val iCategoryName = idx("categoryName")
        val iAccountName = idx("accountName")
        val iToAccountName = idx("toAccountName")
        val iRemark = idx("remark")
        val iFee = idx("fee")
        val iBookName = idx("bookName")
        val iRelatedBillId = idx("relatedBillId")
        val iExcludeFromStats = idx("excludeFromStats")

        val result = mutableListOf<Bill>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvRow(line)
            if (cols.size < headers.size.coerceAtMost(4)) continue

            runCatching {
                val timeMs = if (iTime >= 0) {
                    val raw = cols[iTime]
                    raw.toLongOrNull() ?: sdf.parse(raw)?.time ?: return@runCatching
                } else {
                    return@runCatching
                }

                val importedId = if (iId >= 0) cols[iId].toLongOrNull() ?: 0L else 0L
                val rawType = if (iType >= 0) cols[iType].toIntOrNull() ?: 0 else 0
                val rawSubType = if (iSubType >= 0) {
                    cols[iSubType].toIntOrNull() ?: Bill.SUBTYPE_NORMAL
                } else {
                    Bill.SUBTYPE_NORMAL
                }
                val remark = if (iRemark >= 0) cols[iRemark] else ""
                val normalizedType = normalizeImportedTypeAndSubtype(rawType, rawSubType, remark)
                val amount = if (iAmount >= 0) cols[iAmount].toDoubleOrNull() ?: return@runCatching else return@runCatching
                val originalAmount = if (iOriginalAmount >= 0) cols[iOriginalAmount].toDoubleOrNull() ?: amount else amount
                val currency = if (iCurrency >= 0) cols[iCurrency] else "CNY"
                val exchangeRate = if (iExchangeRate >= 0) cols[iExchangeRate].toDoubleOrNull() else null
                val categoryName = if (iCategoryName >= 0) cols[iCategoryName] else ""
                val accountName = if (iAccountName >= 0) cols[iAccountName] else ""
                val toAccountName = if (iToAccountName >= 0) cols[iToAccountName] else ""
                val fee = if (iFee >= 0) cols[iFee].toDoubleOrNull() ?: 0.0 else 0.0
                val bookNameFromCsv = if (iBookName >= 0) cols[iBookName] else ""
                val relatedBillId = if (iRelatedBillId >= 0) cols[iRelatedBillId].toLongOrNull() else null
                val excludeFromStats = if (iExcludeFromStats >= 0) {
                    cols[iExcludeFromStats].let { it == "1" || it.equals("true", ignoreCase = true) }
                } else {
                    normalizedType.excludeFromStats
                }

                result += Bill(
                    id = importedId,
                    type = normalizedType.type,
                    subType = normalizedType.subType,
                    amount = amount,
                    originalAmount = originalAmount,
                    currency = currency,
                    exchangeRate = exchangeRate ?: estimateExchangeRate(currency),
                    categoryName = CategoryNameNormalizer.normalizeForStorage(categoryName),
                    accountName = accountName,
                    toAccountName = toAccountName,
                    time = timeMs,
                    remark = remark,
                    fee = fee,
                    bookName = bookNameFromCsv.ifBlank { normalizeFallbackBookName(fallbackBookName) },
                    relatedBillId = relatedBillId,
                    excludeFromStats = excludeFromStats
                )
            }
        }
        return result
    }

    private fun importQianJi(
        lines: List<String>,
        headers: List<String>,
        fallbackBookName: String?
    ): List<Bill> {
        fun idx(name: String) = headers.indexOf(name)

        val iTime = idx("时间")
        val iCat1 = idx("分类")
        val iCat2 = idx("二级分类")
        val iType = idx("类型")
        val iAmount = idx("金额")
        val iCurrency = idx("币种")
        val iAccount1 = idx("账户1")
        val iAccount2 = idx("账户2")
        val iRemark = idx("备注")
        val iFee = idx("手续费")

        val result = mutableListOf<Bill>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = parseCsvRow(line)
            if (cols.size < headers.size.coerceAtMost(6)) continue

            runCatching {
                val qianJiType = cols.getOrNull(iType)?.trim().orEmpty()
                val mappedType = qianJiTypeMap[qianJiType] ?: return@runCatching
                val timeMs = parseTimeFlexible(cols.getOrNull(iTime)?.trim().orEmpty()) ?: return@runCatching
                val amount = abs(cols.getOrNull(iAmount)?.trim()?.toDoubleOrNull() ?: return@runCatching)
                val fee = abs(cols.getOrNull(iFee)?.trim()?.toDoubleOrNull() ?: 0.0)
                val currency = cols.getOrNull(iCurrency)?.trim().takeUnless { it.isNullOrBlank() } ?: "CNY"
                val cat1 = cols.getOrNull(iCat1)?.trim().orEmpty()
                val cat2 = cols.getOrNull(iCat2)?.trim().orEmpty()
                val categoryName = if (cat2.isNotBlank()) "$cat1 - $cat2" else cat1
                val accountName = cols.getOrNull(iAccount1)?.trim().orEmpty()
                val toAccountName = cols.getOrNull(iAccount2)?.trim().orEmpty()
                val remark = cols.getOrNull(iRemark)?.trim().orEmpty()
                val normalizedType = normalizeImportedTypeAndSubtype(mappedType.type, mappedType.subType, remark)

                result += Bill(
                    id = 0L,
                    type = normalizedType.type,
                    subType = normalizedType.subType,
                    amount = amount,
                    originalAmount = amount,
                    currency = currency,
                    exchangeRate = estimateExchangeRate(currency),
                    categoryName = CategoryNameNormalizer.normalizeForStorage(categoryName),
                    accountName = accountName,
                    toAccountName = toAccountName,
                    time = timeMs,
                    remark = remark,
                    fee = fee,
                    bookName = normalizeFallbackBookName(fallbackBookName),
                    excludeFromStats = mappedType.excludeFromStats
                )
            }
        }
        return result
    }

    private fun normalizeImportedTypeAndSubtype(rawType: Int, rawSubType: Int, remark: String): ImportedBillType {
        val normalized = when {
            rawType == Bill.TYPE_REPAYMENT || rawSubType == Bill.SUBTYPE_REPAYMENT ->
                ImportedBillType(Bill.TYPE_TRANSFER, Bill.SUBTYPE_REPAYMENT)
            // 兼容旧平账记录：subType=3 转为 SUBTYPE_NORMAL + excludeFromStats=false
            rawSubType == Bill.SUBTYPE_BALANCE_ADJUSTMENT ->
                ImportedBillType(
                    if (rawType in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) rawType else Bill.TYPE_EXPENSE,
                    Bill.SUBTYPE_NORMAL,
                    excludeFromStats = false
                )
            // 兼容旧平账记录：subType=4 转为 SUBTYPE_NORMAL + excludeFromStats=true
            rawSubType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED ->
                ImportedBillType(
                    if (rawType in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) rawType else Bill.TYPE_EXPENSE,
                    Bill.SUBTYPE_NORMAL,
                    excludeFromStats = true
                )
            rawType in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER) ->
                ImportedBillType(rawType, rawSubType)
            else ->
                ImportedBillType(Bill.TYPE_EXPENSE, rawSubType)
        }
        if (normalized.type == Bill.TYPE_INCOME &&
            normalized.subType == Bill.SUBTYPE_NORMAL &&
            isRefundLikeRemark(remark)
        ) {
            return ImportedBillType(Bill.TYPE_INCOME, Bill.SUBTYPE_REFUND)
        }
        return normalized
    }

    private fun isRefundLikeRemark(remark: String): Boolean {
        val text = remark.trim()
        if (text.isBlank()) return false
        // P2-21: 勿用 contains("退款")，会误伤「咨询退款政策」等备注
        return text.startsWith("[退款]") ||
            text.startsWith("【退款】") ||
            text.startsWith("退款")
    }

    private fun estimateExchangeRate(currency: String): Double {
        return if (currency.equals("CNY", ignoreCase = true)) {
            1.0
        } else {
            val rateToCny = CurrencyManager.getRate(currency) ?: 1.0
            if (rateToCny != 0.0) 1.0 / rateToCny else 1.0
        }
    }

    private fun isQianJiHeader(headers: List<String>): Boolean {
        val headerSet = headers.map { it.trim().trim('"') }.toSet()
        return headerSet.contains("时间") &&
            headerSet.contains("类型") &&
            headerSet.contains("金额") &&
            headerSet.contains("账户1")
    }

    private fun parseTimeFlexible(raw: String): Long? {
        val text = raw.trim()
        if (text.isBlank()) return null
        text.toLongOrNull()?.let { return it }
        return sdf.parse(text)?.time ?: sdfDateOnly.parse(text)?.time
    }

    private fun normalizeFallbackBookName(bookName: String?): String {
        return bookName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: BookAccountManager.DEFAULT_BOOK
    }

    private fun readAllLinesWithFallback(inputStream: InputStream): List<String> {
        val bytes = inputStream.readBytes()
        val text = decodeCsvText(bytes)
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
    }

    private fun decodeCsvText(bytes: ByteArray): String {
        val utf8 = runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        return runCatching { String(bytes, charset("GB18030")) }
            .getOrElse { String(bytes) }
    }

    private fun parseCsvRow(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && !inQuotes -> inQuotes = true
                char == '"' && inQuotes -> {
                    if (index + 1 < line.length && line[index + 1] == '"') {
                        current.append('"')
                        index++
                    } else {
                        inQuotes = false
                    }
                }
                char == ',' && !inQuotes -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        result += current.toString()
        return result
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}

