package com.ohanasurf.kiosko

// App «Kiosko Ohana» (dueño, 19 nov 2026).
// · Abre el programa del kiosko (index.html en Vercel) a pantalla completa, en horizontal y sin barras.
// · Le da al programa window.OhanaTPV para cobrar con el NFC de la propia tablet con Stripe Tap to Pay
//   (cuenta Stripe «Ohana Kiosko»; el servidor está en api/tpv.js).
// · Deja pasar la impresión por RawBT (intent:...) y el diálogo de imprimir de Android.
// Ninguna clave va en el código: la «clave del TPV» se teclea una vez en la tablet.

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.TerminalApplicationDelegate
import com.stripe.stripeterminal.external.callable.*
import com.stripe.stripeterminal.external.models.*
import com.stripe.stripeterminal.log.LogLevel
import com.stripe.stripeterminal.taptopay.TapToPay
import org.json.JSONArray
import org.json.JSONObject
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.location.LocationManager
import android.provider.Settings
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import android.app.PendingIntent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.net.HttpURLConnection
import java.net.URL

class KioskoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Tap to Pay abre un segundo proceso seguro con otra copia de la app: ahí no se inicia nada.
        if (TapToPay.isInTapToPayProcess()) return
        TerminalApplicationDelegate.onCreate(this)
    }
}

/** Habla con api/tpv.js en Vercel. */
object Servidor {
    @Volatile var clave: String = ""

    fun pedir(accion: String, post: Boolean): JSONObject {
        val base = BuildConfig.KIOSKO_URL.trimEnd('/')
        val c = URL("$base/api/tpv?a=$accion").openConnection() as HttpURLConnection
        c.requestMethod = if (post) "POST" else "GET"
        c.setRequestProperty("x-tpv-clave", clave)
        c.connectTimeout = 15000
        c.readTimeout = 20000
        if (post) {
            c.doOutput = true
            c.outputStream.use { it.write(ByteArray(0)) }
        }
        val codigo = c.responseCode
        val flujo = if (codigo in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val texto = flujo.bufferedReader().use { it.readText() }
        val j = try { JSONObject(texto) } catch (e: Exception) { JSONObject().put("error", "Respuesta rara del servidor ($codigo)") }
        if (codigo !in 200..299) throw RuntimeException(j.optString("error", "Error $codigo"))
        return j
    }
}

/** Stripe pide un permiso (ConnectionToken) cada vez que lo necesita. */
class Fichas : ConnectionTokenProvider {
    override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
        Thread {
            try {
                callback.onSuccess(Servidor.pedir("token", true).getString("secret"))
            } catch (e: Exception) {
                callback.onFailure(ConnectionTokenException("No se pudo pedir permiso a Stripe: ${e.message}", e))
            }
        }.start()
    }
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var impresion: WebView? = null
    private val prefs by lazy { getSharedPreferences("kiosko", Context.MODE_PRIVATE) }

