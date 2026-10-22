package com.questclaude.most3

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Пульт Моста 3 для Плюса: HTTP на 127.0.0.1:8767 (мост 2 — 8766). Только шлем, наружу не слушает.
 *   GET /status                       — что идёт: режим, к/с, звук, картинка, авто-ночь
 *   GET /podklyuchit                  — открыть карту и пустить видео и звук
 *   GET /kartinka?yarkost=+0.1&…      — шейдер: yarkost kontrast nasyshch gamma teni rezkost avtonoch (N, +N, -N, def)
 *   GET /kartinka?nabor=noch          — набор: obychno monitor noch yarko rezko
 *   GET /zvuk?vkl=0|1&g=80            — звук карты в шлем, громкость в %
 *   GET /karta/ruchki?yarkost=N&…     — ручки внутри карты (UVC): yarkost kontrast ton nasyshchennost
 *   GET /rezhimy                      — что умеет карта (текст)
 *   GET /klip?sek=60                  — последние N секунд игры в MP4 (галерея, Movies/Plus); ?vkl=0|1 — запись в круг
 *   GET /rezhim?profil=maks|n=3       — профиль (efir plavno maks 4k hdr) или режим по номеру из /rezhimy; переподключение
 *   GET /diagnoz                      — самоотчёт первого запуска (текст; первая строка — итог)
 */
class Server(private val port: Int) {
    private var ss: ServerSocket? = null

    fun start() {
        val s = try { ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { return }
        ss = s
        Thread({ while (!s.isClosed) { val k = try { s.accept() } catch (e: Exception) { break }; Thread { obrabotat(k) }.start() } },
            "most3-pult").apply { isDaemon = true; start() }
    }

    private fun obrabotat(k: Socket): Unit = k.use {
        k.soTimeout = 5000
        val stroka = k.getInputStream().bufferedReader().readLine() ?: return
        val cel = stroka.split(' ').getOrNull(1) ?: "/"
        val put = cel.substringBefore('?')
        val q = Pult.zapros(cel.substringAfter('?', ""))
        val (kod, telo) = try { otvet(put, q) } catch (e: Exception) { 500 to "{\"oshibka\":${Json.str(e.toString())}}" }
        val b = telo.toByteArray()
        val tip = if (telo.startsWith("{")) "application/json; charset=utf-8" else "text/plain; charset=utf-8"
        k.getOutputStream().apply {
            write("HTTP/1.0 $kod OK\r\nContent-Type: $tip\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(b); flush()
        }
        Unit
    }

    private fun otvet(put: String, q: Map<String, String>): Pair<Int, String> = when (put) {
        "/status", "/" -> 200 to Most.status()
        "/podklyuchit" -> 200 to "{\"otvet\":${Json.str(Most.app?.let { Most.podklyuchit(it) } ?: "мост не запущен")}}"
        "/kartinka" -> {
            val n = Pult.kartinka(Most.nastrojki, q) ?: return 400 to "{\"oshibka\":\"не понял: ${q.keys.joinToString()}\"}"
            Most.postavit(n); 200 to Most.nastrojkiJson()
        }
        "/zvuk" -> {
            q["vkl"]?.let { Most.zvuk(it == "1" || it == "vkl") }
            q["g"]?.trimEnd('%')?.toFloatOrNull()?.let { Most.gromkost = it / 100f }
            200 to (Most.zvuk?.status() ?: "{\"idyot\":false,\"vkl\":${Most.zvukVkl}}")
        }
        "/karta/ruchki" -> 200 to ruchki(q)
        "/klip" -> {
            q["vkl"]?.let { Most.klip(it == "1" || it == "vkl") }
            if (q.containsKey("sek") || q.isEmpty()) 200 to Most.sohranitKlip(q["sek"]?.toIntOrNull() ?: 150)
            else 200 to (Most.klip?.status() ?: "{\"idyot\":false,\"vkl\":${Most.klipVkl}}")
        }
        "/rezhim" -> {
            q["profil"]?.let { p -> val pr = Profil.po(p) ?: return 400 to "{\"oshibka\":\"нет профиля $p: efir plavno maks 4k hdr\"}"
                Most.smenitProfil(pr); return 200 to "{\"otvet\":${Json.str("профиль: ${pr.nadpis}; переподключаю")}}" }
            q["n"]?.toIntOrNull()?.let { return 200 to "{\"otvet\":${Json.str(Most.vybratRezhim(it))}}" }
            200 to "{\"profil\":\"${Most.profil.imya}\",\"nadpis\":${Json.str(Most.profil.nadpis)}}"
        }
        "/diagnoz" -> 200 to (Most.app?.let { Most.diagnoz(it) } ?: "мост не запущен")
        "/rezhimy" -> 200 to (Most.karta?.let { Most.opisanieKarty(it) }
            ?: Most.app?.let { File(it.filesDir, "karta-rezhimy.txt").takeIf { f -> f.exists() }?.readText() } ?: "карта ещё не открывалась")
        else -> 404 to "{\"oshibka\":\"нет такого: $put\"}"
    }

    private fun ruchki(q: Map<String, String>): String {
        val k = Most.karta ?: return "{\"oshibka\":\"карта не открыта\"}"
        for ((imya, v) in q) {
            if (imya !in Karta.RUCHKI) continue
            val d = k.diapazon(imya) ?: continue
            val novoe = if (v == "def") d.def else Pult.chislo(v, d.cur) ?: continue
            k.ustanovit(imya, novoe.coerceIn(d.min, d.max))
        }
        return Karta.RUCHKI.keys.joinToString(",", "{", "}") { imya ->
            val d = k.diapazon(imya)
            "\"$imya\":" + (d?.let { "{\"cur\":${it.cur},\"min\":${it.min},\"max\":${it.max},\"def\":${it.def}}" } ?: "null")
        }
    }
}

/** Разбор команд пульта — обычная JVM (PultTest). */
object Pult {
    fun zapros(s: String): Map<String, String> = s.split('&').mapNotNull {
        val kv = it.split('=', limit = 2)
        if (kv.size == 2 && kv[0].isNotEmpty()) kv[0] to dekod(kv[1]) else null
    }.toMap()

    /** «+» в адресе обычно пробел; у нас «+0.1» — шаг, поэтому плюс не трогаем, а %2B раскодируем. */
    private fun dekod(v: String): String = try { URLDecoder.decode(v.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { v }

    fun chislo(v: String, tek: Int): Int? = when {
        v.startsWith("+") || v.startsWith("-") -> v.drop(1).toIntOrNull()?.let { if (v[0] == '+') tek + it else tek - it }
        else -> v.removePrefix("=").toIntOrNull()
    }

    /** Новые настройки шейдера по запросу; null — что-то не понято (тогда ничего не меняем). */
    fun kartinka(n: Nastrojki, q: Map<String, String>): Nastrojki? {
        var r = n
        q["nabor"]?.let { r = NABORY[it] ?: return null }
        for ((imya, v) in q) {
            if (imya == "nabor") continue
            r = r.izmenit(imya, v) ?: return null
        }
        return r.ogranichit()
    }
}
