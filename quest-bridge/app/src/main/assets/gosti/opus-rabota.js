// Работник звука гостя (Quest Claude Bridge 2, 2.5.0): libopus в WebAssembly (opus-encdec, MIT + BSD) —
// кодирует голос гостя (48 кГц моно, 20 мс) и раскодирует его смесь из шлема. Захват (AudioWorklet
// «zahvat») шлёт сюда куски по своему порту, раскодированное уходит прямо в «vyvod» — без главного потока.
'use strict';
importScripts('libopus-encoder.wasm.min.js', 'libopus-decoder.wasm.min.js');

const E = self.OpusEncoderLib;
const D = self.OpusDecoderLib;
const KADR = 960;
let enc = 0, dec = 0;
let ePcm = 0, eOut = 0, dIn = 0, dPcm = 0;
let portZahvat = null, portVyvod = null;
let nakop = new Float32Array(KADR), nakopN = 0;
let seq = 0;
let govorit = false;
let urovenSum = 0, urovenN = 0;

function gotov(lib) {
  return new Promise((r) => { if (lib.isReady) r(); else lib.onready = r; });
}

function ctl(lib, st, kod, znach) {
  const p = lib._malloc(4);
  lib.HEAP32[p >> 2] = znach;
  const r = lib._opus_encoder_ctl ? lib._opus_encoder_ctl(st, kod, p) : lib._opus_decoder_ctl(st, kod, p);
  lib._free(p);
  return r;
}

async function zapusk(nastr) {
  await Promise.all([gotov(E), gotov(D)]);
  const err = E._malloc(4);
  enc = E._opus_encoder_create(48000, 1, 2048 /* OPUS_APPLICATION_VOIP */, err);
  const e1 = E.HEAP32[err >> 2];
  E._free(err);
  if (!enc || e1 !== 0) throw new Error('opus encoder ' + e1);
  ctl(E, enc, 4002, nastr.bitrate || 48000);          // OPUS_SET_BITRATE
  ctl(E, enc, 4010, 8);                               // OPUS_SET_COMPLEXITY
  ctl(E, enc, 4024, 3001);                            // OPUS_SET_SIGNAL: голос
  ctl(E, enc, 4012, 0);                               // без FEC: TCP не теряет
  ePcm = E._malloc(KADR * 4);
  eOut = E._malloc(1500);
  const err2 = D._malloc(4);
  dec = D._opus_decoder_create(48000, 1, err2);
  const e2 = D.HEAP32[err2 >> 2];
  D._free(err2);
  if (!dec || e2 !== 0) throw new Error('opus decoder ' + e2);
  dIn = D._malloc(1500);
  dPcm = D._malloc(5760 * 4);
}

// кусок захвата (48 кГц) → копим по 960 → Opus → главному (в WebSocket)
function zahvat(f) {
  let i = 0;
  while (i < f.length) {
    const m = Math.min(KADR - nakopN, f.length - i);
    nakop.set(f.subarray(i, i + m), nakopN);
    nakopN += m; i += m;
    if (nakopN === KADR) { kodirovat(nakop); nakopN = 0; }
  }
}

function kodirovat(pcm) {
  let s = 0;
  for (let j = 0; j < KADR; j++) s += pcm[j] * pcm[j];
  urovenSum += s / KADR; urovenN++;
  if (urovenN >= 5) {                                  // 100 мс — уровень для полоски на экране
    const db = 10 * Math.log10(urovenSum / urovenN + 1e-12);
    postMessage({ t: 'uroven', db });
    urovenSum = 0; urovenN = 0;
  }
  E.HEAPF32.set(pcm, ePcm >> 2);
  const n = E._opus_encode_float(enc, ePcm, KADR, eOut, 1500);
  if (n <= 0) return;
  const d = new Uint8Array(3 + n);
  d[0] = 1; d[1] = (seq >> 8) & 255; d[2] = seq & 255;
  d.set(E.HEAPU8.subarray(eOut, eOut + n), 3);
  seq = (seq + 1) & 0xffff;
  postMessage({ t: 'paket', d }, [d.buffer]);
}

// пакет смеси из шлема → PCM → прямо в «vyvod»
function dekodirovat(paket) {
  if (paket.length > 1500) return;
  D.HEAPU8.set(paket, dIn);
  const n = D._opus_decode_float(dec, dIn, paket.length, dPcm, 5760, 0);
  if (n <= 0) return;
  const pcm = new Float32Array(n);
  pcm.set(D.HEAPF32.subarray(dPcm >> 2, (dPcm >> 2) + n));
  if (portVyvod) portVyvod.postMessage(pcm, [pcm.buffer]);
}

onmessage = async (e) => {
  const m = e.data;
  if (m.t === 'init') {
    portZahvat = e.ports[0];
    portVyvod = e.ports[1];
    try {
      await zapusk(m);
      portZahvat.onmessage = (ev) => zahvat(ev.data);
      postMessage({ t: 'gotov' });
    } catch (x) {
      postMessage({ t: 'oshibka', tekst: String(x && x.message || x) });
    }
  } else if (m.t === 'dekod') {
    dekodirovat(new Uint8Array(m.d));
  } else if (m.t === 'sbros') {
    nakopN = 0;
  }
};
