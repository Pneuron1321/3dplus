// Проверка перевода H.264 на странице гостя (gost.js): Annex B ⇄ AVC — на настоящем потоке ffmpeg.
//   node h264.mjs gost.js поток.h264
import fs from 'node:fs';
import vm from 'node:vm';
const [js, potok] = process.argv.slice(2);
const src = fs.readFileSync(js, 'utf8');
const vzyat = (imya) => { const i = src.indexOf('  function ' + imya + '('); const j = src.indexOf('\n  }\n', i); return src.slice(i, j + 4); };
const ctx = { S: { avcc: null }, Uint8Array };
vm.createContext(ctx);
vm.runInContext(vzyat('razborAvcC') + vzyat('annexB') + vzyat('vAvcc') + '; this.razborAvcC = razborAvcC; this.annexB = annexB; this.vAvcc = vAvcc;', ctx);
const d = new Uint8Array(fs.readFileSync(potok));
// NAL по стартовым кодам 00 00 01 и 00 00 00 01; кадр — до среза (тип 1 или 5) включительно
const nach = [];                                     // [начало кода, начало NAL]
for (let i = 0; i + 3 < d.length; i++) {
  if (d[i] === 0 && d[i + 1] === 0 && d[i + 2] === 1) { nach.push([i > 0 && d[i - 1] === 0 ? i - 1 : i, i + 3]); i += 2; }
}
const kadry = []; let tek = [];
for (let k = 0; k < nach.length; k++) {
  const konec = k + 1 < nach.length ? nach[k + 1][0] : d.length;
  const t = d[nach[k][1]] & 31;
  tek.push(Buffer.concat([Buffer.from([0, 0, 0, 1]), d.subarray(nach[k][1], konec)]));
  if (t === 1 || t === 5) { kadry.push({ key: t === 5, d: Buffer.concat(tek) }); tek = []; }
}
let ok = 0;
for (const f of kadry) {
  const r = ctx.vAvcc(new Uint8Array(f.d), f.key);
  if (f.key && !r.opisanie) throw new Error('нет описания у ключевого');
  if (f.key) { ctx.S.avcc = ctx.razborAvcC(r.opisanie); if (ctx.S.avcc.sps.length !== 1 || ctx.S.avcc.pps.length !== 1 || ctx.S.avcc.len !== 4) throw new Error('avcC не разобрался'); }
  // обратно в Annex B — те же срезы (и SPS/PPS у ключевого)
  const back = ctx.annexB(r.d, f.key);
  const bezAud = (x) => { const o = []; const n = []; for (let j = 0; j + 4 <= x.length; j++) if (x[j] === 0 && x[j + 1] === 0 && x[j + 2] === 0 && x[j + 3] === 1) n.push(j); n.push(x.length); for (let k = 0; k + 1 < n.length; k++) { const t = x[n[k] + 4] & 31; if (t !== 9) o.push(Buffer.from(x.subarray(n[k], n[k + 1]))); } return Buffer.concat(o); };
  if (!bezAud(Buffer.from(back)).equals(bezAud(f.d))) throw new Error('туда-обратно не совпало, кадр ' + ok);
  ok++;
}
console.log('H.264 туда-обратно: кадров ' + ok + ', ключевых ' + kadry.filter((k) => k.key).length + ' — совпали');
