package com.ohanasurf.kiosko

// Impresora y cajón de las tablets KOZEN (la del kiosko es una «KOZEN Nebullar P18», MediaTek, Android 13).
// Dueño, 25 nov 2026: «sigo sin poder abrir el cajón ni imprimir tickets». El diagnóstico enseñó que no es la GEON NB11 que
// creíamos: en las Kozen la impresora y el cajón no se manejan por USB ni por puerto serie, sino con el servicio del fabricante
// (com.pos.service) a través de su SDK (libs/kozen-sdk.jar, paquete com.pos.sdk). El único aparato USB que se veía era el
// adaptador de red por cable (bda:8152), al que la app mandaba los tickets: por eso no salía nada.
//
// · Cajón: POIGeneralAPI.getDefault().openCashbox().
// · Ticket: el programa manda ESC/POS (texto + órdenes). Aquí se lee, se dibuja en una imagen con letra de ancho fijo
//   (para que las columnas cuadren) y se imprime con POIPrinterManager. Las órdenes de cajón dentro del ticket abren el cajón.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import com.pos.sdk.accessory.POIGeneralAPI
import com.pos.sdk.printer.POIPrinterManager
import com.pos.sdk.printer.models.BitmapPrintLine
import com.pos.sdk.printer.models.PrintLine
import com.pos.sdk.printer.models.TextPrintLine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Kozen {
    /** ¿Es una tablet Kozen con el servicio de impresión del fabricante? */
    fun es(ctx: Context): Boolean {
        val marca = (Build.MANUFACTURER + " " + Build.BRAND).uppercase()
        if (!marca.contains("KOZEN")) return false
        return try { ctx.packageManager.getPackageInfo("com.pos.service", 0); true } catch (e: Exception) { false }
    }

    /** Abre el cajón. Devuelve "" si fue bien o el motivo del error. */
    fun abrirCajon(): String = try {
        val r = POIGeneralAPI.getDefault().openCashbox()
        if (r < 0) "el cajón respondió con el código $r" else ""
    } catch (t: Throwable) { "no se pudo abrir el cajón (" + (t.message ?: t.javaClass.simpleName) + ")" }

    private class Linea(val texto: String, val negrita: Boolean, val grande: Boolean, val alin: Int)

    /** Lee el ESC/POS del programa: líneas de texto con negrita/tamaño/alineación y si pide abrir el cajón. */
    private fun leer(datos: ByteArray): Pair<List<Linea>, Boolean> {
        val out = ArrayList<Linea>()
        var cajon = false
        var neg = false; var gr = false; var al = 0
        val sb = StringBuilder()
        fun fin() { out.add(Linea(sb.toString().trimEnd(), neg, gr, al)); sb.setLength(0) }
        var i = 0
        val n = datos.size
        fun b(k: Int) = if (k < n) datos[k].toInt() and 0xFF else 0
        while (i < n) {
            val c = b(i)
            when {
                c == 0x1B -> { // ESC
                    when (b(i + 1)) {
                        0x40 -> { neg = false; gr = false; al = 0; i += 2 }            // ESC @
                        0x45 -> { neg = (b(i + 2) and 1) == 1; i += 3 }                 // ESC E n
                        0x61 -> { al = b(i + 2) % 48; i += 3 }                        // ESC a n
                        0x64 -> { if (sb.isNotEmpty()) fin(); repeat(b(i + 2).coerceAtMost(10)) { out.add(Linea("", false, false, 0)) }; i += 3 } // ESC d n
                        0x70 -> { cajon = true; i += 5 }                              // ESC p m t1 t2
                        0x21 -> { gr = (b(i + 2) and 0x30) != 0; neg = (b(i + 2) and 0x08) != 0; i += 3 } // ESC ! n
                        else -> i += 2
                    }
                }
                c == 0x1D -> { // GS
                    when (b(i + 1)) {
                        0x21 -> { gr = b(i + 2) != 0; i += 3 }                        // GS ! n
                        0x56 -> { i += if (b(i + 2) >= 0x41) 4 else 3 }               // GS V (corte)
                        else -> i += 3
                    }
                }
                c == 0x10 && b(i + 1) == 0x14 -> { cajon = true; i += 5 }             // DLE DC4 (cajón)
                c == 0x0A -> { fin(); i++ }
                c < 0x20 -> i++
                else -> { sb.append(c.toChar()); i++ }
            }
        }
        if (sb.isNotEmpty()) fin()
        while (out.isNotEmpty() && out.last().texto.isEmpty()) out.removeAt(out.size - 1)
        return Pair(out, cajon)
    }

    /** Dibuja las líneas en una imagen de «ancho» puntos (384 = papel de 58 mm, 576 = 80 mm) con letra de ancho fijo. */
    private fun dibujar(lineas: List<Linea>, ancho: Int): Bitmap {
        val cols = maxOf(32, lineas.maxOfOrNull { it.texto.length } ?: 32)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.MONOSPACE }
        // Tamaño de letra para que quepan «cols» caracteres en el ancho
        p.textSize = 40f
        val w40 = p.measureText("M")
        val tam = (40f * (ancho.toFloat() / cols) / w40).coerceIn(12f, 40f)
        val altoLinea = { l: Linea -> (tam * (if (l.grande) 2f else 1f) * 1.22f) }
        val alto = maxOf(1, lineas.sumOf { altoLinea(it).toDouble() }.toInt() + 8)
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.WHITE)
        var y = 0f
        for (l in lineas) {
            val h = altoLinea(l)
            p.textSize = tam * (if (l.grande) 2f else 1f)
            p.typeface = Typeface.create(Typeface.MONOSPACE, if (l.negrita) Typeface.BOLD else Typeface.NORMAL)
            p.isFakeBoldText = l.negrita
            var t = l.texto
            while (t.isNotEmpty() && p.measureText(t) > ancho) t = t.dropLast(1)
            val x = when (l.alin) { 1 -> (ancho - p.measureText(t)) / 2f; 2 -> ancho - p.measureText(t); else -> 0f }
            y += h
            cv.drawText(t, x, y - h * 0.22f, p)
        }
        return bmp
    }

    /**
     * Imprime el ESC/POS. modo: "384" o "576" (imagen de ese ancho) o "texto" (líneas de texto del SDK).
     * Devuelve "" si fue bien o el motivo del error. Bloquea hasta que termina (se llama desde la cola de impresión).
     */
    fun imprimir(ctx: Context, datos: ByteArray, modo: String): String {
        val (lineas, cajon) = leer(datos)
        var err = ""
        if (cajon) err = abrirCajon()
        if (lineas.all { it.texto.isBlank() }) return err
        var pm: POIPrinterManager? = null
        try {
            pm = POIPrinterManager(ctx.applicationContext)
            pm.open()
            pm.cleanCache()
            if (modo == "texto") {
                for (l in lineas) {
                    val pos = when (l.alin) { 1 -> PrintLine.CENTER; 2 -> PrintLine.RIGHT; else -> PrintLine.LEFT }
                    pm.addPrintLine(TextPrintLine(if (l.texto.isEmpty()) " " else l.texto, pos,
                        if (l.grande) TextPrintLine.FONT_LARGE else TextPrintLine.FONT_NORMAL, l.negrita))
                }
            } else {
                val ancho = modo.toIntOrNull()?.coerceIn(200, 832) ?: 384
                pm.addPrintLine(BitmapPrintLine(dibujar(lineas, ancho), PrintLine.LEFT))
            }
            pm.lineWrap(3)
            val hecho = CountDownLatch(1)
            var fallo = ""
            pm.beginPrint(object : POIPrinterManager.IPrinterListener {
                override fun onStart() {}
                override fun onFinish() { hecho.countDown() }
                override fun onError(code: Int, msg: String?) {
                    fallo = when (code) {
                        POIPrinterManager.ERROR_NO_PAPER -> "la impresora no tiene papel"
                        POIPrinterManager.ERROR_OVERHEAT -> "la impresora está demasiado caliente; espera un poco"
                        else -> "error de la impresora ($code${if (msg.isNullOrBlank()) "" else ": $msg"})"
                    }
                    hecho.countDown()
                }
            })
            if (!hecho.await(40, TimeUnit.SECONDS)) fallo = "la impresora no contesta"
            if (fallo.isNotEmpty()) err = if (err.isEmpty()) fallo else "$err; $fallo"
        } catch (t: Throwable) {
            val m = "no se pudo usar la impresora de la tablet (" + (t.message ?: t.javaClass.simpleName) + ")"
            err = if (err.isEmpty()) m else "$err; $m"
        } finally {
            try { pm?.close() } catch (t: Throwable) {}
        }
        return err
    }

    /** Estado de la impresora para el diagnóstico. */
    fun estado(ctx: Context): String = try {
        val pm = POIPrinterManager(ctx.applicationContext)
        pm.open()
        val s = pm.printerState
        pm.close()
        when (s) {
            POIPrinterManager.STATUS_IDLE -> "lista"
            POIPrinterManager.STATUS_PRINTING -> "imprimiendo"
            POIPrinterManager.STATUS_NO_PAPER -> "sin papel"
            POIPrinterManager.STATUS_OVERHEAT -> "demasiado caliente"
            POIPrinterManager.STATUS_NO_PRINTER -> "no encuentra la impresora"
            else -> "estado $s"
        }
    } catch (t: Throwable) { "error: " + (t.message ?: t.javaClass.simpleName) }
}
