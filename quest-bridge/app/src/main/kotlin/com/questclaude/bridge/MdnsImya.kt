package com.questclaude.bridge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Имя шлема в домашней сети: plus.local → адрес шлема (mDNS, как у принтеров и Raspberry Pi).
 * Айфон открывает http://plus.local:8770 без набора цифр (закладка в Safari), и адрес не
 * меняется, когда роутер выдал шлему другой. Отвечаем только на вопрос «где plus.local»
 * (запись A) и только пока открыта страница камеры. Разбор и сборка пакетов — обычная JVM
 * (MdnsImyaTest).
 */
class MdnsImya(private val ip: () -> String?, private val imya: String = IMYA) {
    @Volatile private var sock: MulticastSocket? = null
    @Volatile var otvetov = 0
        private set

    @Synchronized fun start() {
        if (sock != null) return
        val adres = ip() ?: return
        val mestny = InetAddress.getByName(adres)
        val s = MulticastSocket(null as java.net.SocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
        }
        val iface = NetworkInterface.getByInetAddress(mestny)
        try {
            s.joinGroup(InetSocketAddress(GRUPPA, PORT), iface)
        } catch (e: IOException) {
            s.close(); throw e
        }
        sock = s
        thread(name = "mdns-plus", isDaemon = true) {
            val buf = ByteArray(1500)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: IOException) { break }
                val v = vopros(p.data, p.length, imya) ?: continue
                val my = (ip()?.let { InetAddress.getByName(it) } as? Inet4Address)?.address ?: continue
                val obychny = p.port != PORT                  // «старый» вопрос с чужого порта — ответ ему лично, с его id
                val o = otvet(if (obychny) v.id else 0, imya, my, if (obychny) v.vopros else null)
                try {
                    if (obychny) s.send(DatagramPacket(o, o.size, p.socketAddress))
                    else {
                        s.send(DatagramPacket(o, o.size, InetSocketAddress(GRUPPA, PORT)))
                        if (v.lichno) s.send(DatagramPacket(o, o.size, p.socketAddress))
                    }
                    otvetov++
                } catch (e: IOException) { }
            }
        }
    }

    @Synchronized fun stop() {
        try { sock?.close() } catch (e: Exception) { }
        sock = null
    }

    /** Найденный вопрос: id пакета, сам вопрос (для ответа «старому» клиенту), просили ли ответ лично (бит QU). */
    class Vopros(val id: Int, val vopros: ByteArray, val lichno: Boolean)

    companion object {
        const val IMYA = "plus.local"
        const val PORT = 5353
        val GRUPPA: InetAddress = InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))

        /** Есть ли в пакете вопрос «A (или ANY) для imya». Ответы и чужие имена — null. */
        fun vopros(a: ByteArray, len: Int, imya: String): Vopros? {
            if (len < 12) return null
            fun u16(i: Int) = ((a[i].toInt() and 0xff) shl 8) or (a[i + 1].toInt() and 0xff)
            if (a[2].toInt() and 0x80 != 0) return null          // это ответ
            val n = u16(4)
            var i = 12
            repeat(minOf(n, 16)) {
                val nachalo = i
                val (name, posle) = chitatImya(a, len, i) ?: return null
                i = posle
                if (i + 4 > len) return null
                val tip = u16(i)
                val klass = u16(i + 2)
                i += 4
                if (name.equals(imya, ignoreCase = true) && (tip == 1 || tip == 255) && (klass and 0x7fff) == 1)
                    return Vopros(u16(0), sobratImya(name) + a.copyOfRange(i - 4, i).also {
                        it[2] = (it[2].toInt() and 0x7f).toByte()   // без бита QU
                    }, klass and 0x8000 != 0)
                if (nachalo == i) return null
            }
            return null
        }

        /** Имя из пакета (с переходами-сжатием); (имя, где кончилось в исходном месте). */
        private fun chitatImya(a: ByteArray, len: Int, from: Int): Pair<String, Int>? {
            val chasti = mutableListOf<String>()
            var i = from
            var konec = -1
            var prizhkov = 0
            while (true) {
                if (i >= len) return null
                val l = a[i].toInt() and 0xff
                when {
                    l == 0 -> { if (konec < 0) konec = i + 1; break }
                    l and 0xc0 == 0xc0 -> {
                        if (i + 1 >= len || ++prizhkov > 16) return null
                        if (konec < 0) konec = i + 2
                        i = ((l and 0x3f) shl 8) or (a[i + 1].toInt() and 0xff)
                    }
                    l and 0xc0 != 0 -> return null
                    else -> {
                        if (i + 1 + l > len) return null
                        chasti += String(a, i + 1, l, Charsets.UTF_8)
                        i += 1 + l
                    }
                }
            }
            return chasti.joinToString(".") to konec
        }

        private fun sobratImya(name: String): ByteArray = ByteArrayOutputStream().apply {
            for (p in name.split('.')) { val b = p.toByteArray(); write(b.size); write(b) }
            write(0)
        }.toByteArray()

        /** Ответ: imya → ip (A, «сбросить старое», 120 с). vopros — повторить вопрос («старому» клиенту). */
        fun otvet(id: Int, imya: String, ip: ByteArray, vopros: ByteArray?): ByteArray = ByteArrayOutputStream().apply {
            write(id ushr 8); write(id and 0xff)
            write(0x84); write(0)                                 // ответ, авторитетный
            write(0); write(if (vopros != null) 1 else 0)
            write(0); write(1)
            write(0); write(0); write(0); write(0)
            if (vopros != null) write(vopros)
            write(sobratImya(imya))
            write(0); write(1)                                    // A
            write(0x80); write(1)                                 // IN, сбросить старое
            write(0); write(0); write(0); write(120)              // 120 с
            write(0); write(4); write(ip)
        }.toByteArray()
    }
}
