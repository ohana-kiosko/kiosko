// Cobro con tarjeta en la propia tablet (app Android «Kiosko Ohana», Stripe Tap to Pay). Dueño, 19 nov 2026.
// Variables en Vercel (las pone el dueño, nunca en el código: el repositorio es público):
//   STRIPE_SECRET_KEY  clave secreta de la cuenta Stripe «KIOSKO» (sk_test_… para probar, sk_live_… para cobrar de verdad)
//   TPV_CLAVE          clave que se teclea una vez en la tablet (larga, inventada por el dueño)
//   STRIPE_LOCATION    (opcional) id de la ubicación en Stripe › Terminal › Ubicaciones (tml_…). Si no vale para
//                      la clave puesta (p. ej. al pasar de prueba a real), se busca o se crea sola «Kiosko 3 Piedras».
// GET  /api/tpv?a=config → { location, simulado }   (simulado = clave de prueba)
// POST /api/tpv?a=token  → { secret }                (ConnectionToken de Stripe Terminal)
const crypto = require('crypto');

const NOMBRE = 'Kiosko 3 Piedras';
const DIRECCION = { line1: 'Playa de las Tres Piedras', city: 'Chipiona', postal_code: '11550', state: 'CA', country: 'ES' };
let cacheUbicacion = null; // { clave, id }

async function stripe(K, metodo, ruta, cuerpo) {
  const r = await fetch('https://api.stripe.com/v1/' + ruta, {
    method: metodo,
    headers: { Authorization: 'Bearer ' + K, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: cuerpo ? new URLSearchParams(cuerpo).toString() : undefined,
  });
  const j = await r.json();
  // Para ver en Vercel › Logs qué contesta Stripe (solo el tipo de clave, nunca la clave).
  if (!r.ok) console.error('Stripe', metodo, ruta.split('?')[0], r.status, (j.error && j.error.message) || '', 'clave:', K.slice(0, 8));
  return { ok: r.ok, j };
}

// La ubicación tiene que ser del mismo modo (prueba o real) que la clave.
async function ubicacion(K, L) {
  const huella = crypto.createHash('sha256').update(K).digest('hex').slice(0, 12);
  if (cacheUbicacion && cacheUbicacion.clave === huella) return cacheUbicacion.id;
  let id = null;
  if (L) {
    const r = await stripe(K, 'GET', 'terminal/locations/' + encodeURIComponent(L));
    if (r.ok && !r.j.deleted) id = r.j.id;
  }
  if (!id) {
    const lista = await stripe(K, 'GET', 'terminal/locations?limit=100');
    const hay = lista.ok && (lista.j.data || []).find(x => x.display_name === NOMBRE);
    if (hay) id = hay.id;
  }
  if (!id) {
    const c = { display_name: NOMBRE };
    for (const k in DIRECCION) c['address[' + k + ']'] = DIRECCION[k];
    const r = await stripe(K, 'POST', 'terminal/locations', c);
    if (!r.ok) throw new Error('Stripe: ' + ((r.j.error && r.j.error.message) || 'no se pudo crear la ubicación'));
    id = r.j.id;
  }
  cacheUbicacion = { clave: huella, id };
  return id;
}

module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const K = process.env.STRIPE_SECRET_KEY, L = process.env.STRIPE_LOCATION, C = process.env.TPV_CLAVE;
  const faltan = [!K && 'STRIPE_SECRET_KEY', !C && 'TPV_CLAVE'].filter(Boolean);
  if (faltan.length) return res.status(503).json({ error: 'Faltan variables en Vercel: ' + faltan.join(', ') });

  // Sin espacios al principio ni al final (al copiar o teclear se cuelan fácil).
  const dada = Buffer.from(String(req.headers['x-tpv-clave'] || '').trim());
  const buena = Buffer.from(String(C).trim());
  if (dada.length !== buena.length || !crypto.timingSafeEqual(dada, buena)) {
    return res.status(401).json({ error: 'Clave del TPV incorrecta' });
  }

  const a = (req.query && req.query.a) || '';
  try {
    if (a === 'config') {
      return res.status(200).json({ location: await ubicacion(K, L), simulado: /^(sk|rk)_test_/.test(K) });
    }
    if (a === 'token' && req.method === 'POST') {
      const r = await stripe(K, 'POST', 'terminal/connection_tokens', { location: await ubicacion(K, L) });
      if (!r.ok) return res.status(502).json({ error: 'Stripe: ' + ((r.j.error && r.j.error.message) || 'error') });
      return res.status(200).json({ secret: r.j.secret });
    }
  } catch (e) {
    return res.status(502).json({ error: e.message || 'No se pudo hablar con Stripe' });
  }
  return res.status(400).json({ error: 'Acción no válida' });
};
