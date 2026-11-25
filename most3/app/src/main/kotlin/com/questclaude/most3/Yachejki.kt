package com.questclaude.most3

import java.io.File

/**
 * Ячейки ручек (3.2.6, Александр 24.11: «пресеты к ручкам Моста 3 — 5 своих, сохранять, загружать, редактировать;
 * без имён, просто 1–5»). Ячейка — все ручки шейдера (Nastrojki) в файле yachejka-N (строки key=value, как «kartinka»).
 * Загрузить — поставить её ручки; редактировать — двигать ручки после загрузки и сохранить в ту же ячейку.
 * Перезапись ячейки можно отменить один раз (vernut): прежнее содержимое помнится до следующего сохранения.
 * Активная ячейка — последняя загруженная или сохранённая (файл yachejka-aktivnaya). Обычная JVM — YachejkiTest.
 */
class Yachejki(private val dir: File) {
    @Volatile var aktivnaya = 0; private set          // 0 — ни одна
    private var proshloe: Pair<Int, String?>? = null   // ячейка и что в ней было до последнего сохранения (null — пусто)

    init {
        aktivnaya = try { File(dir, AKTIVNAYA).readText().trim().toInt().takeIf { it in 1..SKOLKO } ?: 0 } catch (e: Exception) { 0 }
    }

    private fun fajl(n: Int) = File(dir, "yachejka-$n")

    fun est(n: Int): Boolean = n in 1..SKOLKO && fajl(n).isFile

    fun vzyat(n: Int): Nastrojki? = if (!est(n)) null else try { Nastrojki.izStroki(fajl(n).readText()) } catch (e: Exception) { null }

    /** Ручки ячейки [n] — или null (нет такой / пустая); ячейка становится активной. */
    fun zagruzit(n: Int): Nastrojki? = vzyat(n)?.also { postavitAktivnuyu(n) }

    /** Записать ручки в ячейку [n]; false — нет такой ячейки или не записалось. */
    @Synchronized fun sohranit(n: Int, nastr: Nastrojki): Boolean {
        if (n !in 1..SKOLKO) return false
        val f = fajl(n)
        val bylo = if (f.isFile) try { f.readText() } catch (e: Exception) { null } else null
        return try {
            dir.mkdirs()
            f.writeText(nastr.ogranichit().vStroku())
            proshloe = n to bylo
            postavitAktivnuyu(n)
            true
        } catch (e: Exception) { false }
    }

    /** Отменить последнее сохранение: вернуть ячейке прежнее (или снова сделать пустой). Номер ячейки или 0 — нечего. */
    @Synchronized fun vernut(): Int {
        val (n, bylo) = proshloe ?: return 0
        try { if (bylo == null) fajl(n).delete() else fajl(n).writeText(bylo) } catch (e: Exception) { return 0 }
        proshloe = null
        return n
    }

    /** Отличаются ли ручки [tek] от активной ячейки (подвинул после загрузки — «изменено, не сохранено»). */
    fun izmeneno(tek: Nastrojki): Boolean {
        val a = vzyat(aktivnaya) ?: return false
        return a.ogranichit().vStroku() != tek.ogranichit().vStroku()
    }

    fun json(tek: Nastrojki): String = "{\"aktivnaya\":$aktivnaya,\"izmeneno\":${izmeneno(tek)}," +
        "\"est\":[${(1..SKOLKO).joinToString(",") { if (est(it)) "1" else "0" }}],\"mozhno_vernut\":${proshloe != null}}"

    private fun postavitAktivnuyu(n: Int) {
        aktivnaya = n
        try { dir.mkdirs(); File(dir, AKTIVNAYA).writeText(n.toString()) } catch (e: Exception) { }
    }

    companion object {
        const val SKOLKO = 5
        const val AKTIVNAYA = "yachejka-aktivnaya"
    }
}
