package com.questclaude.most3

/** Строка в JSON (кавычки и управляющие символы). Обычная JVM. */
object Json {
    fun str(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (ch in s) when {
            ch == '"' -> b.append("\\\"")
            ch == '\\' -> b.append("\\\\")
            ch == '\n' -> b.append("\\n")
            ch < ' ' -> b.append(String.format("\\u%04x", ch.code))
            else -> b.append(ch)
        }
        return b.append('"').toString()
    }
}

/** Байты в hex для самоотчёта. */
object Hex {
    fun str(b: ByteArray, n: Int = b.size): String {
        val s = StringBuilder(n * 3)
        for (i in 0 until minOf(n, b.size)) { if (i > 0) s.append(' '); s.append("%02x".format(b[i].toInt() and 0xff)) }
        return s.toString()
    }
}
