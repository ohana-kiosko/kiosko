# Kiosko Ohana Surf Tres Piedras

Programa de caja (TPV) del kiosko de la Playa de las Tres Piedras (Chipiona).
Cuenta propia del kiosko (kiosko@), separada de la escuela.

- `index.html`: el programa (de momento el prototipo aprobado el 13 nov 2026; los datos se guardan en el propio aparato).
- Se publica en Vercel desde la rama `main`.

## Versión primaria (18 nov 2026)
- Perfiles: Isidro (admin), Ely y Gonzalo. Cada uno crea su clave la primera vez en el aparato (se guarda cifrada en el aparato, nunca en el código: este repositorio es público).
- Sin datos de ejemplo. Todo se guarda en el propio TPV (localStorage). Cada día la caja empieza a cero.
- TPV: ZAZOO POS NB11 (Android 13, impresora de 80 mm integrada, cajón GEON por RJ11). Imprime con la app RawBT (intent `rawbt`, ESC/POS: ticket, corte, cajón) o con el diálogo de Android. Ajustes en «10. Productos y aparatos».
- Se instala como app (manifest + service worker) y abre sin internet.

## Fichaje y panel del dueño (21 nov 2026)
- Los camareros fichan **solo aquí**, con «Fichar entrada / Fichar salida». Ya no se llevan en la web de la escuela.
- Panel del dueño en el móvil: `/panel/` (web) y app Android «Panel Kiosko» (carpeta `android-panel/`, se compila en Actions › «App Panel Kiosko»). Descarga: https://github.com/ohana-kiosko/kiosko/releases/download/panel/panel-kiosko.apk
- Cajas cerradas (con datáfono, cambio que se deja y efectivo al sobre) y horas de los camareros (horas ordinarias por semana y extra calculadas solas).
- Servidor: `api/panel.js`, con un almacén privado de Vercel Blob («kiosko-blob»). Variables: `BLOB_READ_WRITE_TOKEN` (la pone Vercel) y `TPV_CLAVE`. Con `TPV_CLAVE` se entra en el panel y se conecta la tablet una vez («Equipo y sueldos» › «Conectar con el panel del móvil»). Si se cambia en Vercel, hay que publicar otra vez y volver a entrar en los dos sitios.
- En directo (23 nov 2026): la tablet manda «vivo» (cobros de hoy, mesas, pedidos…). Con la base Redis «ohana-club» conectada al proyecto (prefijo KV, claves `kiosko:*` aparte de la escuela) va cada 30 s; sin ella, al almacén Blob cada 10-20 min. Pestañas del panel: En directo, Cajas, Pedidos (WhatsApp a cada proveedor), Camareros y Ajustes.

## Cobro con tarjeta en la tablet (19 nov 2026)
- App Android «Kiosko Ohana» (carpeta `android/`): abre este programa a pantalla completa y cobra con el NFC de la tablet con Stripe Tap to Pay (cuenta Stripe «KIOSKO», separada de la escuela).
- Se compila sola en GitHub Actions («App Android»). Descarga: https://github.com/ohana-kiosko/kiosko/releases/latest/download/kiosko-ohana.apk
- Servidor: `api/tpv.js`. Variables en Vercel (las pone el dueño): `STRIPE_SECRET_KEY`, `STRIPE_LOCATION`, `TPV_CLAVE`.
- En Chrome el botón Tarjeta sigue con los pasos de BBVA Cobros.
- Si la tablet no acepta la clave del TPV: botón «Clave del TPV» en la ventana de cobro con tarjeta, o cambiarla en Vercel (TPV_CLAVE) y volver a publicar.
