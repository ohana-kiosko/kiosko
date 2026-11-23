// Panel del dueño en el móvil y copia en internet del kiosko (dueño, 21 nov 2026: «quiero ver en el móvil las horas de los
// camareros, con su horario ordinario y que cuente solas las horas extra, y las cajas»). Todo separado de la escuela.
//
// Datos: almacén privado de Vercel Blob «kiosko-blob» (Frankfurt). Dos ficheros:
//   kiosko/estado.json  lo sube la tablet: cajas cerradas, caja abierta, fichajes y camareros que conoce
//   kiosko/config.json  lo guarda el dueño desde el panel: camareros, horas ordinarias por semana, € por hora extra y
//                       correcciones de fichajes (salidas olvidadas, fichajes a mano o borrados)
// Variables en Vercel (nunca en el código: el repositorio es público):
//   BLOB_READ_WRITE_TOKEN  la puso Vercel al conectar el almacén con el proyecto
//   TPV_CLAVE              la clave larga que inventó el dueño (la misma de la tablet). Con ella se entra en el panel y se
//                          conecta la tablet una vez. Si se cambia en Vercel, hay que volver a entrar en los dos sitios.
//
//   POST {a:'entrar', clave, quien:'tpv'|'dueno'}  -> {tok}
//   POST {a:'subir', datos}            (cabecera x-tok de la tablet)  -> guarda estado.json
//   GET  ?a=config                     (tablet o dueño)               -> config.json
//   GET  ?a=todo                       (dueño)                        -> {estado, config}
//   POST {a:'config', config}          (dueño)                        -> guarda config.json
//   GET  ?a=salud                      (sin clave)                    -> comprueba que el almacén lee y escribe
//   POST {a:'diag', datos}             (tablet)                       -> diagnóstico de impresora y cajón (kiosko/diag.json)
//   GET  ?a=diag                       (sin clave)                    -> lo lee Claude: solo datos del aparato (USB, puertos, apps), sin ventas
//   POST {a:'vivo', datos}             (tablet)                       -> lo que pasa ahora en caja (cobros de hoy, mesas, faltas…)
//   GET  ?a=vivo                       (dueño)                        -> {vivo, modo}
//
// «En directo» (dueño, 23 nov 2026: «que pueda ir viendo lo que va pasando en caja»). Si el proyecto tiene una base Redis
// conectada (variables KV_REST_API_URL y KV_REST_API_TOKEN, o UPSTASH_REDIS_REST_URL y UPSTASH_REDIS_REST_TOKEN, las pone
// Vercel al conectarla), lo de ahora va ahí y la tablet lo manda cada 30 s (modo 'redis'). Si no, va al almacén Blob y la
// tablet lo manda cada 10-15 min para no pasar de las escrituras gratis (modo 'blob'). Se cambia solo, sin tocar código.
// El plan gratis da 2.000 escrituras al mes: la tablet sube solo al cerrar o abrir caja, al fichar y, como mucho, una vez
// por hora si hubo ventas.
const crypto = require('crypto');

const API_BLOB = 'https://vercel.com/api/blob/';
const tokBlob = () => process.env.BLOB_READ_WRITE_TOKEN || '';
const tienda = t => (t.split('_')[3] || '');

async function leer(ruta) {
  const t = tokBlob();
  const r = await fetch('https://' + tienda(t) + '.private.blob.vercel-storage.com/' + ruta + '?cache=0', { headers: { authorization: 'Bearer ' + t } });
  if (r.status === 404) return null;
  if (!r.ok) { console.error('Blob leer', ruta, r.status); throw new Error('No se pudo leer el almacén (' + r.status + ')'); }
  return r.json();
}

async function escribir(ruta, obj) {
  const t = tokBlob();
  const r = await fetch(API_BLOB + '?pathname=' + encodeURIComponent(ruta), {
    method: 'PUT',
    headers: {
      authorization: 'Bearer ' + t, 'x-api-version': '13', 'x-vercel-blob-store-id': tienda(t), 'x-vercel-blob-access': 'private',
      'x-add-random-suffix': '0', 'x-allow-overwrite': '1', 'x-content-type': 'application/json', 'x-cache-control-max-age': '60',
    },
    body: JSON.stringify(obj),
  });
  if (!r.ok) { console.error('Blob escribir', ruta, r.status, (await r.text()).slice(0, 300)); throw new Error('No se pudo guardar en el almacén (' + r.status + ')'); }
  return true;
}

