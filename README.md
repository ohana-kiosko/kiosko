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
