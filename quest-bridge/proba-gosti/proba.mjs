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
  // 2.5.3 «🔇 Только голоса»: наушников не видно (у Chromium их нет) — включилась сама, сервер гасит игру; кнопка — вернулась
  const igraNaServere = () => g.evaluate(() => { const S = window.__plus.S; const ya = S.lyudi.find((x) => x.id === S.id); return ya ? ya.igra : null; });
  await g.waitForFunction(() => window.__plus.S.tolkoGolosa === true, null, { timeout: 5000 });
  await g.waitForFunction(() => { const S = window.__plus.S; const ya = S.lyudi.find((x) => x.id === S.id); return ya && ya.igra === 0; }, null, { timeout: 5000 });
  if (await g.isHidden('#golosaPodskazka')) throw new Error('нет подсказки «только голоса»');
  if (!(await g.getAttribute('#knGolosa', 'class')).includes('vkl')) throw new Error('кнопка «только голоса» не горит');
  await g.screenshot({ path: out + '/gost-tolko-golosa.png' });
  // вернуть игру — сначала вопрос «ты в наушниках?»: «нет» — игра так и выключена, «да» — 50 %
  await g.click('#knGolosa');
  await vidno(g, 'dialog');
  await g.screenshot({ path: out + '/gost-vopros-igra.png' });
  await g.click('#dialogNet');
  await new Promise((r) => setTimeout(r, 600));
  if (!(await g.isHidden('#dialog')) || !(await g.evaluate(() => window.__plus.S.tolkoGolosa)) || await igraNaServere() !== 0)
    throw new Error('«нет» вернул игру');
  await g.click('#knGolosa');
  await vidno(g, 'dialog');
  await g.click('#dialogDa');
  await g.waitForFunction(() => { const S = window.__plus.S; const ya = S.lyudi.find((x) => x.id === S.id); return ya && ya.igra === 0.5; }, null, { timeout: 5000 });
  if (!(await g.isHidden('#golosaPodskazka'))) throw new Error('подсказка осталась');
  await g.click('#knGolosa');                                           // выключить игру — без вопроса
  await g.waitForFunction(() => { const S = window.__plus.S; const ya = S.lyudi.find((x) => x.id === S.id); return ya && ya.igra === 0; }, null, { timeout: 5000 });
  if (!(await g.isHidden('#dialog'))) throw new Error('вопрос при выключении игры');
  shag('«Только голоса»: без наушников включилась сама (игра на сервере 0); вернуть — через вопрос «в наушниках?»: «нет» — без игры, «да» — 50 %');
  // 📻 рация: не держит — не слышно (модератор не видит «говорит»), держит — слышно и рамка у него в пульте
  const gid = await g.evaluate(() => window.__plus.S.id);
  const ryadG = '#pultLyudi .pultKto[data-id="' + gid + '"]';
  await m.waitForFunction((s) => { const d = document.querySelector(s); return d && d.classList.contains('govorit'); }, ryadG, { timeout: 8000 });
  await g.click('#knRaciya');
  await vidno(g, 'knGovori');
  await m.waitForFunction((s) => { const d = document.querySelector(s); return d && !d.classList.contains('govorit') && d.textContent.includes('📻'); }, ryadG, { timeout: 8000 });
  await g.dispatchEvent('#knGovori', 'pointerdown', { pointerId: 1, isPrimary: true });
  await m.waitForFunction((s) => { const d = document.querySelector(s); return d && d.classList.contains('govorit'); }, ryadG, { timeout: 8000 });
  await g.screenshot({ path: out + '/gost-raciya.png' });
  await m.screenshot({ path: out + '/moder-raciya.png', fullPage: true });
  await g.dispatchEvent('#knGovori', 'pointerup', { pointerId: 1, isPrimary: true });
  await m.waitForFunction((s) => { const d = document.querySelector(s); return d && !d.classList.contains('govorit'); }, ryadG, { timeout: 8000 });
  await g.click('#knRaciya');                                           // рацию выключил — снова открытый микрофон
  await m.waitForFunction((s) => { const d = document.querySelector(s); return d && d.classList.contains('govorit') && !d.textContent.includes('📻'); }, ryadG, { timeout: 8000 });
  shag('рация: без кнопки гостя не слышно, держит — слышно и у модератора рамка «говорит» и 📻; выключил — открытый микрофон');
  // убрать с экрана — у гостя заметка
  await m.click('#pultLyudi .pultKto button:nth-of-type(1)');
  await g.waitForFunction(() => /hid you|убрал/.test(document.getElementById('zametka').textContent), null, { timeout: 5000 });
  shag('модератор убрал гостя с экрана');
  await m.click('#pultLyudi .pultKto button:nth-of-type(1)');           // показать снова
  // выгнать
  await m.click('#pultLyudi .pultKto button.kr');
  await vidno(g, 'ekranKonec');
  shag('модератор выгнал гостя: «' + (await g.textContent('#konecPod')) + '»');
  // Android: гость сам выключает и включает свою камеру в эфире (огонёк гаснет, кадры не уходят)
  const a = await stranica(Object.assign({}, devices['Pixel 7'] || devices['Pixel 5'], { locale: 'ru-RU' }));
  await a.goto(baza + '#k=' + kl.gost);
  await a.fill('#imya', 'Pixel');
  await a.click('#knRazreshit'); await vidno(a, 'knVoyti'); await a.click('#knVoyti');
  await vidno(a, 'ekranZhdi');
  await m.waitForSelector('.zhdut button.da', { timeout: 10000 }); await m.click('.zhdut button.da');
  await vidno(a, 'ekranEfir');
  const otpr = () => a.evaluate(() => window.__plus.S.vidOtpr || 0);
  const kamU = () => m.evaluate(() => { const u = window.__plus.S.lyudi.find((x) => x.imya === 'Pixel'); return u && u.kamera; });
  await a.waitForFunction(() => (window.__plus.S.vidOtpr || 0) > 10, null, { timeout: 10000 });
  await a.click('#knKam2');
  await m.waitForFunction(() => { const u = window.__plus.S.lyudi.find((x) => x.imya === 'Pixel'); return u && u.kamera === false; }, null, { timeout: 5000 });
  const dorozhek = await a.evaluate(() => window.__plus.S.stream.getVideoTracks().length);
  const o1 = await otpr(); await new Promise((r) => setTimeout(r, 2000)); const o2 = await otpr();
  if (dorozhek !== 0 || o2 - o1 > 1) throw new Error('камера не погасла: дорожек ' + dorozhek + ', кадров за 2 с ' + (o2 - o1));
  shag('Android: гость выключил камеру — дорожка остановлена, кадров нет, модератор видит «камера выкл»');
  await a.click('#knKam2');
  await m.waitForFunction(() => { const u = window.__plus.S.lyudi.find((x) => x.imya === 'Pixel'); return u && u.kamera === true; }, null, { timeout: 5000 });
  const o3 = await otpr(); await new Promise((r) => setTimeout(r, 2000)); const o4 = await otpr();
  if (o4 - o3 < 10 || !(await kamU())) throw new Error('камера не вернулась: кадров за 2 с ' + (o4 - o3));
  shag('Android: камера снова в эфире (' + (o4 - o3) + ' кадров за 2 с), кодек ' + (await a.evaluate(() => window.__plus.S.vencCfg && window.__plus.S.vencCfg.codec)));
  await a.screenshot({ path: out + '/android-v-efire.png' });
  // модератор: камера и микрофон гостя (выкл — гаснет у гостя, своя кнопка не включает; вкл — камера вернулась сама)
  const ryad = () => m.locator('#pultLyudi .pultKto', { hasText: 'Pixel' });
  await ryad().locator('button').nth(2).click();                                  // 📷 выкл
  await a.waitForFunction(() => window.__plus.S.kameraZapret === true && window.__plus.S.stream.getVideoTracks().length === 0, null, { timeout: 5000 });
  await a.click('#knKam2');
  await new Promise((r) => setTimeout(r, 600));
  if (await a.evaluate(() => window.__plus.S.stream.getVideoTracks().length) !== 0) throw new Error('гость включил камеру сам при запрете');
  const z1 = await otpr(); await new Promise((r) => setTimeout(r, 1500));
  if (await otpr() - z1 > 1) throw new Error('кадры идут при запрете');
  shag('модератор выключил гостю камеру: погасла, своя кнопка не включает, кадров нет');
  await m.waitForFunction(() => { const r = [...document.querySelectorAll('#pultLyudi .pultKto')].find((e) => /Pixel/.test(e.textContent)); return r && /📷 (вкл|on)/.test(r.textContent); }, null, { timeout: 5000 });
  await ryad().locator('button').nth(2).click();                                  // 📷 вкл
  await a.waitForFunction(() => window.__plus.S.kameraZapret === false && window.__plus.S.stream.getVideoTracks().length === 1, null, { timeout: 6000 });
  const z2 = await otpr(); await new Promise((r) => setTimeout(r, 2000));
  if (await otpr() - z2 < 10) throw new Error('камера не вернулась после разрешения');
  shag('модератор разрешил камеру — вернулась сама (была включена)');
  await ryad().locator('button').nth(1).click();                                  // 🎤 выкл
  await a.waitForFunction(() => window.__plus.S.zaglushen === true, null, { timeout: 5000 });
  await m.waitForFunction(() => { const r = [...document.querySelectorAll('#pultLyudi .pultKto')].find((e) => /Pixel/.test(e.textContent)); return r && /🎤 (вкл|on)/.test(r.textContent); }, null, { timeout: 5000 });
  await ryad().locator('button').nth(1).click();                                  // 🎤 вкл
  await a.waitForFunction(() => window.__plus.S.zaglushen === false, null, { timeout: 5000 });
  shag('модератор выключил и включил гостю микрофон');
  // страж дорожек: микрофон умер (звонок на iPhone) — страница подняла его сама
  const star = await a.evaluate(() => { const t = window.__plus.S.stream.getAudioTracks()[0]; t.dispatchEvent(new Event('ended')); return t.id; });
  await a.waitForFunction((id) => { const t = window.__plus.S.stream.getAudioTracks()[0]; return t && t.id !== id && t.readyState === 'live'; }, star, { timeout: 6000 });
  shag('страж: умерший микрофон поднят заново');
  // сокет умер молча (iPhone после фона): сервер не отвечает 15 с — страница звонит сама, место за ней
  const ws0 = await a.evaluate(() => { window.__plus.ws0 = window.__plus.S.ws; window.__plus.S.pongMs = Date.now() - 20000; return true; });
  await a.waitForFunction(() => window.__plus.S.ws && window.__plus.S.ws !== window.__plus.ws0 && window.__plus.S.ws.readyState === 1 && window.__plus.S.vnutri, null, { timeout: 12000 });
  await m.waitForFunction(() => window.__plus.S.lyudi.filter((x) => x.imya === 'Pixel').length === 1 && window.__plus.S.lyudi.find((x) => x.imya === 'Pixel').sost === 'vnutri', null, { timeout: 6000 });
  shag('мёртвый сокет: переподключилась сама, место и цвет — те же, без ожидания (' + ws0 + ')');
  // встроенный браузер приложения без камеры и микрофона — подсказка, куда открыть ссылку
  const w = await stranica(devices['iPhone 13']);
  await w.addInitScript(() => Object.defineProperty(navigator, 'mediaDevices', { value: undefined }));
  await w.goto(baza + '#k=' + kl.gost);
  const pod = await w.textContent('#vhodOshibka');
  if (!/Safari/.test(pod) || !(await w.isDisabled('#knRazreshit'))) throw new Error('нет подсказки для встроенного браузера: ' + pod);
  shag('встроенный браузер: «' + pod.slice(0, 60) + '…»');
} catch (x) {
  itog.oshibki.push(String(x && x.stack || x));
  console.log('✗', x && x.message);
} finally {
  fs.writeFileSync(out + '/proba.json', JSON.stringify(itog, null, 1));
  fs.writeFileSync(out + '/stop', '');
  await b.close();
}
