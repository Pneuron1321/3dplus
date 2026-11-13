package com.questclaude.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** plus.local: вопрос mDNS узнаём (и со сжатием имён), отвечаем записью A по правилам mDNS. */
class MdnsImyaTest {
    private fun imya(n: String) = ByteArrayOutputStream().apply {
        for (p in n.split('.')) { write(p.length); write(p.toByteArray()) }
        write(0)
    }.toByteArray()

    private fun vopros(id: Int, vararg q: ByteArray) = ByteArrayOutputStream().apply {
        write(id ushr 8); write(id and 0xff); write(0); write(0)
        write(0); write(q.size); write(0); write(0); write(0); write(0); write(0); write(0)
        q.forEach { write(it) }
    }.toByteArray()

    private fun q(name: ByteArray, tip: Int, klass: Int = 1) = name + byteArrayOf(0, tip.toByte(), (klass ushr 8).toByte(), klass.toByte())

    @Test fun uznayotVopros() {
        val p = vopros(0, q(imya("plus.local"), 1, 0x8001))       // A, бит QU (как спрашивает айфон)
        val v = MdnsImya.vopros(p, p.size, "plus.local")!!
        assertTrue(v.lichno)
        assertArrayEquals(q(imya("plus.local"), 1), v.vopros)     // повтор вопроса — без бита QU
        val any = vopros(7, q(imya("PLUS.local"), 255))          // ANY, большими буквами
        assertEquals(7, MdnsImya.vopros(any, any.size, "plus.local")!!.id)
    }

    @Test fun chuzhoeMimo() {
        val drugoe = vopros(0, q(imya("printer.local"), 1))
        assertNull(MdnsImya.vopros(drugoe, drugoe.size, "plus.local"))
        val aaaa = vopros(0, q(imya("plus.local"), 28))
        assertNull(MdnsImya.vopros(aaaa, aaaa.size, "plus.local"))
        val otvet = MdnsImya.otvet(0, "plus.local", byteArrayOf(192.toByte(), 168.toByte(), 1, 50), null)
        assertNull(MdnsImya.vopros(otvet, otvet.size, "plus.local"))   // ответы не трогаем
        assertNull(MdnsImya.vopros(byteArrayOf(1, 2, 3), 3, "plus.local"))
    }

    @Test fun vtoroyVoprosSoSzhatiem() {
        // первый вопрос — printer.local; второй — «plus» + ссылка на «local» из первого (смещение 12 + 8)
        val first = q(imya("printer.local"), 1)
        val second = byteArrayOf(4) + "plus".toByteArray() + byteArrayOf(0xc0.toByte(), (12 + 8).toByte()) + byteArrayOf(0, 1, 0, 1)
        val p = vopros(0, first, second)
        val v = MdnsImya.vopros(p, p.size, "plus.local")!!
        assertArrayEquals(q(imya("plus.local"), 1), v.vopros)
    }

    @Test fun otvetZapisA() {
        val ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 50)
        val o = MdnsImya.otvet(0, "plus.local", ip, null)
        val zhdu = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0) + imya("plus.local") +
            byteArrayOf(0, 1, 0x80.toByte(), 1, 0, 0, 0, 120, 0, 4) + ip
        assertArrayEquals(zhdu, o)
        val star = MdnsImya.otvet(0x1234, "plus.local", ip, q(imya("plus.local"), 1))
        assertEquals(0x12, star[0].toInt()); assertEquals(0x34, star[1].toInt())
        assertEquals(1, star[5].toInt())                        // вопрос повторён «старому» клиенту
    }
}
