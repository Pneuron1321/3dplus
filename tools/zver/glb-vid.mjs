/* Осмотр чужой сетки (например, из генератора 3D): GLB → лист из ракурсов вокруг вертикали.
   node tools/zver/glb-vid.mjs вход.glb выход.png [ракурсы через запятую, градусы] [размер кадра] */
import { chromium } from 'playwright-core';
import { readFileSync, writeFileSync, existsSync, readdirSync, mkdtempSync } from 'fs';
import { tmpdir } from 'os';
import { join, dirname } from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const [IN, OUT, YAWS = '0,45,90,180,270', SZ = '420'] = process.argv.slice(2);
function chrome() {
  const base = process.env.PLAYWRIGHT_BROWSERS_PATH || '/opt/pw-browsers';
  if (existsSync(base)) for (const d of readdirSync(base).filter(n => n.startsWith('chromium-')).sort().reverse()) {
    const p = join(base, d, 'chrome-linux', 'chrome'); if (existsSync(p)) return p;
  }
}
const html = `<!doctype html><html><body style="margin:0;background:#e9e9e7">
<script src="${pathToFileURL(join(ROOT, 'node_modules/three/build/three.min.js')).href}"></script>
<script src="${pathToFileURL(join(ROOT, 'node_modules/three/examples/js/loaders/GLTFLoader.js')).href}"></script>
</body></html>`;
const b = await chromium.launch({ executablePath: chrome(), args: ['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'] });
const page = await b.newPage();
const tmp = join(mkdtempSync(join(tmpdir(), 'glbvid-')), 'v.html'); writeFileSync(tmp, html);
await page.goto(pathToFileURL(tmp).href);
await page.waitForFunction(() => window.THREE && THREE.GLTFLoader);
const b64 = readFileSync(IN).toString('base64');
const png = await page.evaluate(async ({ b64, yaws, sz }) => {
  const bin = Uint8Array.from(atob(b64), c => c.charCodeAt(0)).buffer;
  const gl = await new Promise((ok, err) => new THREE.GLTFLoader().parse(bin, '', ok, err));
  const obj = gl.scene, box = new THREE.Box3().setFromObject(obj), c = box.getCenter(new THREE.Vector3()), s = box.getSize(new THREE.Vector3());
  obj.position.sub(c);
  obj.traverse(o => { if (!o.isMesh) return; if (!o.geometry.attributes.normal) o.geometry.computeVertexNormals(); if (o.geometry.attributes.color) o.material = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.7, side: THREE.DoubleSide }); else if (!(o.material && o.material.map)) o.material = new THREE.MeshStandardMaterial({ color: 0x8fa86a, roughness: 0.8, side: THREE.DoubleSide }); });
  const R = Math.max(s.x, s.y, s.z);
  const sc = new THREE.Scene(); sc.add(obj); sc.add(new THREE.HemisphereLight(0xffffff, 0x445533, 0.7));
  const d = new THREE.DirectionalLight(0xffffff, 0.9); d.position.set(1, 2, 3); sc.add(d);
  const r = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true }); r.setSize(sz * yaws.length, sz); r.setClearColor(0xe9e9e7);
  r.setScissorTest(true); r.outputEncoding = THREE.sRGBEncoding;
  const cam = new THREE.OrthographicCamera(-R * 0.6, R * 0.6, R * 0.6, -R * 0.6, 0.01, R * 10);
  yaws.forEach((y, i) => {
    const a = y * Math.PI / 180; cam.position.set(Math.sin(a) * R * 3, 0, Math.cos(a) * R * 3); cam.lookAt(0, 0, 0);
    d.position.copy(cam.position).add(new THREE.Vector3(0, R * 2, 0));
    r.setViewport(i * sz, 0, sz, sz); r.setScissor(i * sz, 0, sz, sz); r.render(sc, cam);
  });
  return r.domElement.toDataURL('image/png');
}, { b64, yaws: YAWS.split(',').map(Number), sz: +SZ });
writeFileSync(OUT, Buffer.from(png.split(',')[1], 'base64'));
await b.close();
console.log('лист', OUT);
