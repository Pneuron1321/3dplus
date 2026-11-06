package com.questclaude.bridge

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Галерея «Фильмы/Plus» для страницы клипов (2.7.0) — через MediaStore, только чтение.
 * Свои клипы (камера, KlipMp4) мост видит без разрешений; клипы Моста 3 — чужие файлы: Android 13+
 * показывает их только с «Видео» (READ_MEDIA_VIDEO; до 13 — READ_EXTERNAL_STORAGE). Без разрешения
 * страница покажет клипы камеры и скажет, как открыть игру. Путь к файлу не нужен: файл открывается
 * по номеру (content://…/video/<id>), а номер — только из списка этой папки.
 */
class KlipyGalereya(ctx: Context) : KlipyStranica.Galereya {
    private val ctx = ctx.applicationContext
    private val dlitKesh = ConcurrentHashMap<Long, Long>()

    override val vidnoVse: Boolean get() = ctx.checkSelfPermission(RAZRESHENIE) == PackageManager.PERMISSION_GRANTED

    override fun spisok(): List<KlipyStranica.Klip> {
        val m = MediaStore.Video.Media._ID
        val kolonki = arrayOf(m, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Video.VideoColumns.DURATION, MediaStore.MediaColumns.SIZE)
        // RELATIVE_PATH хранится со слешем на конце; вложенные папки — не наши
        val gde = "(${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?) AND " +
            "${MediaStore.MediaColumns.IS_PENDING}=0"
        val out = ArrayList<KlipyStranica.Klip>()
        ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, kolonki, gde,
            arrayOf(KlipMp4.PAPKA + "/", KlipMp4.PAPKA), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val imya = c.getString(1) ?: "klip.mp4"
                val dlit = c.getLong(3).takeIf { it > 0 } ?: dlitKesh.getOrPut(id) { dlitelnost(id) }
                out += KlipyStranica.Klip(id, imya, c.getLong(2) * 1000L, dlit, c.getLong(4))
            }
        }
        return out
    }

    private fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

    override fun otkryt(k: KlipyStranica.Klip): KlipyStranica.Fayl? {
        val pfd = ctx.contentResolver.openFileDescriptor(uri(k.id), "r") ?: return null
        return object : KlipyStranica.Fayl {
            override val dlina: Long = pfd.statSize.takeIf { it >= 0 } ?: k.bait
            override fun s(ot: Long): InputStream {
                // свой поток на каждый ответ, общий дескриптор закрывается вместе с Fayl
                val f = ParcelFileDescriptor.AutoCloseInputStream(pfd.dup())
                f.channel.position(ot)
                return f
            }
            override fun close() { try { pfd.close() } catch (e: Exception) { } }
        }
    }

    /** Кадр: миниатюра MediaStore (Android сам её кэширует), нет — кадр на 1-й секунде; JPEG 480 px. */
    override fun prevyu(k: KlipyStranica.Klip): ByteArray? {
        val bmp = try {
            ctx.contentResolver.loadThumbnail(uri(k.id), Size(480, 270), null)
        } catch (e: Exception) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri(k.id))
                r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                    if (b.width <= 480) b else Bitmap.createScaledBitmap(b, 480, maxOf(1, b.height * 480 / b.width), true).also { b.recycle() }
                }
            } catch (x: Exception) { null } finally { try { r.release() } catch (x: Exception) { } }
        } ?: return null
        return ByteArrayOutputStream().use { o ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, o)
            bmp.recycle()
            o.toByteArray()
        }
    }

    /** Длительность из самого файла — если галерея ещё не знает (0). */
    private fun dlitelnost(id: Long): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri(id))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L } finally { try { r.release() } catch (e: Exception) { } }
    }

    companion object {
        /** Чтение чужих видео в галерее: Android 13+ — «Видео», раньше — «Файлы». */
        val RAZRESHENIE: String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
