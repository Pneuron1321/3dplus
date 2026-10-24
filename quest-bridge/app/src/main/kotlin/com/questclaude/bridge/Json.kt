package com.questclaude.bridge

/**
 * Маленький разбор JSON для сообщений страницы гостя (2.5.0): объекты, массивы, строки, числа, да/нет,
 * null. org.json из Android на обычной JVM — заглушка, а комнату гостей проверяем на столе (JsonTest).
 * Глубина и длина ограничены: сообщения приходят из интернета.
 */
object Json {
    class Oshibka(msg: String) : Exception(msg)

    const val MAKS_GLUBINA = 16

    fun razobrat(s: String): Any? {
        val p = Parser(s)
        p.probely()
        val v = p.znachenie(0)
        p.probely()
        if (p.i != s.length) throw Oshibka("лишнее после JSON")
        return v
    }

    /** Объект или null, если это не объект или JSON битый. */
    fun obekt(s: String): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        razobrat(s) as? Map<String, Any?>
    } catch (e: Oshibka) { null }

    fun Map<String, Any?>.str(k: String): String? = this[k] as? String
    fun Map<String, Any?>.bool(k: String): Boolean? = this[k] as? Boolean
    fun Map<String, Any?>.num(k: String): Double? = (this[k] as? Number)?.toDouble()
    fun Map<String, Any?>.int(k: String): Int? = num(k)?.takeIf { !it.isNaN() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
    @Suppress("UNCHECKED_CAST")
    fun Map<String, Any?>.obj(k: String): Map<String, Any?>? = this[k] as? Map<String, Any?>

    private class Parser(val s: String) {
        var i = 0

        fun probely() { while (i < s.length && s[i] in " \t\r\n") i++ }

        fun znachenie(gl: Int): Any? {
            if (gl > MAKS_GLUBINA) throw Oshibka("слишком глубоко")
            probely()
            if (i >= s.length) throw Oshibka("конец")
            return when (val c = s[i]) {
                '{' -> obekt(gl)
                '[' -> massiv(gl)
                '"' -> stroka()
                't' -> slovo("true", true)
                'f' -> slovo("false", false)
                'n' -> slovo("null", null)
                else -> if (c == '-' || c in '0'..'9') chislo() else throw Oshibka("неожиданный знак")
            }
        }

        fun slovo(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw Oshibka("ожидалось $w")
            i += w.length
            return v
        }

        fun obekt(gl: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            probely()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                probely()
                if (i >= s.length || s[i] != '"') throw Oshibka("ожидался ключ")
                val k = stroka()
                probely()
                if (i >= s.length || s[i] != ':') throw Oshibka("ожидалось :")
                i++
                m[k] = znachenie(gl + 1)
                probely()
                if (i >= s.length) throw Oshibka("конец объекта")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw Oshibka("ожидалось , или }")
                }
            }
        }

        fun massiv(gl: Int): List<Any?> {
            i++
            val a = ArrayList<Any?>()
            probely()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                a += znachenie(gl + 1)
                probely()
                if (i >= s.length) throw Oshibka("конец массива")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return a
                    else -> throw Oshibka("ожидалось , или ]")
                }
            }
        }

        fun stroka(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw Oshibka("строка не закрыта")
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw Oshibka("строка не закрыта")
                        when (val e = s[i++]) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c'); 'n' -> b.append('\n')
                            'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Oshibka("плохой \\u")
                                val h = s.substring(i, i + 4).toIntOrNull(16) ?: throw Oshibka("плохой \\u")
                                b.append(h.toChar()); i += 4
                            }
                            else -> throw Oshibka("плохой знак после \\: $e")
                        }
                    }
                    c < ' ' -> throw Oshibka("управляющий знак в строке")
                    else -> b.append(c)
                }
            }
        }

        fun chislo(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] in ".eE+-")) i++
            return s.substring(st, i).toDoubleOrNull() ?: throw Oshibka("плохое число")
        }
    }
}
