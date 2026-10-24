package com.questclaude.bridge

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Страница «подключить камеру» для айфона: кнопка в приложении 2 (или `kamera svyaz`) открывает
 * её на 2 минуты по домашней Wi-Fi — http://<шлем>:8770. В Safari на айфоне кнопки
 * «Добавить в Moblin» (moblin://?{…} — бесплатный, без водяного знака) и «Добавить в Larix»
 * (larix://set/v1?…, Larix Grove): приложение получает подключение к шлему вместе с ключом и
 * настройками видео (H.264 — другой кодек шлем не примет). Адрес с ключом вписывать руками
 * не нужно и на экране шлема (он в кадре эфира) его нет. Обычная JVM — проверяется на столе
 * (KameraStranicaTest).
 */
class KameraStranica(
    private val port: Int,
    private val rtmp: () -> String?,          // rtmp://<шлем>:1935/kamera/<ключ>
    private val bind: InetAddress? = null,    // null — все сети шлема; в тестах — 127.0.0.1
    /** Ещё страницы на то же время (2.5.0: /gosti — ссылки гостей): путь → HTML или null. */
    private val dop: (String) -> String? = { null },
    private val pri: (Boolean) -> Unit = {},  // открылась / закрылась — имя plus.local живёт столько же
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var doMs = 0L
    @Volatile private var priyom: Thread? = null

    val otkryta: Boolean get() = server != null && System.currentTimeMillis() < doMs

    /** Секунд до закрытия (0 — закрыта). */
    val ostalos: Int get() = if (otkryta) ((doMs - System.currentTimeMillis()) / 1000).toInt() else 0

    /** Открыть (или продлить) на sek секунд. */
    @Synchronized fun otkryt(sek: Int = SEK) {
        doMs = System.currentTimeMillis() + sek * 1000L
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bind, port))
        server = s
        try { pri(true) } catch (e: Exception) { }
        priyom = thread(name = "kamera-stranica", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { otvet(c) }
            }
        }
        thread(name = "kamera-stranica-chasy", isDaemon = true) {
            while (!s.isClosed && System.currentTimeMillis() < doMs) Thread.sleep(250)
            zakrytEsli(s)
        }
    }

    @Synchronized fun zakryt() {
        server?.let { zakrytEsli(it) }
    }

    @Synchronized private fun zakrytEsli(s: ServerSocket) {
        try { s.close() } catch (e: IOException) { }
        // порт свободен, только когда поток в accept() проснулся (как у FrameServer.stop)
        priyom?.takeIf { it !== Thread.currentThread() }?.join(1000)
        if (server === s) {
            server = null; doMs = 0
            try { pri(false) } catch (e: Exception) { }
        }
    }

    private fun otvet(c: Socket) {
        c.use { otvetIn(it) }
    }

    private fun otvetIn(c: Socket) {
        c.soTimeout = 5000
        val r = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val zapros = r.readLine() ?: return
        while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break }
        val put = zapros.split(' ').getOrNull(1)?.substringBefore('?') ?: ""
        val a = rtmp()
        val d = if (otkryta && put != "/" && put != "/index.html") try { dop(put) } catch (e: Exception) { null } else null
        val (kod, telo) = if ((put == "/" || put == "/index.html") && otkryta && a != null) 200 to html(a)
            else if (d != null) 200 to d
            else 404 to "<!doctype html><meta charset=\"utf-8\"><p>Страница закрыта: в приложении 2 нажми «КАМЕРА ТЕЛЕФОНА» ещё раз " +
                "(ссылки гостей — Плюсу: «покажи ссылки гостей»).</p>"
        val b = telo.toByteArray(Charsets.UTF_8)
        c.getOutputStream().apply {
            write(("HTTP/1.1 $kod ${if (kod == 200) "OK" else "Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            write(b)
            flush()
        }
    }

    companion object {
        const val PORT = 8770
        const val SEK = 120

        /**
         * Ссылка Larix Grove: подключение «Quest» (перезаписать прежнее с тем же именем), только видео,
         * H.264 1920×1080, 30 к/с, 6000 кбит/с, ключевой кадр раз в секунду (2.5.5: было 720p и 2000 —
         * Александр 17.10: «картинка с камеры слабовата»; Wi-Fi дома тянет с запасом).
         */
        fun larix(rtmp: String): String =
            "larix://set/v1?conn[][url]=" + URLEncoder.encode(rtmp, "UTF-8") +
                "&conn[][name]=Quest&conn[][overwrite]=on&conn[][mode]=v" +
                "&enc[vid][format]=avc&enc[vid][res]=1920x1080&enc[vid][fps]=30&enc[vid][bitrate]=6000&enc[vid][keyframe]=1"

        /**
         * Ссылка Moblin: поток «Quest» (выбран), H.264 1920×1080, 30 к/с, 6000 кбит/с, без B-кадров,
         * ключевой кадр раз в секунду. Moblin по умолчанию шлёт H.265 — без этой ссылки его нужно
         * переключать руками. Запрос — JSON целиком, в процентах (Moblin: url.query(percentEncoded: false)).
         */
        fun moblin(rtmp: String): String =
            "moblin://?" + procent("{\"streams\":[{\"name\":\"Quest\",\"url\":${FrameServer.json(rtmp)},\"selected\":true," +
                "\"video\":{\"codec\":\"H.264/AVC\",\"resolution\":\"1920x1080\",\"fps\":30,\"bitrate\":6000000," +
                "\"bFrames\":false,\"maxKeyFrameInterval\":1}}]}")

        private fun procent(s: String): String = buildString {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                if (c.toChar() in 'A'..'Z' || c.toChar() in 'a'..'z' || c.toChar() in '0'..'9' || c.toChar() in "-._~") append(c.toChar())
                else append('%').append("%02X".format(c))
            }
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

        /**
         * Ссылки гостей эфира для айфона Александра (2.5.0): пульт (его), модератор, гость — «Поделиться»
         * (Telegram и т. п.) и «Скопировать». Ссылки — с ключами: только здесь, по домашней Wi-Fi, 2 минуты.
         */
        fun gostiHtml(hozyain: String?, moder: String?, gost: String?, vnutri: Int): String {
            if (hozyain == null || moder == null || gost == null) return """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="4"><title>Гости эфира</title>
<style>body{font:18px -apple-system,system-ui,sans-serif;margin:0;padding:20px 16px;background:#111;color:#eee}</style></head>
<body><h1>Гости эфира</h1><p>Туннель ещё поднимается (5–10 секунд). Страница обновится сама.</p>
<p><small>Не поднялся за минуту — скажи Плюсу «гости статус».</small></p></body></html>"""
            fun karta(id: String, zag: String, pod: String, ssylka: String, cvet: String) = """
<div class="k"><h2 style="color:$cvet">$zag</h2><p>$pod</p>
<textarea id="$id" rows="2" readonly>${esc(ssylka)}</textarea>
<button class="b" onclick="del('$id','$zag')">Поделиться</button><button onclick="kop('$id')">Скопировать</button></div>"""
            return """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Гости эфира</title>
<style>
body{font:18px -apple-system,system-ui,sans-serif;margin:0;padding:20px 16px 40px;background:#111;color:#eee;line-height:1.4}
h1{font-size:24px;margin:0 0 6px}h2{font-size:20px;margin:0 0 4px}
.k{background:#1c1c22;border-radius:14px;padding:14px;margin:14px 0}
textarea{width:100%;box-sizing:border-box;font:13px ui-monospace,monospace;padding:8px;border-radius:8px;background:#0d0d10;color:#ddd;border:1px solid #333}
button{font-size:17px;padding:12px 16px;margin:8px 8px 0 0;border-radius:12px;border:0;background:#3a3a44;color:#fff}
button.b{background:#2d7ff9}small{color:#999}
</style></head><body>
<h1>Гости эфира</h1><p><small>В комнате сейчас: $vnutri. Ссылки — с ключами: шли только тем, кого зовёшь.</small></p>
${karta("m", "Модератор", "Входит сразу, впускает гостей и делится их ссылкой. Отправь тому, кто будет модератором.", moder, "#ffaa5a")}
${karta("g", "Гость", "Ждёт, пока впустят (модератор или ты: «плюс, впусти гостя»).", gost, "#ff8cc8")}
${karta("h", "Мой пульт", "Тебе: кто в комнате, впустить, убрать с экрана, выгнать. Открой в Safari.", hozyain, "#82c8ff")}
<p><small>Ссылки живут, пока открыта комната (gosti start … gosti stop); следующий раз — новые.
Ссылка гостя утекла — Плюсу «новая ссылка гостя». Страница закроется через 2 минуты.</small></p>
<script>
function kop(id){var t=document.getElementById(id);t.focus();t.select();t.setSelectionRange(0,99999);
try{navigator.clipboard.writeText(t.value)}catch(e){try{document.execCommand('copy')}catch(e2){}}}
function del(id,zag){var t=document.getElementById(id).value;
if(navigator.share){navigator.share({title:'Эфир Плюса — '+zag,text:'Заходи в эфир ('+zag.toLowerCase()+'):',url:t}).catch(function(){})}else kop(id)}
</script></body></html>"""
        }

        fun html(rtmp: String): String {
            val m = esc(moblin(rtmp))
            val l = esc(larix(rtmp))
            val a = esc(rtmp)
            return """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Камера для эфира</title>
<style>
body{font:18px -apple-system,system-ui,sans-serif;margin:0;padding:20px 16px 40px;background:#111;color:#eee;line-height:1.4}
h1{font-size:24px;margin:0 0 12px}
a.b{display:block;padding:18px;margin:18px 0;background:#2d7ff9;color:#fff;text-align:center;border-radius:14px;text-decoration:none;font-size:22px;font-weight:600}
a.b2{background:#3a3a3a}
textarea{width:100%;box-sizing:border-box;font:14px ui-monospace,monospace;padding:8px;border-radius:8px;background:#222;color:#eee;border:1px solid #444}
button{font-size:16px;padding:10px 14px;margin-top:6px;border-radius:10px;border:0;background:#444;color:#fff}
small{color:#999}
</style></head><body>
<h1>Камера для эфира</h1>
<p>Лучше Moblin (App Store, бесплатно, без водяного знака). Нажми:</p>
<a class="b" href="$m">Добавить в Moblin</a>
<p>Moblin спросит про импорт настроек — согласись: появится поток <b>Quest</b> (H.264, 1080p), он уже выбран. Был прежний <b>Quest</b> — его можно удалить. Потом в Moblin — «Go Live».</p>
<a class="b b2" href="$l">Добавить в Larix</a>
<p><small>Larix бесплатно ставит надпись на картинку и ограничивает эфир часом.</small></p>
<p>Приложение спросит про устройства в локальной сети — «Разрешить» (иначе оно не найдёт шлем; потом это в Настройках → Конфиденциальность → Локальная сеть).</p>
<p><small>Не открылось? Скопируй адрес и вставь вручную (в Moblin: Settings → Streams → Quest → URL; кодек — H.264).</small></p>
<textarea id="a" rows="3" readonly>$a</textarea>
<button onclick="k('a')">Скопировать адрес</button>
<p><small>Адрес — с ключом камеры: никому не показывай. Страница закроется через 2 минуты.</small></p>
<script>
function k(id){var t=document.getElementById(id);t.focus();t.select();t.setSelectionRange(0,99999);
try{document.execCommand('copy')}catch(e){}}
</script>
</body></html>"""
        }
    }
}
