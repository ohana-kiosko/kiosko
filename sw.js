// Guarda el programa en el aparato para que abra aunque no haya internet.
const V='kiosko-v4'; // 20 nov 2026: el programa siempre se pide nuevo a internet (sin caché) y solo se usa la copia guardada sin wifi
self.addEventListener('install',e=>{e.waitUntil(caches.open(V).then(c=>c.addAll(['/','/manifest.webmanifest','/icon-192.png','/icon-512.png'])));self.skipWaiting()});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==V).map(x=>caches.delete(x)))));self.clients.claim()});
self.addEventListener('fetch',e=>{const r=e.request;if(r.method!=='GET')return;
  if(r.mode==='navigate'){e.respondWith(fetch(r,{cache:'no-store'}).then(x=>{const y=x.clone();caches.open(V).then(c=>c.put('/',y));return x}).catch(()=>caches.match('/')));return}
  e.respondWith(caches.match(r).then(x=>x||fetch(r).then(y=>{if(y.ok&&new URL(r.url).origin===location.origin){const z=y.clone();caches.open(V).then(c=>c.put(r,z))}return y})))});
