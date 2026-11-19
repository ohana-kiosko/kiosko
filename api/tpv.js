// Cobro con tarjeta en la propia tablet (app Android «Kiosko Ohana», Stripe Tap to Pay). Dueño, 19 nov 2026.
// Variables en Vercel (las pone el dueño, nunca en el código: el repositorio es público):
//   STRIPE_SECRET_KEY  clave secreta de la cuenta Stripe «Ohana Kiosko» (sk_test_… para probar, sk_live_… para cobrar de verdad)
//   STRIPE_LOCATION    id de la ubicación del kiosko en Stripe › Terminal › Ubicaciones (tml_…)
//   TPV_CLAVE          clave que se teclea una vez en la tablet (larga, inventada por el dueño)
// GET  /api/tpv?a=config → { location, simulado }   (simulado = clave de prueba)
// POST /api/tpv?a=token  → { secret }                (ConnectionToken de Stripe Terminal)
const crypto = require('crypto');

module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const K = process.env.STRIPE_SECRET_KEY, L = process.env.STRIPE_LOCATION, C = process.env.TPV_CLAVE;
  const faltan = [!K && 'STRIPE_SECRET_KEY', !L && 'STRIPE_LOCATION', !C && 'TPV_CLAVE'].filter(Boolean);
  if (faltan.length) return res.status(503).json({ error: 'Faltan variables en Vercel: ' + faltan.join(', ') });

  const dada = Buffer.from(String(req.headers['x-tpv-clave'] || ''));
  const buena = Buffer.from(C);
  if (dada.length !== buena.length || !crypto.timingSafeEqual(dada, buena)) {
    return res.status(401).json({ error: 'Clave del TPV incorrecta' });
  }

  const a = (req.query && req.query.a) || '';
  if (a === 'config') {
    return res.status(200).json({ location: L, simulado: /^(sk|rk)_test_/.test(K) });
  }
  if (a === 'token' && req.method === 'POST') {
    try {
      const r = await fetch('https://api.stripe.com/v1/terminal/connection_tokens', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + K, 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'location=' + encodeURIComponent(L),
      });
      const j = await r.json();
      if (!r.ok) return res.status(502).json({ error: 'Stripe: ' + ((j.error && j.error.message) || r.status) });
      return res.status(200).json({ secret: j.secret });
    } catch (e) {
      return res.status(502).json({ error: 'No se pudo hablar con Stripe' });
    }
  }
  return res.status(400).json({ error: 'Acción no válida' });
};
