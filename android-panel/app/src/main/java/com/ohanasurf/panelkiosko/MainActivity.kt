package com.ohanasurf.panelkiosko

// App «Panel Kiosko» (dueño, 21 nov 2026): abre el panel del dueño (kiosko-topaz.vercel.app/panel/) como una app:
// cajas del kiosko y horas de los camareros. La clave del TPV se teclea una vez en la app y se queda guardada en el móvil.
// Ninguna clave va en el código (el repositorio es público).

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var sinRed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#F1EFEA"))
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                // El panel se queda dentro de la app; cualquier otro enlace se abre fuera.
                if (u.host == Uri.parse(BuildConfig.PANEL_URL).host) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) {}
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { sinRed = true; avisoSinRed() }
            }
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(BuildConfig.PANEL_URL)
    }

    private fun avisoSinRed() {
        val html = """<!doctype html><html lang="es"><meta name="viewport" content="width=device-width,initial-scale=1">
            <body style="margin:0;background:#F1EFEA;color:#1A1714;font:16px/1.45 system-ui,sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center;padding:24px;box-sizing:border-box">
            <div style="background:#fff;border:1px solid #E2DDD3;border-radius:14px;padding:20px;max-width:360px;text-align:center">
            <p style="font-size:18px;font-weight:600;margin:0 0 8px">Sin conexión</p>
            <p style="margin:0 0 16px;color:#5E5850">El panel necesita internet para ver las cajas y las horas. Comprueba los datos o el wifi.</p>
            <a href="${BuildConfig.PANEL_URL}" style="display:inline-block;background:#C2560F;color:#fff;text-decoration:none;font-weight:600;border-radius:10px;padding:12px 18px">Volver a intentar</a>
            </div></body></html>"""
        web.loadDataWithBaseURL(BuildConfig.PANEL_URL, html, "text/html", "utf-8", null)
    }

    override fun onResume() {
        super.onResume()
        if (sinRed) { sinRed = false; web.loadUrl(BuildConfig.PANEL_URL) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Atrás: cierra la ventana abierta del panel; si no hay ninguna, sale de la app")
    override fun onBackPressed() {
        web.evaluateJavascript("(function(){var s=document.querySelectorAll('.sheet');if(s.length){s[s.length-1].remove();return 1}return 0})()") { r ->
            if (r != "1") {
                if (web.canGoBack()) web.goBack() else finish()
            }
        }
    }
}
