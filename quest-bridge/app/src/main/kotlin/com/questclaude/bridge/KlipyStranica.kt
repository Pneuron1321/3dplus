package com.questclaude.bridge

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Страница «Клипы» для айфона (2.7.0): `klipy` (Плюсу: «открой клипы») или кнопка «КЛИПЫ» открывают её
 * на 15 минут по домашней Wi-Fi — http://<шлем>:8772/k/<ключ>/. В Safari: клипы из галереи шлема
 * «Фильмы/Plus» (игра — Мост 3, камера — мост 2), новые сверху, с кадром, длительностью и размером;
 * смотреть (video) и «Скачать» (attachment; Range — Safari тянет большие файлы кусками).
 * Ключ — одноразовый, на одно открытие (секрет, как ссылка гостя): адрес целиком только на экране
 * приложения 2 и только без эфира (экран — в кадре); в /klipy, Termux и голос он не уходит.
 * По истечении ключ мёртв (404), порт закрыт. Только чтение и только клипы из списка галереи:
 * в адресе — номер клипа, не путь. Обычная JVM — проверяется на столе (KlipyStranicaTest).
 */
class KlipyStranica(
    private val galereya: Galereya,
    private val ip: () -> String?,
    private val port: Int = PORT,
    private val bind: InetAddress? = null,                  // null — все сети шлема; в тестах — 127.0.0.1
    private val chasy: () -> Long = { System.currentTimeMillis() },
    private val noviyKlyuch: () -> String = { sozdatKlyuch() },
    private val zona: TimeZone = TimeZone.getDefault(),
) : KlipyStranica.Upr {

    /** Для /klipy… (FrameServer): ответы JSON без ключа. */
    interface Upr {
        fun status(): String
        fun otkryt(): String
        fun zakryt(): String
    }

    /** Клип галереи: id — номер в MediaStore; ms — когда сохранён; dlitMs 0 — не знаем. */
    class Klip(val id: Long, val imya: String, val ms: Long, val dlitMs: Long, val bait: Long)

    /** Открытый файл клипа: длина и чтение с места (Range). */
    interface Fayl : Closeable {
        val dlina: Long
        fun s(ot: Long): InputStream
    }

    /** Галерея «Фильмы/Plus» (MediaStore — KlipyGalereya; в тестах — подделка). */
    interface Galereya {
        /** Только клипы папки Movies/Plus, готовые (не IS_PENDING). */
        fun spisok(): List<Klip>
        fun otkryt(k: Klip): Fayl?
        /** Кадр клипа JPEG (небольшой) или null. */
        fun prevyu(k: Klip): ByteArray?
        /** Видны ли клипы чужого приложения (Мост 3): нужно разрешение «Видео». */
        val vidnoVse: Boolean get() = true
    }

    /** Ответ: заголовки и тело — байты или кусок файла [ot, ot+dlina). */
    class Otvet(val kod: Int, val tip: String, val telo: ByteArray = ByteArray(0), val zagolovki: List<String> = emptyList(),
                val fayl: Fayl? = null, val ot: Long = 0, val dlina: Long = telo.size.toLong())

    @Volatile private var klyuch: String? = null
    @Volatile private var doMs = 0L
    @Volatile private var server: ServerSocket? = null
    @Volatile private var priyom: Thread? = null
    private val chuzhie = HashSet<String>()                // разные неверные ключи за это открытие (старая вкладка — один)
    @Volatile var zakrytaPodbor = false                     // закрыта: перебирали ключ
        private set
    private val soedineniy = AtomicInteger()
    @Volatile private var spisokKesh: Pair<Long, List<Klip>>? = null
    private val prevyuKesh = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ByteArray>?) = size > PREVYU_KESH
    }

    val otkryta: Boolean get() = klyuch != null && chasy() < doMs

    /** Видны ли клипы игры (разрешение «Видео»). */
    val igraVidna: Boolean get() = galereya.vidnoVse

    /** Секунд до закрытия (0 — закрыта). */
    val ostalos: Int get() = if (otkryta) ((doMs - chasy() + 999) / 1000).toInt() else 0

    /** Адрес с ключом — только для экрана приложения 2 (не в JSON, не в журнал). */
    fun adres(): String? {
        val k = klyuch ?: return null
        if (!otkryta) return null
        return "http://${ip() ?: return null}:$port/k/$k/"
    }

    /** Адрес со скрытым ключом — для экрана во время эфира. */
    fun adresSkryt(): String? = adres()?.let { a -> a.substringBefore("/k/") + "/k/" + "•".repeat(klyuch?.length ?: 8) + "/" }

    /** Открыть на sek секунд (или продлить: ключ тот же, пока открыта). */
    @Synchronized fun otkrytNa(sek: Int = SEK, soket: Boolean = true) {
        if (!otkryta) {
            klyuch = noviyKlyuch()
            synchronized(chuzhie) { chuzhie.clear() }
            zakrytaPodbor = false
            synchronized(prevyuKesh) { prevyuKesh.clear() }
        }
        doMs = chasy() + sek * 1000L
        if (!soket || server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bind, port))
        server = s
        priyom = thread(name = "klipy-stranica", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                if (soedineniy.incrementAndGet() > MAKS_SOEDINENIY) {
                    soedineniy.decrementAndGet(); try { c.close() } catch (e: IOException) { }; continue
                }
                thread(name = "klipy-otvet", isDaemon = true) {
                    try { c.use { obsluzhit(it) } } catch (e: Exception) { } finally { soedineniy.decrementAndGet() }
                }
            }
        }
        thread(name = "klipy-chasy", isDaemon = true) {
            while (!s.isClosed && !zakrytEsliIstekla(s)) Thread.sleep(250)
        }
    }

    /** Закрыть: ключ умирает сразу. */
    @Synchronized fun zakrytVse() {
        klyuch = null; doMs = 0
        server?.let { zakrytSoket(it) }
    }

    /** Срок вышел — закрыть порт (true); продлили в последний миг — нет. */
    @Synchronized private fun zakrytEsliIstekla(s: ServerSocket): Boolean {
        if (otkryta) return false
        zakrytSoket(s)
        return true
    }

    @Synchronized private fun zakrytSoket(s: ServerSocket) {
        try { s.close() } catch (e: IOException) { }
        priyom?.takeIf { it !== Thread.currentThread() }?.join(1000)
        if (server === s) { server = null; klyuch = null; doMs = 0 }
    }

    // ---------- для /klipy (без ключа) ----------

    override fun status(): String {
        val n = try { spisok().size } catch (e: Exception) { -1 }
        val a = ip()
        return "{\"otkryta\":$otkryta,\"sek\":$ostalos,\"ip\":${a?.let { json(it) } ?: "null"},\"port\":$port," +
            "\"klipov\":$n,\"igra_vidna\":${galereya.vidnoVse},\"podbor\":$zakrytaPodbor}"
    }

    override fun otkryt(): String {
        if (ip() == null) return "{\"oshibka\":\"шлем не в Wi-Fi\"}"
        try { otkrytNa() } catch (e: Exception) {
            return "{\"oshibka\":${json("страница не открылась: ${e.message}")}}"
        }
        return status().replaceFirst("{", "{\"ok\":true,")
    }

    override fun zakryt(): String {
        zakrytVse()
        return "{\"ok\":true,\"otkryta\":false}"
    }

    // ---------- HTTP ----------

    private fun spisok(): List<Klip> {
        val k = spisokKesh
        val t = chasy()
        if (k != null && t - k.first < SPISOK_MS && t >= k.first) return k.second
        return galereya.spisok().sortedByDescending { it.ms }.also { spisokKesh = t to it }
    }

    private fun obsluzhit(c: Socket) {
        c.soTimeout = 10_000
        c.tcpNoDelay = true
        val inp = c.getInputStream()
        val z = chitatZagolovki(inp) ?: return
        val chasti = z.first().split(' ')
        if (chasti.size < 3) return
        val range = z.drop(1).firstOrNull { it.startsWith("range:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val o = obrabotat(chasti[0], chasti[1], range)
        try { otpravit(c.getOutputStream(), o, chasti[0] == "HEAD") } finally { o.fayl?.close() }
    }

    /**
     * Логика ответа (без сокета — для тестов): метод, путь запроса, заголовок Range.
     * /k/<ключ>/ — страница; p/<id>.jpg — кадр; v/<id>.mp4 — смотреть; d/<id>.mp4 — скачать.
     */
    fun obrabotat(metod: String, zapros: String, range: String? = null): Otvet {
        if (metod != "GET" && metod != "HEAD") return tekst(405, "GET only")
        val put = zapros.substringBefore('?')
        if (!put.startsWith("/k/")) return net()
        val ost = put.removePrefix("/k/")
        val k = ost.substringBefore('/')
        val kl = klyuch
        if (kl == null || !otkryta || !MessageDigest.isEqual(k.toByteArray(), kl.toByteArray())) {
            if (kl != null && otkryta && synchronized(chuzhie) { chuzhie.add(k.take(40)); chuzhie.size >= MAKS_CHUZHIH }) {
                zakrytaPodbor = true
                thread(isDaemon = true) { zakrytVse() }    // перебирают ключ — закрыть совсем
            }
            return net()
        }
        if (!ost.contains('/')) return Otvet(302, "text/plain", zagolovki = listOf("Location: /k/$k/"))
        val dal = ost.substringAfter('/')
        if (dal == "" || dal == "index.html") return Otvet(200, "text/html; charset=utf-8", html(spisok()).toByteArray(Charsets.UTF_8))
        val m = MARSHRUT.matchEntire(dal) ?: return net()
        val id = m.groupValues[2].toLongOrNull() ?: return net()
        val klip = spisok().firstOrNull { it.id == id } ?: return net()   // только из списка: путей нет
        return when (m.groupValues[1]) {
            "p" -> prevyu(klip)?.let { Otvet(200, "image/jpeg", it, listOf("Cache-Control: private, max-age=900")) } ?: net()
            else -> fayl(klip, range, skachat = m.groupValues[1] == "d")
        }
    }

    private fun prevyu(k: Klip): ByteArray? {
        val kl = "${k.id}:${k.bait}"
        synchronized(prevyuKesh) { prevyuKesh[kl]?.let { return it } }
        val b = try { galereya.prevyu(k) } catch (e: Exception) { null } ?: return null
        synchronized(prevyuKesh) { prevyuKesh[kl] = b }
        return b
    }

    private fun fayl(k: Klip, range: String?, skachat: Boolean): Otvet {
        val f = try { galereya.otkryt(k) } catch (e: Exception) { null } ?: return net()
        val n = f.dlina
        val dop = mutableListOf("Accept-Ranges: bytes", "Cache-Control: no-store")
        if (skachat) dop += "Content-Disposition: " + vlozhenie(k.imya)
        val d = diapazon(range, n)
        return when {
            d == null -> Otvet(200, "video/mp4", zagolovki = dop, fayl = f, ot = 0, dlina = n)
            d.isEmpty() -> { f.close(); Otvet(416, "text/plain", zagolovki = dop + "Content-Range: bytes */$n") }
            else -> Otvet(206, "video/mp4", zagolovki = dop + "Content-Range: bytes ${d.first}-${d.last}/$n",
                fayl = f, ot = d.first, dlina = d.last - d.first + 1)
        }
    }

    private fun net() = tekst(404, "Страница закрыта: скажи Плюсу «открой клипы» и открой новый адрес с экрана моста.")

    private fun tekst(kod: Int, t: String) = Otvet(kod, "text/plain; charset=utf-8", t.toByteArray(Charsets.UTF_8))

    private fun otpravit(out: OutputStream, o: Otvet, head: Boolean) {
        val prichina = when (o.kod) {
            200 -> "OK"; 206 -> "Partial Content"; 302 -> "Found"; 405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"; else -> "Not Found"
        }
        val kesh = if (o.zagolovki.any { it.startsWith("Cache-Control:") }) "" else "Cache-Control: no-store\r\n"
        val zag = "HTTP/1.1 ${o.kod} $prichina\r\nContent-Type: ${o.tip}\r\nContent-Length: ${o.dlina}\r\n" +
            o.zagolovki.joinToString("") { "$it\r\n" } + kesh +
            "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nX-Robots-Tag: noindex\r\nConnection: close\r\n\r\n"
        val bo = java.io.BufferedOutputStream(out, 65536)
        bo.write(zag.toByteArray(Charsets.ISO_8859_1))
        if (!head) {
            val f = o.fayl
            if (f == null) bo.write(o.telo)
            else f.s(o.ot).use { s ->
                val buf = ByteArray(65536)
                var ost = o.dlina
                while (ost > 0) {
                    val r = s.read(buf, 0, minOf(buf.size.toLong(), ost).toInt())
                    if (r < 0) break
                    bo.write(buf, 0, r)
                    ost -= r
                }
            }
        }
        bo.flush()
    }

    /** Заголовки до пустой строки; длиннее 16 КБ — нет. */
    private fun chitatZagolovki(inp: InputStream): List<String>? {
        val stroki = ArrayList<String>()
        val b = StringBuilder()
        var vsego = 0
        while (true) {
            val x = inp.read()
            if (x < 0) return null
            if (++vsego > 16_384) return null
            if (x == '\n'.code) {
                val l = b.toString().trimEnd('\r')
                b.setLength(0)
                if (l.isEmpty()) return if (stroki.isEmpty()) null else stroki
                stroki += l
            } else b.append(x.toChar())
        }
    }

    // ---------- страница ----------

    fun html(klipy: List<Klip>): String {
        val do_ = Calendar.getInstance(zona).apply { timeInMillis = doMs }
        val doCh = "%d:%02d".format(do_.get(Calendar.HOUR_OF_DAY), do_.get(Calendar.MINUTE))
        val karty = klipy.joinToString("\n") { k ->
            val (kto, cls) = kto(k.imya)
            val dl = if (k.dlitMs > 0) "<span class=\"dl\">${dlitelnost(k.dlitMs)}</span>" else ""
            """<div class="k"><div class="kadr" onclick="igrat(this,${k.id})"><img src="p/${k.id}.jpg" alt="" loading="lazy" onerror="this.remove()"><span class="pl">▶</span>$dl</div>
<div class="niz"><div class="o"><div class="z"><b class="$cls">$kto</b> · ${data(k.ms)}</div><div class="m">${listOfNotNull(if (k.dlitMs > 0) dlitelnost(k.dlitMs) else null, razmer(k.bait)).joinToString(" · ")}</div></div>
<a class="s" href="d/${k.id}.mp4" download>Скачать</a></div></div>"""
        }
        val pusto = if (klipy.isEmpty()) "<p class=\"pusto\">Клипов пока нет. Скажи Плюсу «сохрани клип» — клип игры или камеры появится здесь.</p>" else ""
        val igra = if (galereya.vidnoVse) "" else "<p class=\"vn\">Клипы игры (Мост 3) не видны: мосту 2 нужен доступ к видео. В приложении 2 нажми «КЛИПЫ» и «Разрешить».</p>"
        return """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="referrer" content="no-referrer"><meta name="robots" content="noindex"><meta name="color-scheme" content="dark">
<meta name="theme-color" content="#0E1014"><title>Клипы Плюса</title>
<style>
:root{--g:#0E1014;--g2:#171A20;--z:#F2C230;--l:#EEF2F7;--a:#FF4A2B;--t:#8B93A1}
*{box-sizing:border-box}
body{margin:0;background:var(--g);color:var(--l);font:18px/1.4 -apple-system,system-ui,sans-serif;padding:20px 16px calc(40px + env(safe-area-inset-bottom))}
h1{font-size:30px;margin:0 0 4px;letter-spacing:.5px}h1 span{color:var(--z)}
.pod{color:var(--t);margin:0 0 18px;font-size:16px}
.k{background:var(--g2);border-radius:18px;overflow:hidden;margin:0 0 18px;border:1px solid #232733}
.kadr{position:relative;aspect-ratio:16/9;background:#000;cursor:pointer}
.kadr img,.kadr video{width:100%;height:100%;object-fit:contain;display:block}
.pl{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:68px;height:68px;border-radius:50%;
background:rgba(14,16,20,.72);border:2px solid var(--z);color:var(--z);font-size:28px;display:flex;align-items:center;justify-content:center;padding-left:5px}
.dl{position:absolute;right:10px;bottom:10px;background:rgba(14,16,20,.8);color:var(--l);border-radius:8px;padding:2px 8px;font-size:15px;font-variant-numeric:tabular-nums}
.niz{display:flex;align-items:center;gap:12px;padding:14px 16px}
.o{flex:1;min-width:0}.z{font-size:19px}.m{color:var(--t);font-size:16px;font-variant-numeric:tabular-nums}
b.igra{color:var(--z)}b.kam{color:var(--a)}b.dr{color:var(--l)}
a.s{flex:none;background:var(--z);color:var(--g);text-decoration:none;font-weight:700;font-size:18px;padding:14px 18px;border-radius:14px}
.pusto,.vn{background:var(--g2);border-radius:14px;padding:16px}.vn{border-left:4px solid var(--a)}
small{color:var(--t);font-size:15px;display:block;margin-top:18px}
</style></head><body>
<h1>Клипы <span>Плюса</span></h1>
<p class="pod">Новые сверху · ${klipy.size} шт. · страница открыта до $doCh (<span id="ost"></span>)</p>
$igra$pusto
$karty
<small>«Скачать» кладёт файл в «Загрузки» (приложение «Файлы»); в «Фото» — открой его там и «Поделиться → Сохранить видео».
Адрес с ключом — не пересылай: через 15 минут он перестанет работать.</small>
<script>
var konec=Date.now()+$ostalos*1000;
function t(){var s=Math.max(0,Math.round((konec-Date.now())/1000)),e=document.getElementById('ost');
e.textContent=s>0?('ещё '+Math.floor(s/60)+':'+('0'+s%60).slice(-2)):'закрыта';if(s>0)setTimeout(t,1000)}t();
function igrat(el,id){if(el.querySelector('video'))return;var v=document.createElement('video');
v.src='v/'+id+'.mp4';v.controls=true;v.playsInline=true;v.setAttribute('playsinline','');v.autoplay=true;v.preload='metadata';
el.innerHTML='';el.appendChild(v);el.onclick=null;try{v.play()}catch(e){}}
</script></body></html>"""
    }

    private fun data(ms: Long): String {
        val c = Calendar.getInstance(zona).apply { timeInMillis = ms }
        return "${c.get(Calendar.DAY_OF_MONTH)} ${MESYACY[c.get(Calendar.MONTH)]}, %d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    companion object {
        const val PORT = 8772
        /** 15 минут. */
        const val SEK = 900
        private const val MAKS_SOEDINENIY = 16
        /** Разных неверных ключей за открытие — потом закрыть (перебор). */
        const val MAKS_CHUZHIH = 20
        private const val PREVYU_KESH = 60
        private const val SPISOK_MS = 3000L
        private val MARSHRUT = Regex("([pvd])/(\\d{1,18})\\.(jpg|mp4)")
        private val MESYACY = arrayOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
        /** Как ключ камеры: без 0/o, 1/l — легко вписать с экрана шлема в Safari. */
        private const val BUKVY = "abcdefghijkmnpqrstuvwxyz23456789"

        /** 10 знаков (50 бит): адрес вписывают руками; живёт 15 минут, перебор закрывает страницу. */
        fun sozdatKlyuch(): String {
            val r = SecureRandom()
            return (1..10).map { BUKVY[r.nextInt(BUKVY.length)] }.joinToString("")
        }

        /** Кто снял: по имени файла (most3-igra-… — Мост 3, plus-klip-… — камера моста 2). */
        fun kto(imya: String): Pair<String, String> = when {
            imya.startsWith("most3-") -> "Игра" to "igra"
            imya.startsWith("plus-klip-") -> "Камера" to "kam"
            else -> "Клип" to "dr"
        }

        fun dlitelnost(ms: Long): String {
            val s = (ms + 500) / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

        fun razmer(b: Long): String = when {
            b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f ГБ", b / (1L shl 30).toDouble()).replace('.', ',')
            b >= 1L shl 20 -> "${(b + (1L shl 19)) shr 20} МБ"
            else -> "${maxOf(1, (b + 512) shr 10)} КБ"
        }

        /**
         * Range: null — весь файл (нет заголовка или непонятный — по RFC просто 200);
         * пустой — за концом файла (416); иначе ot..do включительно. Несколько кусков — только первый.
         */
        fun diapazon(h: String?, dlina: Long): LongRange? {
            if (h == null) return null
            val m = Regex("\\s*bytes\\s*=\\s*(\\d*)\\s*-\\s*(\\d*)\\s*(,.*)?").matchEntire(h) ?: return null
            val a = m.groupValues[1]; val b = m.groupValues[2]
            if (a.isEmpty() && b.isEmpty()) return null
            if (a.length > 18 || b.length > 18) return LongRange.EMPTY
            if (a.isEmpty()) {                                  // bytes=-500 — последние 500
                val n = b.toLong()
                if (n == 0L || dlina == 0L) return LongRange.EMPTY
                return maxOf(0, dlina - n)..(dlina - 1)
            }
            val ot = a.toLong()
            if (ot >= dlina) return LongRange.EMPTY
            val do_ = if (b.isEmpty()) dlina - 1 else minOf(b.toLong(), dlina - 1)
            if (do_ < ot) return null                            // bytes=5-3 — непонятный, весь файл
            return ot..do_
        }

        /** Строка JSON (как FrameServer.json — здесь своя, чтобы класс проверялся на JVM один). */
        fun json(t: String): String = buildString {
            append('"')
            for (c in t) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
            append('"')
        }

        /** Content-Disposition для «Скачать»: имя — только латиница, цифры, «.-_» и .mp4. */
        fun vlozhenie(imya: String): String {
            var s = imya.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ".-_") it else '_' }
                .joinToString("").trimStart('.').take(120)
            if (s.isEmpty() || s.all { it == '_' }) s = "klip"
            if (!s.endsWith(".mp4", ignoreCase = true)) s += ".mp4"
            return "attachment; filename=\"$s\""
        }
    }
}