    @Volatile private var estado = "Preparando el cobro con tarjeta…"
    @Volatile private var conectado = false
    @Volatile private var conectando = false
    @Volatile private var simulado = false
    private var location = ""
    private var cobroEnCurso: Cancelable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        setContentView(web)
        pantallaCompleta()

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Puente(), "OhanaTPV")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                abrirFuera(request.url)
        }
        web.loadUrl(BuildConfig.KIOSKO_URL)

        Servidor.clave = prefs.getString("clave", "") ?: ""
        if (tienePermisos()) arrancar() else pedirPermisos()
    }

    override fun onResume() {
        super.onResume()
        pantallaCompleta()
        if (!conectado && !conectando && Servidor.clave.isNotEmpty() && tienePermisos()) arrancar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pantallaCompleta()
    }

    @Deprecated("Atrás vuelve dentro del programa")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack()
    }

    @Suppress("DEPRECATION")
    private fun pantallaCompleta() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    // ---------- Enlaces: RawBT y otras apps ----------
    private fun abrirFuera(u: Uri): Boolean {
        val s = u.scheme ?: return false
        if (s == "http" || s == "https") {
            if (u.host == Uri.parse(BuildConfig.KIOSKO_URL).host) return false
            return try { startActivity(Intent(Intent.ACTION_VIEW, u)); true } catch (e: Exception) { false }
        }
        try {
            val i = if (s == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            startActivity(i)
        } catch (e: Exception) {
            if (s == "intent") avisar("No se pudo imprimir. ¿Está instalada la app RawBT?")
        }
        return true
    }

    // ---------- Imprimir con el diálogo de Android ----------
    private fun imprimirHtml(html: String) {
        val w = WebView(this)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print("Ticket kiosko", view.createPrintDocumentAdapter("Ticket kiosko"), PrintAttributes.Builder().build())
            }
        }
        impresion = w
        w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    // ---------- Permisos ----------
    private fun permisos(): Array<String> {
        val l = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
            l.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        return l.toTypedArray()
    }

    private fun tienePermisos(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pedirPermisos() = requestPermissions(permisos(), 1)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (tienePermisos()) arrancar()
        else {
            estado = "Falta el permiso de ubicación (Stripe lo exige para cobrar). Activa Ubicación en los permisos de Kiosko Ohana."
            abrirAjustesApp()
        }
    }

    // ---------- Clave del TPV (la del TPV_CLAVE de Vercel) ----------
    private fun pedirClave() {
        val campo = EditText(this)
        campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        campo.hint = "Clave del TPV"
        AlertDialog.Builder(this)
            .setTitle("Cobro con tarjeta en la tablet")
            .setMessage("Escribe la clave del TPV (la misma que pusiste en Vercel como TPV_CLAVE). Solo se pide una vez.")
            .setView(campo)
            .setPositiveButton("Guardar") { _, _ ->
                Servidor.clave = campo.text.toString().trim()
                prefs.edit().putString("clave", Servidor.clave).apply()
                conectado = false
                arrancar()
            }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    // ---------- Stripe Tap to Pay ----------
    private fun arrancar() {
        if (Servidor.clave.isEmpty()) { estado = "Falta la clave del TPV."; pedirClave(); return }
        if (conectando || conectado) return
        conectando = true
        estado = "Conectando el cobro con tarjeta…"
        Thread {
            try {
                val cfg = Servidor.pedir("config", false)
                location = cfg.getString("location")
                simulado = cfg.optBoolean("simulado", false)
                runOnUiThread { iniciarTerminal() }
            } catch (e: Exception) {
                conectando = false
                estado = "No se pudo conectar con el servidor: ${e.message}"
                if ((e.message ?: "").contains("Clave")) runOnUiThread { pedirClave() }
            }
        }.start()
    }

    private fun iniciarTerminal() {
        try {
            if (!Terminal.isInitialized()) {
                Terminal.init(
                    applicationContext,
                    LogLevel.NONE,
                    Fichas(),
                    object : TerminalListener {
                        override fun onConnectionStatusChange(status: ConnectionStatus) {
                            conectado = status == ConnectionStatus.CONNECTED
                        }
                    },
                    null,
                    LocaleConfig.CardLanguagePreferenceIfAvailable,
                )
            }
        } catch (e: Exception) {
            conectando = false
            estado = "No se pudo iniciar Stripe: ${e.message}"
            return
        }
        buscarLector()
    }

    private fun buscarLector() {
        val config = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(isSimulated = simulado)
        Terminal.getInstance().discoverReaders(
            config,
            object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    val r = readers.firstOrNull() ?: return
                    if (!conectado) runOnUiThread { conectar(r) }
                }
            },
            object : Callback {
                override fun onSuccess() {}
                override fun onFailure(e: TerminalException) {
                    conectando = false
                    estado = explicar(e)
                }
            },
        )
    }

    private fun conectar(r: Reader) {
        val config = ConnectionConfiguration.TapToPayConnectionConfiguration(
            useCase = TapUseCase.Pay(location),
            autoReconnectOnUnexpectedDisconnect = true,
            tapToPayReaderListener = object : TapToPayReaderListener {},
        )
        Terminal.getInstance().connectReader(r, config, object : ReaderCallback {
            override fun onSuccess(reader: Reader) {
                conectando = false
                conectado = true
                estado = if (simulado) "Modo prueba de Stripe: no se cobra de verdad." else "Cobro con tarjeta listo."
            }
            override fun onFailure(e: TerminalException) {
                conectando = false
                conectado = false
                estado = explicar(e)
            }
        })
    }

    private fun explicar(e: TerminalException): String {
        val code = e.errorCode.name
        val txt = e.message ?: ""
        return when {
            code.contains("UNSUPPORTED") || code.contains("ATTESTATION") || code.contains("INSECURE") ->
                "Esta tablet no pasa la comprobación de Stripe para cobrar con NFC ($code). Cobra con el datáfono. $txt"
            code.contains("CANCEL") -> "Cobro cancelado."
            code.contains("DECLINED") -> "Tarjeta rechazada. $txt"
            code.contains("CONNECTION") || code.contains("NETWORK") -> "Sin internet o sin conexión con Stripe. $txt"
            else -> "$code: $txt"
        }
    }

    private fun cobrarTarjeta(centimos: Long) {
        if (!tienePermisos()) {
            responder(false, null, "Falta el permiso de ubicación. Te abro los permisos de la app: activa Ubicación y vuelve.")
            pedirPermisos()
            return
        }
        if (!ubicacionEncendida()) {
            responder(false, null, "La ubicación de la tablet está apagada. Te abro el ajuste: enciéndela y vuelve.")
            try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) {}
            return
        }
        if (!conectado) {
            responder(false, null, "El cobro con tarjeta todavía no está listo. $estado")
            arrancar()
            return
        }
        val params = PaymentIntentParameters.Builder()
            .setAmount(centimos)
            .setCurrency("eur")
            .build()
        Terminal.getInstance().createPaymentIntent(params, object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) {
                runOnUiThread { procesar(paymentIntent) }
            }
            override fun onFailure(e: TerminalException) {
                responder(false, null, explicar(e))
            }
        })
    }

    private fun procesar(pi: PaymentIntent) {
        cobroEnCurso = Terminal.getInstance().processPaymentIntent(
            pi,
            CollectPaymentIntentConfiguration.Builder().build(),
            ConfirmPaymentIntentConfiguration.Builder().build(),
            object : PaymentIntentCallback {
                override fun onSuccess(paymentIntent: PaymentIntent) {
                    cobroEnCurso = null
                    val st = paymentIntent.status?.toString() ?: ""
                    val ok = st.equals("SUCCEEDED", true) || st.equals("REQUIRES_CAPTURE", true)
                    responder(ok, paymentIntent.id, if (ok) "" else "Stripe dice: $st")
                }
                override fun onFailure(e: TerminalException) {
                    cobroEnCurso = null
                    responder(false, null, explicar(e))
                }
            },
        )
    }

    private fun responder(ok: Boolean, id: String?, error: String) {
        val j = JSONObject()
            .put("ok", ok)
            .put("id", id ?: "")
            .put("error", error)
            .put("simulado", simulado)
        runOnUiThread {
            web.evaluateJavascript("window.ohkTPV&&window.ohkTPV(${JSONObject.quote(j.toString())})", null)
        }
    }

    private fun avisar(texto: String) {
        runOnUiThread {
            web.evaluateJavascript("try{tpvMsg=${JSONObject.quote(texto)};render()}catch(e){}", null)
        }
    }

    // ---------- Imprimir directo en la impresora de la tablet (sin RawBT). Dueño, 20 nov 2026 ----------
    // Las tablets TPV traen la impresora interna como un aparato Bluetooth emparejado de fábrica o como un puerto serie.
    private val colaImpresion = Executors.newSingleThreadExecutor()

    @SuppressLint("MissingPermission")
    private fun aparatosBt(): List<BluetoothDevice> = try {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bondedDevices?.toList() ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private val puertosSerie = listOf("/dev/ttyS1", "/dev/ttyS3", "/dev/ttyS4", "/dev/ttyS0", "/dev/ttyS2", "/dev/ttyS5", "/dev/ttyUSB0", "/dev/usb/lp0")

    // Impresora interna por USB (lo más habitual en los TPV Android con impresora de 80 mm). Dueño, 21 nov 2026.
    private fun usbManager() = getSystemService(Context.USB_SERVICE) as UsbManager

    private fun salidaUsb(d: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        var mejor: Pair<UsbInterface, UsbEndpoint>? = null
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                    if (itf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return Pair(itf, ep)
                    if (mejor == null) mejor = Pair(itf, ep)
                }
            }
        }
        return mejor
    }

    // Fuera los adaptadores de red por USB (clases CDC 2 y 10, p. ej. el bda:8152 de la tablet Kozen): no son impresoras.
    private fun esRedUsb(d: UsbDevice): Boolean = d.vendorId == 0x0bda && d.productId == 0x8152 ||
        (0 until d.interfaceCount).any { val c = d.getInterface(it).interfaceClass; c == 2 || c == 10 }

    private fun impresorasUsb(): List<UsbDevice> = try {
        usbManager().deviceList.values.filter { salidaUsb(it) != null && !esRedUsb(it) }
            .sortedByDescending { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER } }
    } catch (e: Exception) { emptyList() }

    private fun nombreUsb(d: UsbDevice): String = try { (d.productName ?: d.manufacturerName ?: "") } catch (e: Exception) { "" }
        .ifBlank { "USB " + Integer.toHexString(d.vendorId) + ":" + Integer.toHexString(d.productId) }

    private fun imprimirUsb(d: UsbDevice, datos: ByteArray) {
        val um = usbManager()
        if (!um.hasPermission(d)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent("com.ohanasurf.kiosko.USB").setPackage(packageName), flags)
            runOnUiThread { um.requestPermission(d, pi) }
            throw RuntimeException("acepta el permiso de la impresora USB que ha salido en pantalla y vuelve a imprimir")
        }
        val (itf, ep) = salidaUsb(d) ?: throw RuntimeException("la impresora USB no tiene salida de datos")
        val con = um.openDevice(d) ?: throw RuntimeException("no se pudo abrir la impresora USB")
        try {
            con.claimInterface(itf, true)
            var off = 0
            while (off < datos.size) {
                val n = minOf(4096, datos.size - off)
                val trozo = datos.copyOfRange(off, off + n)
                val r = con.bulkTransfer(ep, trozo, n, 5000)
                if (r < 0) throw RuntimeException("la impresora USB no responde")
                off += r
            }
            con.releaseInterface(itf)
        } finally { con.close() }
    }

    @SuppressLint("MissingPermission")
    private fun listaImpresoras(): String {
        val arr = JSONArray()
        if (Kozen.es(this)) {
            arr.put(JSONObject().put("tipo", "kozen").put("id", "kozen:384").put("nombre", "Impresora de la tablet (papel de 58 mm)"))
            arr.put(JSONObject().put("tipo", "kozen").put("id", "kozen:576").put("nombre", "Impresora de la tablet (papel de 80 mm)"))
            arr.put(JSONObject().put("tipo", "kozen").put("id", "kozen:texto").put("nombre", "Impresora de la tablet (solo texto)"))
        }
        impresorasUsb().forEach { arr.put(JSONObject().put("tipo", "usb").put("id", "usb:" + it.deviceName).put("nombre", nombreUsb(it) + " (USB)")) }
        aparatosBt().forEach { arr.put(JSONObject().put("tipo", "bt").put("id", it.address).put("nombre", it.name ?: it.address)) }
        puertosSerie.forEach { p -> val f = File(p); if (f.exists() && f.canWrite()) arr.put(JSONObject().put("tipo", "serie").put("id", p).put("nombre", "Puerto $p")) }
        return arr.toString()
    }

    @SuppressLint("MissingPermission")
    private fun imprimirDirecto(destino: String, datos: ByteArray) {
        colaImpresion.execute {
            var error = ""
            var via = ""
            try {
                if (destino.startsWith("kozen") || Kozen.es(this) && destino.isEmpty() && impresorasUsb().isEmpty()) {
                    // 26 nov: la P18 del kiosko NO trae impresora dentro (el SDK dice «no encuentra la impresora»); la impresora
                    // es USB (0418:5011). Por Kozen solo se imprime si se elige «kozen:…» o si no hay ninguna impresora USB.
                    // Tablet KOZEN (la del kiosko): impresora y cajón por el servicio del fabricante. Dueño, 25 nov 2026.
                    val e = Kozen.imprimir(this, datos, destino.substringAfter("kozen:", "384"))
                    if (e.isNotEmpty()) throw RuntimeException(e)
                    via = "impresora de la tablet"
                } else if (destino.startsWith("usb:") || destino.isEmpty() && impresorasUsb().isNotEmpty()) {
                    val lista = impresorasUsb()
                    val d = lista.firstOrNull { "usb:" + it.deviceName == destino } ?: lista.firstOrNull()
                        ?: throw RuntimeException("no encuentro la impresora USB")
                    imprimirUsb(d, datos); via = "USB"
                } else if (destino.startsWith("/dev/")) {
                    // «/dev/ttyS3@115200»: puerto serie con su velocidad (dueño, 21 nov 2026: pruebas del lunes)
                    val ruta = destino.substringBefore("@")
                    destino.substringAfter("@", "").toIntOrNull()?.let { ponerBaudios(ruta, it) }
                    FileOutputStream(ruta).use { it.write(datos); it.flush() }
                    via = "serie $destino"
                } else {
                    val lista = aparatosBt()
                    val dev = lista.firstOrNull { it.address == destino }
                        ?: lista.firstOrNull { (it.name ?: "").contains(Regex("print|inner|pos|impres", RegexOption.IGNORE_CASE)) }
                    if (dev != null) {
                        val s = dev.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                        s.connect()
                        s.outputStream.write(datos)
                        s.outputStream.flush()
                        Thread.sleep(400)
                        s.close()
                        via = "Bluetooth " + (dev.name ?: dev.address)
                    } else {
                        val p = puertosSerie.firstOrNull { File(it).let { f -> f.exists() && f.canWrite() } }
                            ?: throw RuntimeException("no encuentro la impresora de la tablet (ni Bluetooth ni puerto serie). Elige una en la lista")
                        ponerBaudios(p, 115200)
                        FileOutputStream(p).use { it.write(datos); it.flush() }
                        via = "serie $p"
                    }
                }
            } catch (e: SecurityException) {
                error = "falta el permiso «Dispositivos cercanos» de la app Kiosko Ohana"
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            val j = JSONObject().put("ok", error.isEmpty()).put("error", error).put("via", via)
            runOnUiThread { web.evaluateJavascript("window.ohkPRN&&window.ohkPRN(${JSONObject.quote(j.toString())})", null) }
        }
    }

    // ---------- Diagnóstico de la impresora y del cajón (dueño, 21 nov 2026: «déjamelo preparado para el lunes») ----------
    // El TPV ZAZOO / GEON NB11 (Rockchip RK3562, Android 13) no publica cómo se abre su cajón. Este diagnóstico apunta todo lo
    // que la tablet enseña (USB, Bluetooth, puertos serie, ficheros del sistema con «cash/drawer/box», apps de impresora) y lo
    // manda al panel para que Claude lo lea; las pruebas numeradas dicen qué camino imprime y cuál abre el cajón.
    private fun ponerBaudios(ruta: String, baudios: Int) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "stty -F $ruta $baudios raw -echo 2>/dev/null || toybox stty -F $ruta $baudios raw -echo"))
            p.waitFor()
        } catch (e: Exception) {}
    }

    private val reSistema = Regex("cash|drawer|money|kick|qianxiang|box|print|gpio|uart|serial", RegexOption.IGNORE_CASE)
    private val reCajon = Regex("cash|drawer|money|kick|qianxiang|box", RegexOption.IGNORE_CASE)
    private fun buscarEn(dir: File, prof: Int, res: JSONArray, dentroCajon: Boolean = false) {
        if (prof < 0 || res.length() >= 300) return
        val hijos = try { dir.listFiles() } catch (e: Exception) { null } ?: return
        for (f in hijos) {
            if (res.length() >= 300) return
            val coincide = dentroCajon || reSistema.containsMatchIn(f.name)
            if (coincide) res.put(f.path + (if (f.isDirectory) "/" else "") + (if (f.canWrite()) " [escribe]" else "") + (if (f.canRead()) " [lee]" else ""))
            val enlace = try { java.nio.file.Files.isSymbolicLink(f.toPath()) } catch (e: Exception) { true }
            val esCajon = reCajon.containsMatchIn(f.name)
            if (f.isDirectory && (!enlace || coincide)) {
                if (esCajon) buscarEn(f, 1, res, true) else if (prof > 0) buscarEn(f, prof - 1, res, dentroCajon)
            }
        }
    }

    @SuppressLint("MissingPermission", "QueryPermissionsNeeded")
    private fun diagnosticoJson(): String {
        val j = JSONObject()
        try {
            j.put("app", BuildConfig.VERSION_NAME)
            j.put("aparato", JSONObject().put("fabricante", Build.MANUFACTURER).put("marca", Build.BRAND).put("modelo", Build.MODEL)
                .put("producto", Build.PRODUCT).put("placa", Build.BOARD).put("hardware", Build.HARDWARE).put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT).put("huella", Build.FINGERPRINT))
            if (Kozen.es(this)) j.put("kozen", JSONObject().put("impresora", Kozen.estado(this)))
            val usb = JSONArray()
            try {
                usbManager().deviceList.values.forEach { d ->
                    val itfs = JSONArray()
                    for (i in 0 until d.interfaceCount) {
                        val itf = d.getInterface(i)
                        val eps = JSONArray()
                        for (e in 0 until itf.endpointCount) { val ep = itf.getEndpoint(e); eps.put("tipo${ep.type}/" + (if (ep.direction == UsbConstants.USB_DIR_OUT) "salida" else "entrada")) }
                        itfs.put(JSONObject().put("clase", itf.interfaceClass).put("subclase", itf.interfaceSubclass).put("protocolo", itf.interfaceProtocol).put("puntos", eps))
                    }
                    usb.put(JSONObject().put("id", "usb:" + d.deviceName).put("nombre", nombreUsb(d)).put("vid", Integer.toHexString(d.vendorId)).put("pid", Integer.toHexString(d.productId))
                        .put("clase", d.deviceClass).put("permiso", usbManager().hasPermission(d)).put("salida", salidaUsb(d) != null).put("interfaces", itfs))
                }
            } catch (e: Exception) { usb.put("error: " + e.message) }
            j.put("usb", usb)
            val bt = JSONArray()
            try { aparatosBt().forEach { bt.put(JSONObject().put("id", it.address).put("nombre", it.name ?: "")) } } catch (e: Exception) { bt.put("error: " + e.message) }
            j.put("bluetooth", bt)
            val dev = JSONArray()
            (File("/dev").listFiles() ?: emptyArray()).filter { Regex("^(tty(S|USB|ACM|HS|MT|GS|FIQ|AMA)\\d+|usb|lp\\d*|.*print.*|.*cash.*|.*drawer.*|.*box.*|gpio.*)$", RegexOption.IGNORE_CASE).matches(it.name) }
                .forEach { dev.put(it.path + (if (it.canWrite()) " [escribe]" else "") + (if (it.canRead()) " [lee]" else "")) }
            File("/dev/usb").listFiles()?.forEach { dev.put(it.path + (if (it.canWrite()) " [escribe]" else "")) }
            j.put("dev", dev)
            val sis = JSONArray()
            buscarEn(File("/sys/class"), 2, sis); buscarEn(File("/sys/devices/platform"), 2, sis); buscarEn(File("/proc"), 1, sis)
            j.put("sistema", sis)
            val apps = JSONArray()
            try {
                val re = Regex("print|pos|cash|drawer|serial|gpio|sunmi|woyou|imin|telpo|senraise|reciept|receipt|nyx|hopestar|landi|ciontek|geon|zazoo|smart|xprinter|epson|bixolon|rawbt|escpos|rockchip", RegexOption.IGNORE_CASE)
                // 26 nov 2026: la tablet trae un programa de caja de demostración que sí imprime y abre el cajón. Para saber cuál es
                // se listan todas las apps que no son de Android/Google/MediaTek, con su nombre visible.
                val fuera = Regex("^(android|com\\.android|com\\.google|com\\.mediatek|com\\.mtk|vendor\\.|com\\.qualcomm)")
                packageManager.getInstalledPackages(0).filter { re.containsMatchIn(it.packageName) || !fuera.containsMatchIn(it.packageName) }
                    .forEach { pi ->
                        val nom = try { pi.applicationInfo?.loadLabel(packageManager)?.toString() ?: "" } catch (e: Exception) { "" }
                        apps.put(pi.packageName + " " + (pi.versionName ?: "") + if (nom.isNotBlank()) " «$nom»" else "")
                    }
            } catch (e: Exception) { apps.put("error: " + e.message) }
            j.put("apps", apps)
            val props = JSONArray()
            try {
                val p = Runtime.getRuntime().exec("getprop")
                val re = Regex("ro\\.product\\.|ro\\.board|ro\\.hardware|ro\\.build\\.display|print|cash|drawer|pos\\.|serial|uart|ro\\.vendor\\.build\\.fingerprint", RegexOption.IGNORE_CASE)
                p.inputStream.bufferedReader().readLines().filter { re.containsMatchIn(it) }.take(150).forEach { props.put(it) }
            } catch (e: Exception) { props.put("error: " + e.message) }
            j.put("propiedades", props)
            j.put("stty", try { val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v stty || command -v toybox")); p.inputStream.bufferedReader().readText().trim() } catch (e: Exception) { "" })
        } catch (e: Exception) { j.put("error", e.toString()) }
        return j.toString()
    }

    /** Prueba de cajón por un fichero del sistema (GPIO): lee el valor, pone el contrario 0,2 s y lo deja como estaba. */
    private fun probarCajonSistema(ruta: String) {
        colaImpresion.execute {
            var error = ""
            try {
                val f = File(ruta)
                val antes = try { f.readText().trim() } catch (e: Exception) { "" }
                val pulso = if (antes == "1") "0" else "1"
                val vuelta = if (antes == "1") "1" else "0"
                fun poner(v: String) {
                    try { FileOutputStream(f).use { it.write(v.toByteArray()); it.flush() } }
                    catch (e: Exception) {
                        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo $v > '$ruta'"))
                        p.waitFor()
                        if (p.exitValue() != 0) throw RuntimeException("la tablet no deja escribir en $ruta")
                    }
                }
                poner(pulso); Thread.sleep(200); poner(vuelta)
            } catch (e: Exception) { error = e.message ?: e.toString() }
            val j = JSONObject().put("ok", error.isEmpty()).put("error", error).put("via", ruta)
            runOnUiThread { web.evaluateJavascript("window.ohkPRN&&window.ohkPRN(${JSONObject.quote(j.toString())})", null) }
        }
    }

    private fun ubicacionEncendida(): Boolean = try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else true
    } catch (e: Exception) { true }

    private fun abrirAjustesApp() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
    }

    /** Lo que el programa del kiosko puede llamar: window.OhanaTPV.* */
    inner class Puente {
        @JavascriptInterface fun cobrar(centimos: Int, ref: String) { runOnUiThread { cobrarTarjeta(centimos.toLong()) } }
        @JavascriptInterface fun cancelar() {
            runOnUiThread {
                cobroEnCurso?.cancel(object : Callback {
                    override fun onSuccess() {}
                    override fun onFailure(e: TerminalException) {}
                })
            }
        }
        @JavascriptInterface fun estado(): String = estado
        @JavascriptInterface fun listo(): Boolean = conectado
        @JavascriptInterface fun prueba(): Boolean = simulado
        @JavascriptInterface fun clave() { runOnUiThread { pedirClave() } }
        @JavascriptInterface fun imprimirHTML(html: String) { runOnUiThread { imprimirHtml(html) } }
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun impresoras(): String = listaImpresoras()
        @JavascriptInterface fun imprimirRaw(destino: String, b64: String) { imprimirDirecto(destino, Base64.decode(b64, Base64.DEFAULT)) }
        @JavascriptInterface fun permisos() { runOnUiThread { abrirAjustesApp() } }
        @JavascriptInterface fun diagnostico(): String = diagnosticoJson()
        @JavascriptInterface fun cajonSistema(ruta: String) { probarCajonSistema(ruta) }
        // Tablet KOZEN: cajón e impresora del fabricante (dueño, 25 nov 2026)
        @JavascriptInterface fun kozen(): Boolean = Kozen.es(this@MainActivity)
        @JavascriptInterface fun kozenEstado(): String = Kozen.estado(this@MainActivity)
        @JavascriptInterface fun cajonKozen() {
            colaImpresion.execute {
                val e = Kozen.abrirCajon()
                val j = JSONObject().put("ok", e.isEmpty()).put("error", e).put("via", "cajón de la tablet")
                runOnUiThread { web.evaluateJavascript("window.ohkPRN&&window.ohkPRN(${JSONObject.quote(j.toString())})", null) }
            }
        }
    }
}
