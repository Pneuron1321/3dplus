/* Выгрузка модели с флагами в GLB (сборка build.mjs с --opt GLB не пишет): node tools/forma/vygruzka.mjs выход.glb [ключ=значение …]
   Нужна для переноса цвета: кирпичная v6 с ногами «утка» — источник чистых цветов для сетки (tools/forma/cvet_v6.py). */
import { chromium } from 'playwright-core';
import { existsSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const [out, ...kv] = process.argv.slice(2);
function findChrome() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const base = process.env.PLAYWRIGHT_BROWSERS_PATH || '/opt/pw-browsers';
  if (existsSync(base)) for (const d of readdirSync(base).filter(n => n.startsWith('chromium-')).sort().reverse())
    for (const p of [join(base, d, 'chrome-linux', 'chrome'), join(base, d, 'chrome-linux64', 'chrome')]) if (existsSync(p)) return p;
  return undefined;
}
const browser = await chromium.launch({ executablePath: findChrome(),
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--allow-file-access-from-files'] });
const page = await browser.newPage();
page.on('pageerror', e => console.error('[page error]', e.message));
await page.goto(pathToFileURL(join(ROOT, 'tools', 'render.html')).href + '?' + new URLSearchParams(Object.fromEntries(kv.map(a => a.split('=')))));
await page.waitForFunction('window.ready === true', null, { timeout: 180000 });
const buf = Buffer.from(await page.evaluate(() => window.exportGLB()), 'base64');
writeFileSync(out, buf);
console.log(out, (buf.length / 1048576).toFixed(2), 'МБ');
await browser.close();
