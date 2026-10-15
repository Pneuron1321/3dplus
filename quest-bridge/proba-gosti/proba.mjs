// Проба страницы гостя на столе (2.5.0): Chromium с поддельными камерой (узор) и микрофоном (тон 440 Гц).
// Модератор входит, гость ждёт, модератор впускает; обмен звуком и картинкой; убрать с экрана; выгнать.
//   node proba.mjs ПОРТ ПАПКА_ОТЧЁТА ПУТЬ_К_PLAYWRIGHT ПУТЬ_К_CHROMIUM ТОН.wav
import fs from 'node:fs';
const [port, out, pw, chrome, ton] = process.argv.slice(2);
const { chromium, devices } = await import(pw);
const zhdatFayl = async (f, ms = 60000) => { const k = Date.now() + ms; while (!fs.existsSync(f)) { if (Date.now() > k) throw new Error('нет ' + f); await new Promise((r) => setTimeout(r, 200)); } };
await zhdatFayl(out + '/klyuchi.json');
const kl = JSON.parse(fs.readFileSync(out + '/klyuchi.json', 'utf8'));
const b = await chromium.launch({ executablePath: chrome, args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
  '--use-file-for-fake-audio-capture=' + ton, '--autoplay-policy=no-user-gesture-required'] });
const itog = { shagi: [], oshibki: [] };
const shag = (s) => { itog.shagi.push(s); console.log('✓', s); };
const baza = `http://127.0.0.1:${port}/?proba`;
async function stranica(ctxOpts) {
  const c = await b.newContext(Object.assign({ permissions: ['camera', 'microphone'], locale: 'ru-RU' }, ctxOpts || {}));
  const p = await c.newPage();
  p.on('pageerror', (e) => itog.oshibki.push('pageerror: ' + e.message));
  p.on('console', (m) => { if (m.type() === 'error') itog.oshibki.push('console: ' + m.text()); });
  return p;
}
const vidno = (p, id) => p.waitForSelector('#' + id + ':not([hidden])', { timeout: 15000 });
try {
  // модератор — компьютер
  const m = await stranica({ viewport: { width: 1200, height: 900 } });
  await m.goto(baza + '#k=' + kl.moder + '&r=m');
  if ((await m.textContent('#tVhodZag')).indexOf('модератор') < 0) throw new Error('нет подсказки роли');
  if (await m.evaluate(() => location.hash) !== '') throw new Error('ключ остался в адресе');
  await m.fill('#imya', 'Мод');
  await m.click('#knRazreshit');
  await vidno(m, 'knVoyti');
  shag('модератор: камера и микрофон');
  await m.click('#knVoyti');
  await vidno(m, 'ekranEfir'); await vidno(m, 'pult');
  shag('модератор внутри, пульт виден');
  // гость — телефон
  const g = await stranica(Object.assign({}, devices['iPhone 13'], { locale: 'en-US' }));
  await g.goto(baza + '#k=' + kl.gost);
  await g.fill('#imya', 'Kate');
  await g.click('#knRazreshit');
  await vidno(g, 'knVoyti');
  await g.click('#knVoyti');
  await vidno(g, 'ekranZhdi');
  shag('гость ждёт впуска (страница по-английски: ' + (await g.textContent('#tZhdiZag')) + ')');
  await m.waitForSelector('.zhdut button.da', { timeout: 10000 });
  await m.screenshot({ path: out + '/moder-zhdet.png' });
  await m.click('.zhdut button.da');
  await vidno(g, 'ekranEfir');
  shag('модератор впустил гостя');
  await new Promise((r) => setTimeout(r, 9000));                        // звук и картинка 9 с
  const sg = await g.evaluate(() => { const S = window.__plus.S; return { bufer: S.buferMs, rtt: S.rttMs, kodek: S.vencCfg && S.vencCfg.codec, cvet: S.cvet, lyudi: S.lyudi.length, zametka: document.getElementById('zametka').textContent }; });
  const sm = await m.evaluate(() => { const S = window.__plus.S; return { bufer: S.buferMs, rtt: S.rttMs, kodek: S.vencCfg && S.vencCfg.codec, cvet: S.cvet, lyudi: S.lyudi.length }; });
  itog.gost = sg; itog.moder = sm;
  if (!(sg.bufer > 0 && sm.bufer > 0)) throw new Error('смесь из шлема не играет: ' + JSON.stringify([sg, sm]));
  shag('оба слышат смесь (буфер ' + sg.bufer + ' / ' + sm.bufer + ' мс), кодек камеры ' + sg.kodek);
  await g.screenshot({ path: out + '/gost-v-efire.png' });
  await m.screenshot({ path: out + '/moder-v-efire.png', fullPage: true });
  // убрать с экрана — у гостя заметка
  await m.click('#pultLyudi .pultKto button:nth-of-type(1)');
  await g.waitForFunction(() => /hid you|убрал/.test(document.getElementById('zametka').textContent), null, { timeout: 5000 });
  shag('модератор убрал гостя с экрана');
  await m.click('#pultLyudi .pultKto button:nth-of-type(1)');           // показать снова
  // выгнать
  await m.click('#pultLyudi .pultKto button.kr');
  await vidno(g, 'ekranKonec');
  shag('модератор выгнал гостя: «' + (await g.textContent('#konecPod')) + '»');
} catch (x) {
  itog.oshibki.push(String(x && x.stack || x));
  console.log('✗', x && x.message);
} finally {
  fs.writeFileSync(out + '/proba.json', JSON.stringify(itog, null, 1));
  fs.writeFileSync(out + '/stop', '');
  await b.close();
}