const RURL = () => (process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL || '').replace(/\/$/, '');
const RTOK = () => process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN || '';
const hayRedis = () => !!(RURL() && RTOK());
async function redis(cmd) {
  const r = await fetch(RURL(), { method: 'POST', headers: { authorization: 'Bearer ' + RTOK(), 'content-type': 'application/json' }, body: JSON.stringify(cmd) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || j.error) { console.error('Redis', r.status, j.error); throw new Error('No se pudo usar la base en directo'); }
  return j.result;
}
const leeVivo = async () => hayRedis() ? JSON.parse((await redis(['GET', 'kiosko:vivo'])) || 'null') : leer('kiosko/vivo.json');
const guardaVivo = async d => hayRedis() ? redis(['SET', 'kiosko:vivo', JSON.stringify(d)]) : escribir('kiosko/vivo.json', d);

const firma = (C, txt) => crypto.createHmac('sha256', 'panel-kiosko|' + C).update(txt).digest('base64url');
function quienEs(req, C) {
  const tok = String(req.headers['x-tok'] || '');
  const [q, al, f] = tok.split('.');
  if (!q || !al || !f || !['tpv', 'dueno'].includes(q)) return null;
  const b = firma(C, q + '.' + al), a1 = Buffer.from(f), a2 = Buffer.from(b);
  return a1.length === a2.length && crypto.timingSafeEqual(a1, a2) ? q : null;
}

async function cuerpo(req) {
  if (req.body && typeof req.body === 'object') return req.body;
  if (typeof req.body === 'string') { try { return JSON.parse(req.body); } catch (e) { return {}; } }
  const trozos = []; for await (const c of req) trozos.push(c);
  try { return JSON.parse(Buffer.concat(trozos).toString('utf8') || '{}'); } catch (e) { return {}; }
}

module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const C = String(process.env.TPV_CLAVE || '').trim();
  const faltan = [!C && 'TPV_CLAVE', !tokBlob() && 'BLOB_READ_WRITE_TOKEN'].filter(Boolean);
  if (faltan.length) return res.status(503).json({ error: 'Faltan variables en Vercel: ' + faltan.join(', ') });
  const a = (req.query && req.query.a) || '';
  try {
    if (req.method === 'GET' && a === 'salud') {
      const s = await leer('kiosko/salud.json');
      let escrito = false;
      if (!s) { await escribir('kiosko/salud.json', { t: Date.now() }); escrito = true; } // solo la primera vez: no gasta escrituras
      const otra = await leer('kiosko/salud.json');
      return res.status(200).json({ ok: !!(otra && otra.t), escrito });
    }
    if (req.method === 'GET' && a === 'diag') return res.status(200).json({ diag: (await leer('kiosko/diag.json')) || null });
    if (req.method === 'GET') {
      const q = quienEs(req, C);
      if (!q) return res.status(401).json({ error: 'Hay que volver a entrar.' });
      if (a === 'config') return res.status(200).json({ config: (await leer('kiosko/config.json')) || {} });
      if (a === 'todo' && q === 'dueno') {
        const [estado, config, vivo] = await Promise.all([leer('kiosko/estado.json'), leer('kiosko/config.json'), leeVivo().catch(() => null)]);
        return res.status(200).json({ estado: estado || null, config: config || {}, vivo: vivo || null, modo: hayRedis() ? 'redis' : 'blob' });
      }
      if (a === 'vivo' && q === 'dueno') return res.status(200).json({ vivo: (await leeVivo()) || null, modo: hayRedis() ? 'redis' : 'blob' });
      return res.status(400).json({ error: 'Acción no válida' });
    }
    if (req.method !== 'POST') return res.status(405).json({ error: 'Método no válido' });
    const b = await cuerpo(req);
    if (b.a === 'entrar') {
      const quien = b.quien === 'tpv' ? 'tpv' : 'dueno';
      const dada = Buffer.from(String(b.clave || '').trim()), buena = Buffer.from(C);
      if (dada.length !== buena.length || !crypto.timingSafeEqual(dada, buena)) {
        await new Promise(r => setTimeout(r, 800));
        return res.status(401).json({ error: 'Clave incorrecta. Es la clave larga del TPV que pusiste en Vercel (TPV_CLAVE).' });
      }
      const al = crypto.randomBytes(12).toString('base64url');
      return res.status(200).json({ tok: quien + '.' + al + '.' + firma(C, quien + '.' + al) });
    }
    const q = quienEs(req, C);
    if (!q) return res.status(401).json({ error: 'Hay que volver a entrar.' });
    if (b.a === 'subir' && q === 'tpv') {
      const d = b.datos;
      if (!d || typeof d !== 'object' || !Array.isArray(d.cierres) || !Array.isArray(d.fichajes)) return res.status(400).json({ error: 'Datos no válidos' });
      d.recibido = Date.now();
      await escribir('kiosko/estado.json', d);
      return res.status(200).json({ ok: true, t: d.recibido });
    }
    if (b.a === 'vivo' && q === 'tpv') {
      const d = b.datos;
      if (!d || typeof d !== 'object' || JSON.stringify(d).length > 200000) return res.status(400).json({ error: 'Datos no válidos' });
      d.recibido = Date.now();
      await guardaVivo(d);
      return res.status(200).json({ ok: true, modo: hayRedis() ? 'redis' : 'blob' });
    }
    if (b.a === 'diag' && q === 'tpv') {
      const d = b.datos;
      if (!d || typeof d !== 'object' || JSON.stringify(d).length > 300000) return res.status(400).json({ error: 'Datos no válidos' });
      d.recibido = Date.now();
      await escribir('kiosko/diag.json', d);
      return res.status(200).json({ ok: true });
    }
    if (b.a === 'config' && q === 'dueno') {
      const c = b.config;
      if (!c || typeof c !== 'object' || !Array.isArray(c.camareros)) return res.status(400).json({ error: 'Datos no válidos' });
      if (JSON.stringify(c).length > 400000) return res.status(413).json({ error: 'Demasiados datos' });
      c.t = Date.now();
      await escribir('kiosko/config.json', c);
      return res.status(200).json({ ok: true, t: c.t });
    }
    return res.status(400).json({ error: 'Acción no válida' });
  } catch (e) {
    return res.status(502).json({ error: e.message || 'Error del servidor' });
  }
};
