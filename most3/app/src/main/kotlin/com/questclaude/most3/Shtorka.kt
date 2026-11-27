package com.questclaude.most3

/**
 * Когда прятать шторку внизу окна (кнопки и ручки) — 3.3.2, Александр 26.11: «регулирую ручки, шторка сворачивается
 * через этот тайминг; хочу, когда снова нажимаю по окну, пусть открываются те же ручки, что были перед этим. Пока
 * курсор на шторке — чтобы вообще не скрывалась. Когда курсор не на шторке — скрывалась в 2 раза быстрее».
 *  - ruchki — ручки открыты кнопкой «Ручки»: шторка спряталась — при следующем касании окна они снова тут, с той же
 *    выбранной ручкой (MainActivity.vybrana); закрыть — той же кнопкой;
 *  - курсор (луч контроллера / рука) на шторке — не прятать совсем; ушёл с неё — спрятать через BYSTRO_MS (2,5 с);
 *    водит по картинке — время не продлевается;
 *  - пока шлем не прислал ни одного наведения (не знаем, где курсор), — как было: через OBYCHNO_MS (5 с) после касания.
 * Обычная JVM, без Android — ShtorkaTest.
 */
class Shtorka {
    var ruchki = false; private set
    var kursorEst = false; private set           // шлем шлёт наведение — тогда знаем, где курсор
    var kursorNaShtorke = false; private set

    fun pereklyuchitRuchki(): Boolean { ruchki = !ruchki; return ruchki }

    /** Через сколько мс спрятать; null — не прятать (курсор на шторке). */
    fun pryatatCherez(): Long? = if (kursorNaShtorke) null else if (kursorEst) BYSTRO_MS else OBYCHNO_MS

    /** Курсор навели (vnutri — над шторкой). true — перешёл со шторки на картинку или обратно: время заново. */
    fun navedenie(vnutri: Boolean): Boolean {
        kursorEst = true
        val bylo = kursorNaShtorke
        kursorNaShtorke = vnutri
        return bylo != vnutri
    }

    /** Курсор ушёл из окна (или нажимает — тогда следом придёт kasanie). */
    fun ushyol() { kursorNaShtorke = false }

    /** Касание (vnutri — по шторке). Без наведения не знаем, где курсор потом, — считаем, что не на шторке. */
    fun kasanie(vnutri: Boolean) { kursorNaShtorke = kursorEst && vnutri }

    companion object {
        const val OBYCHNO_MS = 5000L
        const val BYSTRO_MS = 2500L
    }
}
