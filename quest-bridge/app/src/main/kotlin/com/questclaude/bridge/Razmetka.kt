package com.questclaude.bridge

/**
 * 2.8.7: место чата в правом столбце (чистая геометрия, без GL — для проверок на столе). Координаты GL: от низа кадра.
 */
object Razmetka {
    /**
     * Самый большой просвет по высоте между [niz] и [verh] в столбце x0..x1, если вычесть помехи [x, y, w, h]
     * (каждая — с запасом [zazor]). Помеха, не задевающая столбец, не мешает. Возвращает (низ, верх).
     */
    fun prosvet(niz: Int, verh: Int, x0: Int, x1: Int, pomehi: List<IntArray>, zazor: Int): Pair<Int, Int> {
        val zanyato = pomehi.filter { it[0] < x1 && it[0] + it[2] > x0 && it[3] > 0 }
            .map { (it[1] - zazor) to (it[1] + it[3] + zazor) }.sortedBy { it.first }
        var luchshe = niz to niz
        var nachalo = niz
        for ((a, b) in zanyato) {
            if (a > nachalo) { val k = minOf(a, verh); if (k - nachalo > luchshe.second - luchshe.first) luchshe = nachalo to k }
            nachalo = maxOf(nachalo, b)
            if (nachalo >= verh) break
        }
        if (verh - nachalo > luchshe.second - luchshe.first) luchshe = nachalo to verh
        return luchshe
    }

    /** Насколько поднять сообщения высотой [vysota] над низом просвета высотой [prosvet], чтобы стояли посередине. */
    fun poSeredine(prosvet: Int, vysota: Int): Int = if (vysota <= 0 || vysota >= prosvet) 0 else (prosvet - vysota) / 2
}
