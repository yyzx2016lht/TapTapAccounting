package com.taostudio.tapaccounting

/** 解析后的单条日志，供查看器过滤与展示。 */
data class LogEntry(
    val time: String,
    val level: Logger.Level?,
    val tag: String,
    val message: String,
) {
    val levelCode: Char get() = level?.code ?: 'D'

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        return message.contains(query, ignoreCase = true) ||
            tag.contains(query, ignoreCase = true) ||
            time.contains(query, ignoreCase = true)
    }
}

object LogParser {
    private val newFormat = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\|([EWID])\|([^|]*)\|(.*)$""")
    private val oldFormat = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})] \[([^]]+)] (.*)$""")
    private val crashHead = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})] \[CRASH] (.*)$""")

    /**
     * 把文件尾部文本切成条目。多行（续行以两空格或无时间戳开头）并入上一条。
     */
    fun parse(text: String): List<LogEntry> {
        val lines = text.replace("\r\n", "\n").lineSequence()
        val entries = ArrayList<LogEntry>()
        var cur: LogEntry? = null
        val buf = StringBuilder()

        fun flush() {
            val c = cur ?: return
            entries.add(c.copy(message = buf.toString()))
            cur = null
            buf.setLength(0)
        }

        for (raw in lines) {
            val line = raw.trimEnd()
            val m = newFormat.find(line)
            if (m != null) {
                flush()
                cur = LogEntry(
                    time = m.groupValues[1],
                    level = Logger.Level.fromCode(m.groupValues[2][0]),
                    tag = m.groupValues[3].ifBlank { "-" },
                    message = m.groupValues[4],
                )
                buf.append(m.groupValues[4])
                continue
            }
            val crash = crashHead.find(line)
            val old = crash ?: oldFormat.find(line)
            if (old != null) {
                flush()
                val isCrash = crash != null
                val tag = if (isCrash) "CRASH" else old.groupValues.getOrElse(2) { "-" }.ifBlank { "-" }
                val msg = if (isCrash) old.groupValues[2] else old.groupValues.getOrElse(3) { "" }
                val level = if (isCrash || msg.startsWith("🚨") || msg.contains("Fatal")) {
                    Logger.Level.ERROR
                } else {
                    null
                }
                cur = LogEntry(
                    time = old.groupValues[1],
                    level = level,
                    tag = tag,
                    message = msg,
                )
                buf.append(msg)
                continue
            }
            if (cur != null) {
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(line)
            } else if (line.isNotBlank()) {
                // 文件头残缺行：仍展示，归入无级别
                cur = LogEntry("", null, "-", line)
                buf.append(line)
            }
        }
        flush()
        return entries
    }
}
