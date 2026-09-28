/* Сборка: рендеры для листа (renders/*.png) и модель pixel-mech.glb.
   Запуск: npm install && npm run build   (только картинки: npm run renders)
   Нужен Chromium: берётся из PLAYWRIGHT_BROWSERS_PATH, CHROME_PATH или обычной установки Playwright. */
import { chromium } from 'playwright-core';
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const ONLY_RENDERS = process.argv.includes('--renders-only');
const PICK = process.argv.find(a => a.startsWith('--view='))?.slice(7);

function findChrome() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const base = process.env.PLAYWRIGHT_BROWSERS_PATH || '/opt/pw-browsers';
  if (existsSync(base)) {
    for (const d of readdirSync(base).filter(n => n.startsWith('chromium-')).sort().reverse()) {
      const p = join(base, d, 'chrome-linux', 'chrome');
      if (existsSync(p)) return p;
    }
  }
  return undefined;                                   // пусть Playwright ищет сам
}

/* ракурсы листа-образца: фон как у листа, размеры — под панели листа ×2…×3 */
const BG = '#a8d3f2';
const HEAD = ['golova', 'lico', 'bokL', 'bokR', 'shapka', 'ochki', 'sheya'];
const EXPLODED_ANCHORS = [
  ['skullcap', 'shapka', [4.8, 7.8, 0]], ['sunglasses', 'ochki', [6.1, 5.9, 1.5]], ['face', 'lico', [4.8, 1.4, 4.8]],
  ['sideplating', 'bokR', [6.1, 5.4, -1.2]], ['neck', 'sheya', [1.47, 0.5, 0.1]], ['torso', 'korpus', [4.4, 16.6, 3.0]],
  ['arms', 'rukaR', [4.0, -4.5, 2.0]], ['pelvis', 'taz', [2.0, 11.8, 2.0]],
  ['legs', 'nogaR', [1.8, -1.6, 1.5]], ['feet', 'stupnyaR', [7.2, 1.9, 3.0]],
  ['m1L', 'lico', [-6.6, 7.2, 4.8]], ['m1R', 'lico', [6.6, 7.2, 4.8]], ['m2L', 'sheya', [-2.4, -0.2, 0]], ['m2R', 'sheya', [2.4, -0.2, 0]],
  ['m3L', 'korpus', [-5.6, 18.2, 2.0]], ['m3R', 'korpus', [5.6, 18.2, 2.0]], ['m4L', 'korpus', [-3.2, 12.1, 2.6]], ['m4R', 'korpus', [3.2, 12.1, 2.6]],
  ['m5L', 'nogaL', [0, 1.0, 1.5]], ['m5R', 'nogaR', [0, 1.0, 1.5]], ['m6L', 'stupnyaL', [-7.8, 3.2, 1.6]], ['m6R', 'stupnyaR', [7.8, 3.2, 1.6]]
];
/* опорные точки вида спереди: по ним tools/qa_body.py переводит рендер в мировые единицы */
const WORLD_ANCHORS = [];
for (const X of [-10, 0, 10]) for (const Y of [0, 10, 20]) WORLD_ANCHORS.push([`g${X}_${Y}`, 'korpus', [X, Y, 0]]);
const VIEWS = {
  /* лист 2 снят почти без перспективы и чуть сверху — длинный фокус; 3/4 подобран по силуэту тела листа 2 */
  front:    { w: 880, h: 1000, yaw: 0, pitch: 0.11, target: [0, 15.6, 0], height: 35.2, fov: 12.5, bg: BG, anchors: WORLD_ANCHORS },
  three4:   { w: 880, h: 1000, yaw: 0.85, pitch: 0.2, target: [0.2, 15.4, 0.4], height: 36.3, fov: 12.5, bg: BG },
  /* камера сверки с листом 2 (только по --view=qa): tools/qa_body.py */
  qa:       { w: 880, h: 1000, yaw: 0, pitch: 0.12, target: [0, 15.6, 0], height: 35.2, fov: 8, bg: BG, anchors: WORLD_ANCHORS, qa: true },
  headtop:  { w: 740, h: 440, yaw: 0.05, pitch: 0.95, target: [0, 29.8, 0.2], height: 8.4, fov: 24, only: HEAD, floor: false, bg: BG },
  face:     { w: 740, h: 440, yaw: 0.14, pitch: 0.04, target: [0.7, 25.1, 0], height: 8.2, fov: 22, bg: BG },
  glasses:  { w: 740, h: 330, yaw: 0.42, pitch: 0.3, target: [0, 25.9, 3.2], height: 5.4, fov: 22, only: ['ochki'], floor: false, bg: BG },
  neck:     { w: 740, h: 360, yaw: 0.1, pitch: -0.14, target: [0, 20.25, 0.2], height: 4.4, fov: 22, only: HEAD.filter(k => k !== 'ochki'), floor: false, bg: BG },
  exploded: { w: 1180, h: 1200, yaw: 0.0, pitch: 0.06, target: [0, 25.9, 0], height: 57.5, explode: 1, floor: false, bg: BG, anchors: EXPLODED_ANCHORS },
  modhead:  { w: 510, h: 380, yaw: -0.75, pitch: 0.18, target: [-0.6, 25.3, 0], height: 13.2, fov: 22, only: HEAD, floor: false, bg: BG },
  modarm:   { w: 510, h: 340, yaw: 0.75, pitch: 0.2, target: [7.6, 14.6, 0.4], height: 12.2, fov: 22, only: ['rukaR', 'korpus'], floor: false, bg: BG },
  modfoot:  { w: 510, h: 360, yaw: 0.75, pitch: 0.28, target: [4.6, 3.1, 1.0], height: 9.2, fov: 22, only: ['stupnyaR', 'nogaR'], floor: false, bg: BG },
  hero:     { w: 1600, h: 1600, yaw: 0.5, pitch: 0.16, target: [0.2, 15.4, 0.4], height: 35.5 }
};

const browser = await chromium.launch({
  executablePath: findChrome(),
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--allow-file-access-from-files']
});
const page = await browser.newPage({ viewport: { width: 1400, height: 1400 } });
page.on('console', m => { if (m.type() === 'error') console.error('[page]', m.text()); });
page.on('pageerror', e => console.error('[page error]', e.message));
await page.goto(pathToFileURL(join(ROOT, 'tools', 'render.html')).href);
await page.waitForFunction('window.ready === true', null, { timeout: 120000 });
console.log('детали:', await page.evaluate('JSON.stringify(window.stats())'));

mkdirSync(join(ROOT, 'renders'), { recursive: true });
for (const [name, spec] of Object.entries(VIEWS)) {
  if (PICK ? !PICK.split(',').includes(name) : spec.qa) continue;
  const { png, anchors } = await page.evaluate(s => window.renderView(s), spec);
  writeFileSync(join(ROOT, 'renders', name + '.png'), Buffer.from(png.split(',')[1], 'base64'));
  if (spec.anchors) writeFileSync(join(ROOT, 'renders', name + '.json'), JSON.stringify(anchors, null, 1));
  console.log('рендер', name);
}
if (!ONLY_RENDERS && !PICK) {
  const b64 = await page.evaluate(() => window.exportGLB());
  const buf = Buffer.from(b64, 'base64');
  writeFileSync(join(ROOT, 'pixel-mech.glb'), buf);
  console.log('pixel-mech.glb', (buf.length / 1048576).toFixed(2), 'МБ');
}
await browser.close();
