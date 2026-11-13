/* ============================================================================
   PIXEL MECH v5 — модульная коллекционная фигурка из кирпичиков.
   Генератор модели для three.js r128 (глобальный THREE).
   Задание и целевые замеры: docs/PROMPT-v3.md (голова), docs/PROMPT-v5.md (тело);
   сверка: tools/qa.py (лист 1), tools/qa_body.py (тело по листу 2).

   Как устроено:
   • Каждая деталь — воксельная сетка со своим шагом клетки (обычный кирпич —
     0.8 × h × 0.8, «макро»-блоки наручей и груди — крупнее).
   • Видимые клетки «кирпичатся»: соседние клетки одного класса сливаются в
     1×2…1×6 с перевязкой рядов, у каждого кирпича фаска и свой оттенок.
   • Клетка может быть «скосом»: коробка, отсечённая плоскостями (рёбра груди,
     наплечники и наручи, носок и задник кроссовок, скосы на шапке).
   • За каждым кирпичом стоит тёмная «подложка» — сквозь швы видна глубина.
   • Затенение в углах (AO) запекается в цвета вершин по заполненности
     соседних клеток — только внутри детали, чтобы разлёт оставался чистым.
   • Шипы (обычные и боковые) — точёные; техник-втулки, болты-оси, шея,
     очки и магнитные шайбы на стыках — отдельная геометрия.
   Всё одной детали сливается в одну сетку с цветами вершин.

   Координаты: x — вправо (от зрителя), y — вверх, z — к зрителю (лицо).
   Единица: шаг шипа 0.8. Пол — y = 0. Высота до шипов рожек — 31.1 (v5: голова выше на 3.5).
   ========================================================================== */
(function (global) {
  'use strict';

  /* ---------- палитра (sRGB), откалибрована по зонам листа-образца ---------- */
  const PALETTE = {
    WH: '#dcdddb', WH2: '#e6e6e4', LG: '#bdbebe', LG2: '#aeafb0', MG: '#8a8b8c', MG2: '#747270',
    DG: '#565759', DG2: '#444547', GM: '#0f1012', BK: '#121214', SV: '#9ea3a8',
    YE: '#f6cd10', YE2: '#efc20c', YE3: '#f9d52c',
    O1: '#d06400', O2: '#f27400', O3: '#ee9010', O4: '#f39a12', O5: '#bf5a00', O6: '#ea7c06',
    PU: '#8656ec', PU2: '#7444dc', PU3: '#9464f0',
    OT1: '#e06c00', OT2: '#cc5e00', OT3: '#ec7a04',
    /* v5 — тело по листу 2 */
    MG3: '#74787e', MG4: '#65696f', DG3: '#343432', BR: '#6a5d55', FG: '#646466',
    /* сапоги «Пикколо»: замша оранжево-красная, неоновая подошва */
    RD1: '#d83a1e', RD2: '#8a160c',
    SU1: '#e2561c', SU2: '#cf4a14', SU3: '#ec6828', SU4: '#b8400f', NG1: '#8cf52e', NG2: '#6edc1c',
    /* тапочки «Пикколо» (лист ног 24.10): светло-мятные */
    MN1: '#a9d68e', MN2: '#93c278'
  };

  /* смеси: класс клетки → из чего выбирается цвет каждого кирпича */
  const MIX = {
    head: [['WH', 0.62], ['WH2', 0.18], ['LG', 0.2]],
    headBack: [['LG', 0.5], ['WH', 0.25], ['MG', 0.25]],
    skull: [['LG', 0.5], ['WH', 0.35], ['LG2', 0.15]],
    side: [['LG', 0.5], ['WH', 0.3], ['LG2', 0.2]],
    sideFront: [['DG', 0.6], ['MG', 0.25], ['DG2', 0.15]],
    yellow: [['YE', 0.72], ['YE2', 0.16], ['YE3', 0.12]],
    purple: [['PU', 0.8], ['PU2', 0.12], ['PU3', 0.08]],
    dark: [['DG', 0.72], ['DG2', 0.28]],
    ear: [['O1', 0.5], ['O5', 0.5]],
    techDG: [['DG', 1]],
    /* тело v5 */
    chest: [['WH', 0.55], ['WH2', 0.3], ['LG', 0.15]],
    fist: [['WH', 0.5], ['WH2', 0.38], ['LG', 0.12]],
    band: [['MG3', 0.7], ['MG', 0.3]],
    abdSide: [['DG3', 0.6], ['DG2', 0.4]],
    abdMid: [['MG3', 0.6], ['MG', 0.4]],
    darkBand: [['DG2', 0.7], ['DG3', 0.3]],
    pelvis: [['MG2', 0.4], ['DG', 0.4], ['MG3', 0.2]],
    crotch: [['BR', 0.8], ['DG2', 0.2]],
    knee: [['BK', 0.7], ['GM', 0.3]],
    paulDark: [['DG2', 0.7], ['DG3', 0.3]],
    gauntDark: [['MG3', 0.6], ['MG4', 0.4]],
    gauntTop: [['MG', 0.6], ['MG3', 0.4]],
    orangePlate: [['O2', 0.6], ['O6', 0.4]],
    orangeBright: [['O4', 0.6], ['O3', 0.4]],
    thigh: [['MG3', 0.6], ['MG4', 0.4]],
    thighTop: [['LG2', 0.6], ['MG', 0.4]],
    shin: [['MG', 0.5], ['MG3', 0.3], ['MG2', 0.2]],
    footBase: [['O4', 0.6], ['O3', 0.4]],
    footMid: [['O1', 0.62], ['O6', 0.2], ['O5', 0.18]],
    footTop: [['OT1', 0.45], ['OT3', 0.3], ['OT2', 0.25]],
    bracket: [['O2', 0.6], ['O6', 0.4]],
    suede: [['SU1', 0.5], ['SU2', 0.25], ['SU3', 0.25]],
    suedeFold: [['SU2', 0.6], ['SU4', 0.4]],
    sole: [['NG1', 0.65], ['NG2', 0.35]]
  };

  function create(T, opts) {
    opts = opts || {};
    const S = 0.8;                                          // шаг шипа

    /* ---------- цвет и случайность (детерминированно) ---------- */
    const LIN = {};
    Object.keys(PALETTE).forEach(k => { LIN[k] = new T.Color(PALETTE[k]).convertSRGBToLinear(); });
    let seed = opts.seed || 90217;
    const rnd = () => { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646; };
    const hash = (a, b, c) => {
      let h = (a * 374761393 + b * 668265263 + c * 2147483647 + 1013904223) | 0;
      h = Math.imul(h ^ (h >>> 13), 1274126177);
      return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
    };
    const tmpHSL = {};
    function tone(key, spread) {                            // живой пластик: каждый кирпич чуть свой
      const c = LIN[key].clone();
      c.getHSL(tmpHSL);
      const s = spread == null ? 0.045 : spread;
      c.setHSL(tmpHSL.h, tmpHSL.s, Math.max(0, Math.min(1, tmpHSL.l * (1 - s + rnd() * 2 * s))));
      return c;
    }
    function pick(cls) {
      if (!MIX[cls]) return cls;
      const x = rnd(); let s = 0;
      for (const [k, w] of MIX[cls]) { s += w; if (x < s) return k; }
      return MIX[cls][0][0];
    }

    /* ---------- заполненность пространства (для AO) ---------- */
    class Occ {
      constructor(res) { this.r = res || 0.2; this.s = new Set(); }
      key(i, j, k) { return ((i + 2000) * 4000 + (j + 2000)) * 4000 + (k + 2000); }
      addBox(x0, y0, z0, x1, y1, z1) {
        const r = this.r;
        for (let i = Math.floor(x0 / r + 1e-6); i < Math.ceil(x1 / r - 1e-6); i++)
          for (let j = Math.floor(y0 / r + 1e-6); j < Math.ceil(y1 / r - 1e-6); j++)
            for (let k = Math.floor(z0 / r + 1e-6); k < Math.ceil(z1 / r - 1e-6); k++) this.s.add(this.key(i, j, k));
      }
      has(x, y, z) { const r = this.r; return this.s.has(this.key(Math.floor(x / r), Math.floor(y / r), Math.floor(z / r))); }
    }
    /* направления выборки AO: точки Фибоначчи на сфере */
    const AO_DIRS = [];
    for (let i = 0; i < 26; i++) {
      const y = 1 - (i + 0.5) / 26 * 2, r = Math.sqrt(1 - y * y), t = i * 2.39996;
      AO_DIRS.push([Math.cos(t) * r, y, Math.sin(t) * r]);
    }

    /* ---------- сборщик сетки: позиции, нормали, цвета ---------- */
    class MeshBuf {
      constructor(withAO) { this.p = []; this.n = []; this.c = []; this.i = []; this.v = 0; this.aoF = []; this.occ = withAO ? new Occ(0.2) : null; }
      vert(x, y, z, nx, ny, nz, col, noAO) {
        this.p.push(x, y, z); this.n.push(nx, ny, nz); this.c.push(col.r, col.g, col.b); this.aoF.push(noAO ? 0 : 1);
        return this.v++;
      }
      tri(a, b, c) { this.i.push(a, b, c); }
      /* запечь затенение: доля занятых точек в полусфере над вершиной */
      bakeAO(strength) {
        if (!this.occ || opts.noAO) return;
        const P = this.p, N = this.n, C = this.c, occ = this.occ, k = strength == null ? 0.78 : strength;
        const DIST = [0.34, 0.78];
        for (let v = 0; v < this.v; v++) {
          if (!this.aoF[v]) continue;
          const o = v * 3, px = P[o] + N[o] * 0.06, py = P[o + 1] + N[o + 1] * 0.06, pz = P[o + 2] + N[o + 2] * 0.06;
          let hit = 0, tot = 0;
          for (const d of AO_DIRS) {
            const dn = d[0] * N[o] + d[1] * N[o + 1] + d[2] * N[o + 2];
            if (dn < 0.2) continue;
            for (let i = 0; i < 2; i++) {
              const w = i ? 0.7 : 1;
              tot += w;
              if (occ.has(px + d[0] * DIST[i], py + d[1] * DIST[i], pz + d[2] * DIST[i])) hit += w;
            }
          }
          if (!tot) continue;
          const f = Math.max(0.42, 1 - k * hit / tot);
          C[o] *= f; C[o + 1] *= f; C[o + 2] *= f;
        }
      }
      geometry() {
        const g = new T.BufferGeometry();
        g.setAttribute('position', new T.Float32BufferAttribute(this.p, 3));
        g.setAttribute('normal', new T.Float32BufferAttribute(this.n, 3));
        g.setAttribute('color', new T.Float32BufferAttribute(this.c, 3));
        g.setIndex(this.v > 65535 ? new T.Uint32BufferAttribute(this.i, 1) : new T.Uint16BufferAttribute(this.i, 1));
        g.computeBoundingSphere();
        return g;
      }
      get empty() { return this.v === 0; }
    }

    /* брусок с фаской: только нужные грани (маска: +x −x +y −y +z −z) */
    const FACE = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
    function bevelBox(mb, cx, cy, cz, hx, hy, hz, r, col, mask, m4, noAO) {
      const h = [hx, hy, hz], c = [cx, cy, cz];
      if (opts.lite) return flatBox(mb, c, h, col, mask, m4, noAO);
      r = Math.min(r, hx * 0.45, hy * 0.45, hz * 0.45);
      const q = [0, 0, 0], p = [0, 0, 0], v3 = new T.Vector3(), n3 = new T.Vector3();
      const nm = m4 ? new T.Matrix3().getNormalMatrix(m4) : null;
      for (let f = 0; f < 6; f++) {
        if (!(mask & (1 << f))) continue;
        const a = f >> 1, s = (f & 1) ? -1 : 1, u = (a + 1) % 3, w = (a + 2) % 3;
        const us = [-h[u], -h[u] + r, h[u] - r, h[u]], ws = [-h[w], -h[w] + r, h[w] - r, h[w]];
        const base = mb.v;
        for (let j = 0; j < 4; j++) for (let i = 0; i < 4; i++) {
          p[a] = s * h[a]; p[u] = us[i]; p[w] = ws[j];
          for (let k = 0; k < 3; k++) q[k] = Math.max(-(h[k] - r), Math.min(h[k] - r, p[k]));
          let nx = p[0] - q[0], ny = p[1] - q[1], nz = p[2] - q[2];
          const l = Math.hypot(nx, ny, nz);
          let px = p[0], py = p[1], pz = p[2];
          if (l > 1e-9) { nx /= l; ny /= l; nz /= l; px = q[0] + nx * r; py = q[1] + ny * r; pz = q[2] + nz * r; }
          else { nx = FACE[f][0]; ny = FACE[f][1]; nz = FACE[f][2]; }
          v3.set(c[0] + px, c[1] + py, c[2] + pz); n3.set(nx, ny, nz);
          if (m4) { v3.applyMatrix4(m4); n3.applyMatrix3(nm).normalize(); }
          mb.vert(v3.x, v3.y, v3.z, n3.x, n3.y, n3.z, col, noAO);
        }
        for (let j = 0; j < 3; j++) for (let i = 0; i < 3; i++) {
          const k = base + j * 4 + i;
          if (s > 0) { mb.tri(k, k + 1, k + 5); mb.tri(k, k + 5, k + 4); }
          else { mb.tri(k, k + 5, k + 1); mb.tri(k, k + 4, k + 5); }
        }
      }
    }

    /* облегчённый брусок (opts.lite, для VR): 4 вершины на грань, без фаски */
    function flatBox(mb, c, h, col, mask, m4, noAO) {
      const p = [0, 0, 0], v3 = new T.Vector3(), n3 = new T.Vector3();
      const nm = m4 ? new T.Matrix3().getNormalMatrix(m4) : null;
      for (let f = 0; f < 6; f++) {
        if (!(mask & (1 << f))) continue;
        const a = f >> 1, s = (f & 1) ? -1 : 1, u = (a + 1) % 3, w = (a + 2) % 3, base = mb.v;
        for (let j = 0; j < 2; j++) for (let i = 0; i < 2; i++) {
          p[a] = s * h[a]; p[u] = (i ? 1 : -1) * h[u]; p[w] = (j ? 1 : -1) * h[w];
          v3.set(c[0] + p[0], c[1] + p[1], c[2] + p[2]); n3.set(FACE[f][0], FACE[f][1], FACE[f][2]);
          if (m4) { v3.applyMatrix4(m4); n3.applyMatrix3(nm).normalize(); }
          mb.vert(v3.x, v3.y, v3.z, n3.x, n3.y, n3.z, col, noAO);
        }
        if (s > 0) { mb.tri(base, base + 1, base + 3); mb.tri(base, base + 3, base + 2); }
        else { mb.tri(base, base + 3, base + 1); mb.tri(base, base + 2, base + 3); }
      }
    }

    /* скос: коробка ∩ полупространства n·u ≤ d (u — координаты в долях полуразмера).
       Грани плоские: так выглядят скошенные и угловые кирпичи. */
    function clipBlock(mb, c, h, planes, col) {
      const polys = [];
      for (let f = 0; f < 6; f++) {
        const a = f >> 1, s = (f & 1) ? -1 : 1, u = (a + 1) % 3, w = (a + 2) % 3;
        const corners = [[-1, -1], [1, -1], [1, 1], [-1, 1]].map(([iu, iw]) => {
          const p = [0, 0, 0]; p[a] = s * h[a]; p[u] = iu * h[u]; p[w] = iw * h[w]; return p;
        });
        polys.push(s > 0 ? corners : corners.reverse());
      }
      let cur = polys;
      for (const pl of planes) {
        /* плоскость в абсолютных координатах: (n/h)·p ≤ d */
        let n = [pl[0] / h[0], pl[1] / h[1], pl[2] / h[2]];
        const ln = Math.hypot(n[0], n[1], n[2]); n = n.map(x => x / ln);
        const d = pl[3] / ln;
        const out = [], cap = [];
        for (const poly of cur) {
          const res = [];
          for (let i = 0; i < poly.length; i++) {
            const A = poly[i], B = poly[(i + 1) % poly.length];
            const da = A[0] * n[0] + A[1] * n[1] + A[2] * n[2] - d, db = B[0] * n[0] + B[1] * n[1] + B[2] * n[2] - d;
            if (da <= 0) res.push(A);
            if ((da <= 0) !== (db <= 0)) {
              const t = da / (da - db), P = [A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t, A[2] + (B[2] - A[2]) * t];
              res.push(P); cap.push(P);
            }
          }
          if (res.length >= 3) out.push(res);
        }
        if (cap.length >= 3) {
          const cc = [0, 0, 0]; cap.forEach(p => { cc[0] += p[0]; cc[1] += p[1]; cc[2] += p[2]; });
          cc[0] /= cap.length; cc[1] /= cap.length; cc[2] /= cap.length;
          const ref = Math.abs(n[0]) < 0.9 ? [1, 0, 0] : [0, 1, 0];
          let u = [n[1] * ref[2] - n[2] * ref[1], n[2] * ref[0] - n[0] * ref[2], n[0] * ref[1] - n[1] * ref[0]];
          const lu = Math.hypot(u[0], u[1], u[2]); u = u.map(x => x / lu);
          const v = [n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]];
          const uniq = [];
          cap.forEach(p => { if (!uniq.some(q => Math.hypot(q[0] - p[0], q[1] - p[1], q[2] - p[2]) < 1e-6)) uniq.push(p); });
          uniq.sort((p, q) => {
            const ap = Math.atan2((p[0] - cc[0]) * v[0] + (p[1] - cc[1]) * v[1] + (p[2] - cc[2]) * v[2], (p[0] - cc[0]) * u[0] + (p[1] - cc[1]) * u[1] + (p[2] - cc[2]) * u[2]);
            const aq = Math.atan2((q[0] - cc[0]) * v[0] + (q[1] - cc[1]) * v[1] + (q[2] - cc[2]) * v[2], (q[0] - cc[0]) * u[0] + (q[1] - cc[1]) * u[1] + (q[2] - cc[2]) * u[2]);
            return ap - aq;
          });
          if (uniq.length >= 3) out.push(uniq);
        }
        cur = out;
      }
      for (const raw of cur) {
        const poly = raw.filter((P, i) => {                          // без повторов подряд
          const Q = raw[(i + raw.length - 1) % raw.length];
          return Math.hypot(P[0] - Q[0], P[1] - Q[1], P[2] - Q[2]) > 1e-7;
        });
        if (poly.length < 3) continue;
        /* нормаль Ньюэлла */
        let nx = 0, ny = 0, nz = 0;
        for (let i = 0; i < poly.length; i++) {
          const A = poly[i], B = poly[(i + 1) % poly.length];
          nx += (A[1] - B[1]) * (A[2] + B[2]); ny += (A[2] - B[2]) * (A[0] + B[0]); nz += (A[0] - B[0]) * (A[1] + B[1]);
        }
        const l = Math.hypot(nx, ny, nz);
        if (l < 1e-9) continue;                                      // схлопнувшаяся грань — не рисуем
        nx /= l; ny /= l; nz /= l;
        const base = mb.v;
        poly.forEach(p => mb.vert(c[0] + p[0], c[1] + p[1], c[2] + p[2], nx, ny, nz, col));
        for (let i = 1; i < poly.length - 1; i++) mb.tri(base, base + i, base + i + 1);
      }
    }

    /* точёная деталь: профиль [r, y, nr, ny] вокруг оси y, с явными нормалями */
    function latheTpl(profile, seg) {
      const pos = [], nor = [], idx = [], P = profile.length;
      for (let i = 0; i <= seg; i++) {
        const t = i / seg * Math.PI * 2, sn = Math.sin(t), cs = Math.cos(t);
        for (const [r, y, nr, ny] of profile) { pos.push(r * sn, y, r * cs); nor.push(nr * sn, ny, nr * cs); }
      }
      for (let i = 0; i < seg; i++) for (let j = 0; j < P - 1; j++) {
        const a = i * P + j, b = a + P;
        /* пропускаем вырожденные: у оси (r = 0) и между двойными точками острой кромки */
        if (Math.abs(profile[j][0] - profile[j + 1][0]) < 1e-9 && Math.abs(profile[j][1] - profile[j + 1][1]) < 1e-9) continue;
        if (profile[j][0] > 1e-9) idx.push(a, b, a + 1);
        if (profile[j + 1][0] > 1e-9) idx.push(b, b + 1, a + 1);
      }
      return { pos, nor, idx };
    }
    function emitTpl(mb, tpl, m4, col, noAO) {
      const nm = new T.Matrix3().getNormalMatrix(m4), v = new T.Vector3(), n = new T.Vector3(), base = mb.v;
      for (let i = 0; i < tpl.pos.length; i += 3) {
        v.set(tpl.pos[i], tpl.pos[i + 1], tpl.pos[i + 2]).applyMatrix4(m4);
        n.set(tpl.nor[i], tpl.nor[i + 1], tpl.nor[i + 2]).applyMatrix3(nm).normalize();
        mb.vert(v.x, v.y, v.z, n.x, n.y, n.z, col, noAO);
      }
      for (const k of tpl.idx) mb.i.push(base + k);
    }
    /* шип: радиус 1, высота 0.71 — масштабируется под клетку; на торце кольцевая фаска */
    const STUD = latheTpl([
      [1, -0.06, 1, 0], [1, 0.56, 1, 0], [0.955, 0.67, 0.72, 0.7], [0.86, 0.71, 0.18, 0.98], [0.62, 0.71, 0, 1], [0.6, 0.7, 0, 1], [0, 0.7, 0, 1]
    ], opts.lite ? 10 : 20);
    /* цилиндр с фаской (для осей, штифтов, шайб): радиус 1, высота 1, от 0 до 1 */
    function cylTpl(bev, seg) {
      const b = bev || 0.08;
      return latheTpl([
        [0, 0, 0, -1], [1 - b, 0, 0, -1], [1, b, 0.7, -0.7], [1, b, 1, 0],
        [1, 1 - b, 1, 0], [1, 1 - b, 0.7, 0.7], [1 - b, 1, 0, 1], [0, 1, 0, 1]
      ], seg || 24);
    }
    const CYL = cylTpl(0.1, opts.lite ? 12 : 24), CYL_FINE = cylTpl(0.06, opts.lite ? 16 : 40), HEX = cylTpl(0.02, 6);
    /* трубка-втулка (техник-отверстие) */
    function tubeTpl(rIn) {
      return latheTpl([
        [rIn, 0.2, -1, 0], [rIn, 1, -1, 0], [rIn, 1, 0, 1], [0.92, 1, 0, 1], [1, 0.92, 0.7, 0.7], [1, 0, 1, 0]
      ], 24);
    }
    const TUBE = tubeTpl(0.55);
    /* шар шарнира (сфера радиуса 1) */
    const BALL = latheTpl(Array.from({ length: 11 }, (_, i) => {
      const t = i / 10 * Math.PI; return [Math.sin(t), -Math.cos(t), Math.sin(t), -Math.cos(t)];
    }), opts.lite ? 12 : 22);

    const m4 = new T.Matrix4(), qq = new T.Quaternion(), vv = new T.Vector3(), ss = new T.Vector3();
    const UP = new T.Vector3(0, 1, 0);
    const QF = FACE.map(f => new T.Quaternion().setFromUnitVectors(UP, new T.Vector3(f[0], f[1], f[2])));
    /* ось по направлению dir, основание в точке (x,y,z), длина len, радиус rad */
    function axial(mb, tpl, x, y, z, dir, rad, len, col, noAO) {
      qq.setFromUnitVectors(UP, vv.set(dir[0], dir[1], dir[2]).normalize());
      m4.compose(new T.Vector3(x, y, z), qq, ss.set(rad, len, rad));
      emitTpl(mb, tpl, m4, col, noAO);
    }

    const stats = { bricks: 0, studs: 0, slopes: 0, magnets: 0 };
    /* паспорт кирпичей (opts.manifest): каждый кирпич и скос — деталь, центр и полуразмеры
       в координатах детали, цвет sRGB, класс, шипы (точка на грани и номер грани) */
    const bricks = opts.manifest ? [] : null;
    const r4 = v => +(+v).toFixed(4);
    function rec(mb, kind, c, h, col, extra) {
      if (!bricks) return null;
      const r = Object.assign({ part: mb.owner || '?', kind, c: c.map(r4), h: h.map(r4),
        color: '#' + col.clone().convertLinearToSRGB().getHexString() }, extra || {});
      bricks.push(r);
      return r;
    }

    /* ======================= ВОКСЕЛЬНАЯ ДЕТАЛЬ ======================= */
    class Vox {
      constructor(o) {
        this.cell = o.cell || [S, 0.96, S];
        this.at = o.at || [0, 0, 0];
        this.m = new Map();
        this.studs = o.studs || null;       // (клетка, грань 0..5) → false | true | радиус
        this.maxLen = o.maxLen == null ? 4 : o.maxLen;
        this.merge = o.merge == null ? 0.45 : o.merge;
        this.gap = o.gap == null ? 0.022 : o.gap;
        this.bev = o.bev == null ? 0.036 : o.bev;
        this.salt = o.salt || 1;
        this.studR = o.studR || 0.24 * Math.min(this.cell[0], this.cell[2]) / S;
        this.coreDark = o.coreDark == null ? 0.2 : o.coreDark;
        this.bevFor = o.bevFor || null;     // своя фаска для клетки
        this.mergeFor = o.mergeFor || null; // своя вероятность слияния по ряду (под линзой — длинные кирпичи)
        this.studTone = o.studTone || null; // (клетка, грань) → ключ палитры: шип другого цвета, чем кирпич
        this.clip = o.clip || null;         // (клетка) → плоскости скоса [nx,ny,nz,d] | null
      }
      K(x, y, z) { return ((x + 300) * 600 + (y + 300)) * 600 + (z + 300); }
      set(x, y, z, cls) { this.m.set(this.K(x, y, z), { x, y, z, cls }); return this; }
      has(x, y, z) { return this.m.has(this.K(x, y, z)); }
      get(x, y, z) { return this.m.get(this.K(x, y, z)); }
      box(x0, x1, y0, y1, z0, z1, cls) {
        for (let x = x0; x < x1; x++) for (let y = y0; y < y1; y++) for (let z = z0; z < z1; z++)
          this.set(x, y, z, typeof cls === 'function' ? cls(x, y, z) : cls);
        return this;
      }
      del(fn) { for (const [k, v] of this.m) if (fn(v)) this.m.delete(k); return this; }
      paint(fn) { for (const v of this.m.values()) { const c = fn(v); if (c) v.cls = c; } return this; }
      openMask(v) {
        let m = 0;
        for (let f = 0; f < 6; f++) if (!this.has(v.x + FACE[f][0], v.y + FACE[f][1], v.z + FACE[f][2])) m |= 1 << f;
        return m;
      }
      cx(x) { return this.at[0] + (x + 0.5) * this.cell[0]; }
      cy(y) { return this.at[1] + (y + 0.5) * this.cell[1]; }
      cz(z) { return this.at[2] + (z + 0.5) * this.cell[2]; }

      build(mb) {
        const [sx, sy, sz] = this.cell, g = this.gap;
        const claimed = new Set();
        const cells = [...this.m.values()].sort((a, b) => a.y - b.y || a.z - b.z || a.x - b.x);
        const masks = new Map();
        for (const v of cells) masks.set(this.K(v.x, v.y, v.z), this.openMask(v));
        /* заполненность для AO: скосы — наполовину (их срезанная часть пустая) */
        if (mb.occ) for (const v of cells) {
          const x0 = this.at[0] + v.x * sx, y0 = this.at[1] + v.y * sy, z0 = this.at[2] + v.z * sz;
          if (this.clip && this.clip(v)) mb.occ.addBox(x0 + sx * 0.25, y0 + sy * 0.25, z0 + sz * 0.25, x0 + sx * 0.75, y0 + sy * 0.75, z0 + sz * 0.75);
          else mb.occ.addBox(x0, y0, z0, x0 + sx, y0 + sy, z0 + sz);
        }
        const isBreak = (pos, row, line, dir) => {
          if (((pos + row) & 1) !== 0) return false;              // перевязка: стыки через ряд со сдвигом
          return hash(pos * 7 + this.salt, row * 13 + (dir === 'x' ? 1 : 2), line * 31) > (this.mergeFor ? this.mergeFor(row) : this.merge);
        };
        for (const v of cells) {
          const key = this.K(v.x, v.y, v.z);
          if (claimed.has(key)) continue;
          const mask = masks.get(key);
          if (!mask) continue;                                    // внутренность — не рисуем
          const planes = this.clip ? this.clip(v) : null;
          if (planes) {                                           // скос: отдельный блок без шипов на срезе
            claimed.add(key);
            const col = tone(pick(v.cls));
            clipBlock(mb, [this.cx(v.x), this.cy(v.y), this.cz(v.z)], [sx / 2 - g / 2, sy / 2 - g / 2, sz / 2 - g / 2], planes, col);
            rec(mb, 'slope', [this.cx(v.x), this.cy(v.y), this.cz(v.z)], [sx / 2, sy / 2, sz / 2], col, { cls: v.cls, planes });
            stats.slopes++;
            continue;
          }
          const fz = mask & 48, fx = mask & 3;
          let dir;
          if (fz && !fx) dir = 'x';
          else if (fx && !fz) dir = 'z';
          else if (fx && fz) dir = (v.y & 1) ? 'z' : 'x';
          else dir = ((v.y + v.x + v.z + this.salt) & 1) ? 'x' : 'z';
          const main = dir === 'x' ? fz : fx;
          const run = [v];
          let mk = mask;
          for (let s = 1; s < this.maxLen; s++) {
            const nx = v.x + (dir === 'x' ? s : 0), nz = v.z + (dir === 'z' ? s : 0);
            const nk = this.K(nx, v.y, nz), n = this.m.get(nk);
            if (!n || n.cls !== v.cls || claimed.has(nk)) break;
            if (this.clip && this.clip(n)) break;
            const nm = masks.get(nk);
            if (!nm || (main && !(nm & main))) break;
            if (isBreak(dir === 'x' ? nx : nz, v.y, dir === 'x' ? v.z : v.x, dir)) break;
            run.push(n); mk |= nm;
          }
          run.forEach(c => claimed.add(this.K(c.x, c.y, c.z)));
          const L = run.length, last = run[L - 1];
          const lx = dir === 'x' ? L : 1, lz = dir === 'z' ? L : 1;
          let fm = mk & (dir === 'x' ? ~3 : ~48);
          if (dir === 'x') fm |= (masks.get(this.K(v.x, v.y, v.z)) & 2) | (masks.get(this.K(last.x, last.y, last.z)) & 1);
          else fm |= (masks.get(this.K(v.x, v.y, v.z)) & 32) | (masks.get(this.K(last.x, last.y, last.z)) & 16);
          const col = tone(pick(v.cls));
          const x0 = this.at[0] + v.x * sx, y0 = this.at[1] + v.y * sy, z0 = this.at[2] + v.z * sz;
          const bev = (this.bevFor && L === 1 && this.bevFor(v)) || this.bev;
          bevelBox(mb, x0 + lx * sx / 2, y0 + sy / 2, z0 + lz * sz / 2,
            lx * sx / 2 - g / 2, sy / 2 - g / 2, lz * sz / 2 - g / 2, bev, col, fm);
          const br = rec(mb, 'brick', [x0 + lx * sx / 2, y0 + sy / 2, z0 + lz * sz / 2], [lx * sx / 2, sy / 2, lz * sz / 2], col, { cls: v.cls, studs: [] });
          stats.bricks++;
          const dark = col.clone().multiplyScalar(this.coreDark);
          if (!opts.noCore && !opts.lite) for (const c of run) this.core(mb, c, masks.get(this.K(c.x, c.y, c.z)), dark);
          if (this.studs && !opts.noStuds) for (const c of run) {
            const cm = masks.get(this.K(c.x, c.y, c.z));
            for (let f = 0; f < 6; f++) {
              if (!(cm & (1 << f))) continue;
              const want = this.studs(c, f);
              if (!want) continue;
              const rad = typeof want === 'number' ? want : this.studR;
              const d = FACE[f];
              const px = this.cx(c.x) + d[0] * (sx / 2 - g / 2), py = this.cy(c.y) + d[1] * (sy / 2 - g / 2), pz = this.cz(c.z) + d[2] * (sz / 2 - g / 2);
              m4.compose(vv.set(px, py, pz), QF[f], ss.set(rad, rad, rad));
              const sk = this.studTone && this.studTone(c, f);
              emitTpl(mb, STUD, m4, sk ? tone(sk) : col.clone().lerp(LIN.WH, 0.03));
              if (br) br.studs.push([r4(px), r4(py), r4(pz), f, r4(rad)]);
              stats.studs++;
            }
          }
        }
        return mb;
      }
      /* подложка: плоскости, утопленные от открытых граней клетки */
      core(mb, c, mask, col) {
        const [sx, sy, sz] = this.cell, rec = 0.16 * Math.min(sx, sy, sz);
        const cx = this.cx(c.x), cy = this.cy(c.y), cz = this.cz(c.z), h = [sx / 2, sy / 2, sz / 2];
        for (let f = 0; f < 6; f++) {
          if (!(mask & (1 << f))) continue;
          const a = f >> 1, s = (f & 1) ? -1 : 1, u = (a + 1) % 3, w = (a + 2) % 3;
          const base = mb.v, p = [0, 0, 0];
          for (let j = 0; j < 2; j++) for (let i = 0; i < 2; i++) {
            p[a] = s * (h[a] - rec); p[u] = (i ? 1 : -1) * h[u]; p[w] = (j ? 1 : -1) * h[w];
            mb.vert(cx + p[0], cy + p[1], cz + p[2], FACE[f][0], FACE[f][1], FACE[f][2], col, true);
          }
          if (s > 0) { mb.tri(base, base + 1, base + 3); mb.tri(base, base + 3, base + 2); }
          else { mb.tri(base, base + 3, base + 1); mb.tri(base, base + 2, base + 3); }
        }
      }
    }
    /* типовые скосы (в долях полуразмера клетки) */
    const CUT = {
      corner: (sx, sy, sz, d) => [[sx, sy, sz, d == null ? 1.55 : d]],             // срезанный угол
      edge: (a, b, d) => [[a[0] + b[0], a[1] + b[1], a[2] + b[2], d == null ? 1.0 : d]]  // скос ребра между гранями a и b
    };

    /* ======================= МАТЕРИАЛЫ ======================= */
    const MAT = {
      plastic: new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.56, metalness: 0, envMapIntensity: 0.42 }),
      metal: new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.32, metalness: 0.7, envMapIntensity: 0.95 }),
      /* линза для экспорта (glTF): белый × цвета вершин (градиент тонировки), полупрозрачная */
      glass: new T.MeshStandardMaterial({
        color: 0xffffff, vertexColors: true, roughness: 0.05, metalness: 0.2,
        transparent: true, opacity: 0.88, envMapIntensity: 2.2
      }),
      /* линза на экране: тонировка умножением (жёлтое → тёмно-красное, глаза видны) … */
      tint: new T.MeshBasicMaterial({
        color: 0xffffff, vertexColors: true, blending: T.MultiplyBlending, transparent: true,
        premultipliedAlpha: true, depthWrite: false, toneMapped: false
      }),
      /* … плюс зеркальные блики софтбоксов поверх */
      /* блики — только отражение окружения линзы (красные полосы), без бликов от ламп */
      gloss: opts.lensEnv ? new T.MeshBasicMaterial({
        color: 0x000000, envMap: opts.lensEnv, combine: T.AddOperation, reflectivity: 1,
        blending: T.AdditiveBlending, transparent: true, depthWrite: false
      }) : new T.MeshStandardMaterial({
        color: new T.Color('#ff1a05').convertSRGBToLinear(), roughness: 0.05, metalness: 1,
        envMapIntensity: 1.3, blending: T.AdditiveBlending, transparent: true, depthWrite: false
      }),
      frame: new T.MeshStandardMaterial({ color: new T.Color('#8f0d08').convertSRGBToLinear(), roughness: 0.24, metalness: 0.25, envMapIntensity: 1.5 }),
      ink: new T.MeshStandardMaterial({ color: LIN.BK.clone(), roughness: 0.5, metalness: 0, envMapIntensity: 0.4 })
    };
    MAT.glass.name = 'sunglass_lens'; MAT.frame.name = 'sunglass_frame'; MAT.plastic.name = 'abs_plastic';
    MAT.metal.name = 'gunmetal'; MAT.ink.name = 'black_tile';

    /* деталь = группа с сетками по материалам; пластик получает AO */
    function part(name, pivot) {
      const g = new T.Group(); g.name = name;
      if (pivot) g.position.set(pivot[0], pivot[1], pivot[2]);
      const bufs = { plastic: new MeshBuf(true), metal: new MeshBuf() };
      bufs.plastic.owner = bufs.metal.owner = name;
      return {
        g, bufs, name,
        finish() {
          bufs.plastic.bakeAO();
          for (const k in bufs) {
            if (bufs[k].empty) continue;
            const mesh = new T.Mesh(bufs[k].geometry(), MAT[k]);
            mesh.name = name + '_' + k; mesh.castShadow = true; mesh.receiveShadow = true;
            g.add(mesh);
          }
          return g;
        }
      };
    }
    /* подгруппа со своим поворотом (кулак, голень) */
    function subPart(parent, name, pos, rot) {
      const g = new T.Group(); g.name = name;
      g.position.set(pos[0], pos[1], pos[2]); g.rotation.set(rot[0], rot[1], rot[2]);
      const bufs = { plastic: new MeshBuf(true), metal: new MeshBuf() };
      bufs.plastic.owner = bufs.metal.owner = name;
      return {
        g, bufs, name,
        finish() {
          bufs.plastic.bakeAO();
          for (const k in bufs) {
            if (bufs[k].empty) continue;
            const mesh = new T.Mesh(bufs[k].geometry(), MAT[k]);
            mesh.name = name + '_' + k; mesh.castShadow = true; mesh.receiveShadow = true;
            g.add(mesh);
          }
          parent.add(g);
          return g;
        }
      };
    }

    /* ---------- мелкие механические детали ---------- */
    /* болт-ось: шайба + стержень + головка со шлицем, наружу по dir */
    function bolt(p, x, y, z, dir, k) {
      k = k || 1;
      const d = dir, gm = LIN.GM, bk = LIN.BK;
      axial(p.bufs.metal, CYL, x, y, z, d, 0.38 * k, 0.07 * k, LIN.BK);                              // чёрная шайба
      axial(p.bufs.metal, CYL, x + d[0] * 0.06 * k, y + d[1] * 0.06 * k, z + d[2] * 0.06 * k, d, 0.2 * k, 0.42 * k, LIN.SV); // хромированный стержень
      axial(p.bufs.metal, CYL_FINE, x + d[0] * 0.46 * k, y + d[1] * 0.46 * k, z + d[2] * 0.46 * k, d, 0.33 * k, 0.22 * k, gm);
      const q = new T.Quaternion().setFromUnitVectors(new T.Vector3(0, 0, 1), new T.Vector3(d[0], d[1], d[2]));
      const c = new T.Vector3(x + d[0] * 0.685 * k, y + d[1] * 0.685 * k, z + d[2] * 0.685 * k);
      m4.compose(c, q, ss.set(1, 1, 1));
      bevelBox(p.bufs.metal, 0, 0, 0, 0.24 * k, 0.035 * k, 0.03 * k, 0.01, bk, 63, m4);
    }
    /* техник-втулка: кольцо с тёмным отверстием */
    function socket(p, x, y, z, dir, col, rad) {
      rad = rad || 0.34;
      axial(p.bufs.plastic, TUBE, x, y, z, dir, rad, 0.14, col, true);
      axial(p.bufs.plastic, CYL, x, y, z, dir, rad * 0.56, 0.05, LIN.BK.clone().multiplyScalar(0.4), true);
    }
    /* техник-штифт: фланец + головка */
    function pin(p, x, y, z, dir, col, rad) {
      rad = rad || 0.24;
      axial(p.bufs.plastic, CYL, x, y, z, dir, rad * 1.35, 0.08, col, true);
      axial(p.bufs.plastic, CYL, x + dir[0] * 0.07, y + dir[1] * 0.07, z + dir[2] * 0.07, dir, rad, 0.3, col, true);
      axial(p.bufs.plastic, CYL, x + dir[0] * 0.36, y + dir[1] * 0.36, z + dir[2] * 0.36, dir, rad * 0.45, 0.02, col.clone().multiplyScalar(0.35), true);
    }
    /* светлая ось-стержень (соединение плечо–корпус) */
    function rod(p, x, y, z, dir, len, col) {
      axial(p.bufs.plastic, CYL, x, y, z, dir, 0.13, len, col || LIN.LG, true);
    }
    /* шип по произвольной нормали (на скошенной грани) */
    function studOn(mb, x, y, z, n, rad, col) {
      qq.setFromUnitVectors(UP, vv.set(n[0], n[1], n[2]).normalize());
      m4.compose(new T.Vector3(x, y, z), qq, ss.set(rad, rad, rad));
      emitTpl(mb, STUD, m4, col);
      stats.studs++;
    }
    /* шаровой шарнир: тёмное гнездо на детали, воронёный шар и ось в соседнюю деталь (по dir) */
    function balljoint(p, x, y, z, dir, r) {
      const d = dir, gm = LIN.GM.clone().multiplyScalar(2.2);
      axial(p.bufs.plastic, CYL_FINE, x, y, z, d, r * 1.55, 0.16, LIN.DG2.clone().multiplyScalar(0.7), true);   // гнездо
      axial(p.bufs.plastic, CYL_FINE, x + d[0] * 0.15, y + d[1] * 0.15, z + d[2] * 0.15, d, r * 1.25, 0.05, LIN.BK, true);
      m4.compose(vv.set(x + d[0] * r * 0.55, y + d[1] * r * 0.55, z + d[2] * r * 0.55), qq.identity(), ss.set(r, r, r));
      emitTpl(p.bufs.metal, BALL, m4, gm);                                                                       // шар
      axial(p.bufs.metal, CYL, x + d[0] * r * 0.9, y + d[1] * r * 0.9, z + d[2] * r * 0.9, d, r * 0.55, r * 1.1, gm); // ось
    }
    /* магнитная шайба на стыке: в сборке скрыта, в разлёте видна */
    function magnet(p, x, y, z, dir) {
      axial(p.bufs.metal, CYL_FINE, x - dir[0] * 0.02, y - dir[1] * 0.02, z - dir[2] * 0.02, dir, 0.27, 0.05, LIN.SV);
      axial(p.bufs.metal, CYL_FINE, x + dir[0] * 0.025, y + dir[1] * 0.025, z + dir[2] * 0.025, dir, 0.12, 0.012, LIN.GM);
      stats.magnets++;
    }

    const parts = {};
    const root = new T.Group(); root.name = 'PixelMech';

    /* ============================================================
       ГОЛОВА — узел у верха шеи (0, 20.5, 0). Замеры — сетка по листу 1 (docs/PROMPT-v3.md, раздел 12).
       lico (блок с лицевой панелью), bokL/bokR (боковые плиты),
       shapka (череп-шапка с пришельцем), ochki (очки).
       ============================================================ */
    const LIFT = 3.5;                                        // v5: тело длиннее (лист 2) — голова выше на 3.5
    const HEAD_Y = 17.0 + LIFT;
    const W = y => y - 17.0;                                 // высоты головы — в координатах v4 (низ головы 17.0)
    const head = new T.Group(); head.name = 'golova'; head.position.set(0, HEAD_Y, 0); root.add(head);
    head.rotation.x = opts.headTilt == null ? 0 : +opts.headTilt;     // наклон головы вперёд (поза)
    const KUB = opts.lico === 'kub' || opts.lico === 'zver';            // прежняя голова-куб (и основа «зверя»)
    const FORMA_D = (opts.golova === 'list' && !KUB && global.PixelMechForma) || null;   // голова по листу (данные tools/forma)
    const V7 = (opts.golova === 'v7' || !!FORMA_D) && !KUB;                           // проба v7 по листу 24.10 (docs/GOLOVA-v7.md)
    const FACE_ROW = 0.512, FACE_ROWS = 13, FACE_Y0 = 0.2;
    const CAP_Y = FACE_Y0 + FACE_ROWS * FACE_ROW;            // низ шапки: 6.86 над низом головы

    /* ---- лицевой блок: 12 × 13 рядов по 0.512 × 12; спереди 2 клетки жёлтые; под линзой — длинные кирпичи.
            Щёки и подбородок (Александр 22.10, фото): щёки идут прямо вниз во всю ширину, подбородок широкий и
            плоский, углы чуть скруглены (раньше сужался двумя ступенями ±3.2/±4.0); затылок скруглён в плане,
            низ затылка уходит к шее, посередине сзади — выступ затылка ---- */
    const lico = part('lico');
    {
      const chinW = y => 0;                                          // щёки прямые — ничего не срезано (22.10)
      const gone = v => v.x < chinW(v.y) || v.x > 11 - chinW(v.y)
        || (v.z === 0 && (v.x < 2 || v.x > 9 || v.y <= 2)) || (v.z === 1 && v.y <= 1);
      new Vox({ cell: [S, FACE_ROW, S], at: [-4.8, FACE_Y0, -4.8], maxLen: 6, merge: 0.62, salt: 3, gap: 0.02, bev: 0.034,
        mergeFor: row => (row >= 6 && row <= 11 ? 0.93 : 0.62),
        clip: c => {
          const sx = c.x < 6 ? -1 : 1, pl = [];
          const edgeX = c.x === chinW(c.y) || c.x === 11 - chinW(c.y);
          if (c.y === 0 && edgeX) pl.push(...CUT.edge([sx, 0, 0], [0, -1, 0], 0.6));   // углы подбородка — чуть скруглены
          if (c.z === 11 && edgeX && c.y <= 5) pl.push(...CUT.edge([sx, 0, 0], [0, 0, 1], 0.8));   // щёки огибают угол лица
          if (c.z === 0 && (c.x === 2 || c.x === 9)) pl.push(...CUT.edge([sx, 0, 0], [0, 0, -1], 0.9));  // затылок в плане
          if (c.z === 1 && (c.x === 0 || c.x === 11)) pl.push(...CUT.edge([sx, 0, 0], [0, 0, -1], 0.9));
          if ((c.z === 0 && c.y === 3) || (c.z === 1 && c.y === 2) || (c.z === 2 && c.y === 0)) pl.push(...CUT.edge([0, -1, 0], [0, 0, -1], 0.9));
          return pl.length ? pl : null;
        } })
        .box(0, 12, 0, FACE_ROWS, 0, 12, (x, y, z) => (z >= 10 ? 'yellow' : (z <= 1 ? 'skull' : 'head')))
        .paint(v => (v.y === 0 && v.z < 10 ? 'dark' : null))
        .del(gone)
        .build(lico.bufs.plastic);
      new Vox({ cell: [S, 0.2, S], at: [-3.2, 0.0, -3.2], maxLen: 4, salt: 5 })   // тёмные плиты низа головы
        .box(0, 8, 0, 1, 0, 8, 'dark').build(lico.bufs.plastic);
      /* выступ затылка: плита 4.8 × 3.6 на 0.4 назад, края скруглены */
      new Vox({ cell: [S, FACE_ROW, 0.4], at: [-2.4, FACE_Y0 + 3 * FACE_ROW, -5.2], maxLen: 3, merge: 0.5, salt: 6,
        clip: c => {
          const pl = [];
          if (c.x === 0 || c.x === 5) pl.push(...CUT.edge([c.x ? 1 : -1, 0, 0], [0, 0, -1], 0.8));
          if (c.y === 6) pl.push(...CUT.edge([0, 1, 0], [0, 0, -1], 0.8));
          if (c.y === 0) pl.push(...CUT.edge([0, -1, 0], [0, 0, -1], 0.8));
          return pl.length ? pl : null;
        }
      }).box(0, 6, 0, 7, 0, 1, 'skull').build(lico.bufs.plastic);
      new Vox({ cell: [S, FACE_ROW, 0.4], at: [-1.6, FACE_Y0 + 4 * FACE_ROW, -5.6], maxLen: 4, merge: 0.6, salt: 7,
        clip: c => {
          const pl = [];
          if (c.x === 0 || c.x === 3) pl.push(...CUT.edge([c.x ? 1 : -1, 0, 0], [0, 0, -1], 0.8));
          if (c.y === 4 || c.y === 0) pl.push(...CUT.edge([0, c.y ? 1 : -1, 0], [0, 0, -1], 0.8));
          return pl.length ? pl : null;
        }
      }).box(0, 4, 0, 5, 0, 1, 'skull').build(lico.bufs.plastic);
      /* объёмное лицо (Александр 22.10, фото: «там объёмное лицо»): щёки и подбородок выступают вперёд накладкой —
         подкова вокруг рта (ряды под линзой), края скруглены наружу и вниз */
      const cheek = (x, y) => (x <= 1 || x >= 10) ? y <= 5 : y <= 2;
      const sloy = (z0, dz, fn) => new Vox({ cell: [S, FACE_ROW, dz], at: [-4.8, FACE_Y0, z0], maxLen: 4, merge: 0.55, salt: 8 + dz * 10,
        gap: 0.02, bev: 0.03,
        clip: c => {
          const pl = [], top = c.y === 5 || !fn(c.x, c.y + 1), inL = !fn(c.x - 1, c.y), inR = !fn(c.x + 1, c.y);
          if (c.x === 0 || (inL && c.x > 0)) pl.push(...CUT.edge([-1, 0, 0], [0, 0, 1], 0.75));
          if (c.x === 11 || (inR && c.x < 11)) pl.push(...CUT.edge([1, 0, 0], [0, 0, 1], 0.75));
          if (top) pl.push(...CUT.edge([0, 1, 0], [0, 0, 1], 0.75));
          if (c.y === 0) pl.push(...CUT.edge([0, -1, 0], [0, 0, 1], 0.6));          // подбородок скруглён вперёд
          return pl.length ? pl : null;
        } })
        .box(0, 12, 0, 6, 0, 1, (x, y) => (fn(x, y) ? 'yellow' : null))
        .del(v => !v.cls)
        .build(lico.bufs.plastic);
      sloy(4.8, 0.4, cheek);
      sloy(5.2, 0.2, (x, y) => (x <= 1 || x >= 10) && y >= 1 && y <= 4);              // середина щеки — выпуклее
      [[-3.2, -2.4], [3.2, -2.4], [-3.2, 2.4], [3.2, 2.4]].forEach(([x, z]) => magnet(lico, x, CAP_Y + 0.02, z, [0, 1, 0]));
      [-1, 1].forEach(s => { magnet(lico, s * 4.8, 2.4, -2.0, [s, 0, 0]); magnet(lico, s * 4.8, 5.2, -2.0, [s, 0, 0]); });
    }
    lico.finish(); head.add(lico.g); parts.lico = lico;

    /* ---- живое лицо: чёрные плитки глаз и рта (отдельные — для мимики) ---- */
    const FZ = 4.8;
    const faceParts = {};
    function inkTile(name, w, h, x, y) {
      const mb = new MeshBuf();
      bevelBox(mb, 0, 0, 0, w / 2, h / 2, 0.06, 0.03, LIN.BK, 63 - 32);
      const mesh = new T.Mesh(mb.geometry(), MAT.ink);
      mesh.name = name; mesh.position.set(x, W(y), FZ - 0.005); mesh.castShadow = true;
      lico.g.add(mesh); faceParts[name] = mesh;
      return mesh;
    }
    inkTile('eyeL', 1.5, 1.5, -1.65, 21.7);
    inkTile('eyeR', 1.5, 1.5, 1.65, 21.7);
    /* 8-битная улыбка: полоса рта и уголки вплотную над её концами (без щелей — улыбка читается);
       «ножки» — вторая ступень уголков, появляются в широкой улыбке */
    inkTile('mouthBar', 4.4, 0.52, 0, 19.2);
    inkTile('mouthCornerL', 0.5, 0.54, -2.2, 19.71);
    inkTile('mouthCornerR', 0.5, 0.54, 2.2, 19.71);
    inkTile('mouthLegL', 0.5, 0.54, -2.64, 20.23).visible = false;
    inkTile('mouthLegR', 0.5, 0.54, 2.64, 20.23).visible = false;
    {                                                        // зубы для улыбки/речи
      const mb = new MeshBuf();
      bevelBox(mb, 0, 0, 0, 1.7, 0.15, 0.05, 0.03, LIN.WH2, 63 - 32);
      const t = new T.Mesh(mb.geometry(), MAT.plastic);
      t.name = 'teeth'; t.position.set(0, W(19.2), FZ + 0.02); t.visible = false;
      lico.g.add(t); faceParts.teeth = t;
    }

    /* ---- боковые плиты как на листе 1: наборные модули 1.8 × 1.6 (4 ряда × 5 столбцов, y 1.15…8.35,
            z −4.8…3.2) разной толщины — белые и светлые кирпичи-«фары» с полым круглым шипом вбок,
            тёмный передний столбец с полыми шипами вперёд, тёмный техник-кирпич с двумя отверстиями
            сверху спереди, тёмный узел шарнира дужки, оранжевое ухо из двух кирпичей с овальными
            выступами; задний столбец скруглён вслед за затылком, низ и верх — скосы ---- */
    function sidePlate(s) {
      const name = s < 0 ? 'bokL' : 'bokR';
      const p = part(name), P = p.bufs.plastic;
      const XI = 4.8, Y0 = W(18.15), MH = 1.8, MZ = 1.6, Z0 = -4.8, G = 0.024;
      const d = [s, 0, 0];
      /* модули: [ряд, столбец, ширина в столбцах, класс, толщина] */
      const MOD = [
        [0, 0, 1, 'LG', 1.0], [0, 1, 1, 'LG2', 1.05], [0, 2, 1, 'WH', 1.2], [0, 3, 1, 'LG', 1.15], [0, 4, 1, 'DG', 1.1],
        [1, 0, 1, 'WH', 1.1], [1, 1, 1, 'LG', 1.05], [1, 2, 1, 'WH', 1.25], [1, 3, 1, 'WH2', 1.25], [1, 4, 1, 'DG', 1.15],
        [2, 0, 1, 'LG', 1.1], [2, 1, 1, 'LG2', 1.05], [2, 2, 1, 'LG', 1.25], [2, 3, 1, 'hinge', 1.25], [2, 4, 1, 'DG', 1.15],
        [3, 0, 1, 'LG', 1.0], [3, 1, 1, 'WH', 1.1], [3, 2, 1, 'WH2', 1.2], [3, 3, 2, 'tech', 1.2]
      ];
      const hollow = (x, y, z, n, r, col) => {                     // полый шип-«фара»: шип и тёмная середина
        studOn(P, x, y, z, n, r, col);
        axial(P, CYL, x + n[0] * r * 0.66, y + n[1] * r * 0.66, z + n[2] * r * 0.66, n, r * 0.56, 0.06, LIN.BK.clone().multiplyScalar(0.8), true);
      };
      for (const [row, colI, span, cls, th] of MOD) {
        const key = cls === 'hinge' || cls === 'tech' ? 'DG' : cls;
        const col = tone(key, 0.03);
        const cx = s * (XI + th / 2), cy = Y0 + (row + 0.5) * MH, cz = Z0 + (colI + span / 2) * MZ;
        const h = [th / 2 - G / 2, MH / 2 - G / 2, span * MZ / 2 - G / 2];
        const pl = [];
        if (colI === 0) pl.push([s, 0, -2, -1]);                                    // задний столбец — по дуге затылка
        if (row === 0) pl.push([s, -1, 0, 0.9]);                                    // низ — скос к подбородку
        if (row === 3 && cls !== 'tech') pl.push([s, 1, 0, 1.0]);                   // верх — скруглён
        if (colI + span === 5 && (row === 0 || row === 3)) pl.push([s, row ? 1 : -1, 1, 1.5]);
        P.occ.addBox(cx - h[0], cy - h[1], cz - h[2], cx + h[0], cy + h[1], cz + h[2]);
        if (pl.length) { clipBlock(P, [cx, cy, cz], h, pl, col); stats.slopes++; }
        else { bevelBox(P, cx, cy, cz, h[0], h[1], h[2], 0.07, col, 63 - (s < 0 ? 1 : 2)); stats.bricks++; }
        rec(P, pl.length ? 'slope' : 'brick', [cx, cy, cz], [th / 2, MH / 2, span * MZ / 2], col, { cls, planes: pl.length ? pl : undefined });
        const xo = s * (XI + th) + s * 0.004;
        /* «фара» на наружной грани белых и светлых модулей (кроме скошенных краёв) */
        if ((key === 'WH' || key === 'WH2' || key === 'LG') && row > 0 && row < 3 && colI > 0) hollow(xo, cy, cz, d, 0.46, tone('LG', 0.02));
        if (row === 3 && colI >= 1 && colI <= 2) studOn(P, cx - s * h[0] * 0.5, cy + h[1], cz, [0, 1, 0], 0.26, col.clone());   // шип сверху
        if (colI === 4 && row < 3) hollow(cx, cy, Z0 + 5 * MZ + 0.004, [0, 0, 1], 0.36, tone('LG', 0.02));   // шипы вперёд на торце
        if (cls === 'tech') {                                              // техник-кирпич: два отверстия вбок и одно вперёд
          socket(p, xo, cy - 0.1, Z0 + 3.5 * MZ, d, LIN.DG2, 0.36);
          socket(p, xo, cy - 0.1, Z0 + 4.5 * MZ, d, LIN.DG2, 0.36);
          socket(p, cx, cy, Z0 + 5 * MZ + 0.011, [0, 0, 1], LIN.DG2, 0.3);
        }
        if (cls === 'hinge') {                                             // узел шарнира дужки очков
          axial(P, CYL, xo, W(22.6), 0.1, d, 0.42, 0.08, LIN.DG2, true);
          pin(p, xo + s * 0.08, W(22.6), 0.1, d, LIN.BK.clone().multiplyScalar(1.6), 0.22);
        }
      }
      /* ухо: два оранжевых кирпича на заднем-среднем столбце (ряды 1–2), с овальными выступами */
      const EX = XI + 1.05, ET = 0.95;
      [1, 2].forEach(row => {
        const cx = s * (EX + ET / 2), cy = Y0 + (row + 0.5) * MH, cz = Z0 + 1.5 * MZ;
        const h = [ET / 2 - G / 2, MH / 2 - G / 2, MZ / 2 - 0.08];
        const col = tone(row === 1 ? 'O1' : 'O5', 0.03);
        clipBlock(P, [cx, cy, cz], h, [[s, row === 2 ? 1 : -1, 0, 1.55], [s, 0, -1, 1.45]], col);
        P.occ.addBox(cx - h[0], cy - h[1], cz - h[2], cx + h[0], cy + h[1], cz + h[2]);
        stats.slopes++;
        const ox = s * (EX + ET) + s * 0.004;
        m4.compose(new T.Vector3(ox, cy, cz + 0.1), new T.Quaternion().setFromUnitVectors(UP, new T.Vector3(s, 0, 0)), ss.set(0.5, 0.3, 0.32));
        emitTpl(P, STUD, m4, col.clone().multiplyScalar(1.06));             // овальный выступ
        stats.studs++;
      });
      [-2.8, 1.2].forEach(z => magnet(p, s * 4.8, 3.9, z, [-s, 0, 0]));
      p.finish(); head.add(p.g); parts[name] = p;
    }
    sidePlate(-1); sidePlate(1);

    /* ---- шапка-череп: макро-кирпичи с крупными шипами, пришелец утоплен в кольцо ---- */
    const shapka = part('shapka');
    const ALIEN = [                                          // сзади → вперёд, 8 шипов шириной
      '.A....A.',
      '.XXXXXX.',
      'XXoXXoXX',
      'XXXXXXXX',
      'XXXXXXXX',
      'XX....XX'
    ];
    const alienAt = (x, z) => {                             // x: 0..11 (шапка), z: 0..11
      const i = z - 4, j = x - 2;
      if (i < 0 || i >= 6 || j < 0 || j >= 8) return '.';
      return ALIEN[i][j];
    };
    {
      /* макро-кирпичи 1.6 × 0.8 × 1.6 с крупными шипами, как на панели HEAD TOP:
         два слоя 6 × 6 (верхний со скруглёнными углами, сзади справа — тёмный скос), пришелец стоит сверху */
      const M = 1.6, inAlien = (x, z) => x >= 1 && x <= 4 && z >= 2 && z <= 4;
      const corner = c => (c.x === 0 || c.x === 5) && (c.z === 0 || c.z === 5);
      new Vox({ cell: [M, S, M], at: [-4.8, CAP_Y, -4.8], maxLen: 2, merge: 0.4, salt: 21, bev: 0.05,
        clip: c => (corner(c) && c.z === 0 ? CUT.edge([c.x ? 1 : -1, 0, 0], [0, 0, -1], 0.05) : null)   // затылок скруглён
      }).box(0, 6, 0, 1, 0, 6, (x, y, z) => (z === 0 ? 'headBack' : 'head')).build(shapka.bufs.plastic);
      new Vox({ cell: [M, S, M], at: [-4.8, CAP_Y + S, -4.8], maxLen: 2, merge: 0.35, salt: 22, bev: 0.05,
        studs: (c, f) => (f === 2 && !inAlien(c.x, c.z)) ? 0.46 : false,
        clip: c => {
          const sx = c.x ? 1 : -1;
          if (corner(c) && c.z === 0) return [[sx, 0, -1, 0.05], [0, 1, -1, 0.6]];
          if (corner(c)) return CUT.edge([sx, 0, 0], [0, 1, 0], 1.45);
          if (c.z === 0) return CUT.edge([0, 1, 0], [0, 0, -1], 0.6);                          // верх затылка скруглён
          return null;
        }
      }).box(0, 6, 0, 1, 0, 6, (x, y, z) => ((x === 5 && z === 0) ? 'DG' : (z === 0 ? 'headBack' : (z === 5 ? 'WH' : 'head'))))
        .build(shapka.bufs.plastic);
      /* пришелец на шапке: тело — кирпич 0.8 + гладкая плитка 0.24 (1.04),
         рожки — два блока по 1.0 с шипом (выше тела на ~1); глазницы — утопленные чёрные плитки */
      alienOn(shapka.bufs.plastic, CAP_Y + 2 * S);
    }
    shapka.finish(); head.add(shapka.g); parts.shapka = shapka;
    function alienOn(mb, AY) {
      const HORN = +(opts.hornH || (V7 ? 0.42 : 1.0));                 // v7: рожки — низкие ушки, не антенны
      const alien = new Vox({ cell: [S, 0.8, S], at: [-3.2, AY, -1.6], maxLen: 4, merge: 0.8, salt: 23, gap: 0.024 });
      const tiles = new Vox({ cell: [S, 0.24, S], at: [-3.2, AY + 0.8, -1.6], maxLen: 4, merge: 0.85, salt: 24, gap: 0.024, bev: 0.03 });
      const horns = new Vox({ cell: [S, HORN, S], at: [-3.2, AY, -1.6], maxLen: 1, salt: 26, gap: 0.024,
        studs: (c, f) => f === 2 && c.y === 1 });
      ALIEN.forEach((row, i) => [...row].forEach((ch, j) => {
        if (ch === 'X') { alien.set(j, 0, i, 'purple'); tiles.set(j, 0, i, 'purple'); }
        if (ch === 'o') alien.set(j, 0, i, 'BK');
        if (ch === 'A') for (let y = 0; y < 2; y++) horns.set(j, y, i, 'PU');
      }));
      [alien, tiles, horns].forEach(v => v.build(mb));
    }

    /* ---- ОЧКИ: зеркальный щиток с переносицей, тёмная тонировка, оправа, дужки к шарниру ---- */
    const ochki = new T.Group(); ochki.name = 'ochki'; head.add(ochki);
    {
      /* в плане: пологая дуга спереди (радиус RF — зеркальные полосы расходятся к краям, как на листе),
         скругление R у висков, прямые бока назад до ZB */
      const A = KUB ? 6.2 : (FORMA_D ? FORMA_D.visor.a : V7 ? 4.85 : 5.35), ZF = 5.56, R = V7 ? 0.35 : 0.95, RF = V7 ? 400 : 20, ZB = FORMA_D ? FORMA_D.visor.zb : V7 ? 2.9 : 1.9;   // v6: виски по шлему; v7: прямой брусок (docs/GOLOVA-v7.md)
      const VIS = FORMA_D && FORMA_D.visor;                                // по листу: рамка красного козырька, замер
      const YT = VIS ? VIS.yt : W(23.32), YB = VIS ? VIS.yb : W(20.45), YN = VIS ? VIS.yb + 0.75 : W(21.24), YS = VIS ? VIS.yb + (VIS.yt - VIS.yb) * 0.68 : W(22.4);
      const P0 = Math.asin((A - R) / (RF - R));
      const Lf = RF * P0, La = R * (Math.PI / 2 - P0);
      const CX0 = RF * Math.sin(P0) - R * Math.sin(P0), CZ0 = ZF - RF * (1 - Math.cos(P0)) - R * Math.cos(P0);
      const Ls = CZ0 - ZB, Lt = Lf + La + Ls;
      const sm = (a, b, x) => { const t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
      function at(u) {
        const s = u < 0 ? -1 : 1, a = Math.abs(u);
        if (a <= Lf) { const f = a / RF; return { x: s * RF * Math.sin(f), z: ZF - RF * (1 - Math.cos(f)), nx: s * Math.sin(f), nz: Math.cos(f) }; }
        if (a <= Lf + La) {
          const t = P0 + (a - Lf) / R;
          return { x: s * (CX0 + R * Math.sin(t)), z: CZ0 + R * Math.cos(t), nx: s * Math.sin(t), nz: Math.cos(t) };
        }
        return { x: s * A, z: CZ0 - (a - Lf - La), nx: s, nz: 0 };
      }
      function bottom(u) {
        const a = Math.abs(u);
        let y = YB;
        y += (YN - YB) * (1 - sm(0.55, 1.05, a));               // переносица: трапеция ±1.0 внизу
        y += 0.45 * sm(3.4, Lf + La * 0.8, a);                  // скругление к вискам
        y += (YS - YB - 0.45) * sm(Lf + La * 0.6, Lt, a);       // бок сужается к дужке
        return y;
      }
      const top = u => YT - 0.05 * sm(Lf + La, Lt, Math.abs(u));
      const N = 150, M = 12, TH = V7 ? 0.24 : 0.055;                 // v7: толстый, как кирпичный
      const YM = (YT + YB) / 2 + 0.2, HH = (YT - YB) / 2, BUL = V7 ? 0.02 : 0.24;
      const bulge = y => -BUL * Math.pow((y - YM) / HH, 2);
      const bulgeD = y => -2 * BUL * (y - YM) / (HH * HH);
      /* тонировка: тёмный красный (жёлтое под ним → #70…90 красный, чёрное → чёрное), светлее у верхней кромки */
      const tLow = new T.Color(FORMA_D ? '#e0441a' : '#720c00').convertSRGBToLinear(), tHigh = new T.Color(FORMA_D ? '#ee5a2a' : '#861206').convertSRGBToLinear();   // по листу — светлее: жёлтое лицо под ним рыже-красное
      const tintAt = y => tLow.clone().lerp(tHigh, sm(0.82, 1.0, (y - YB) / (YT - YB)));
      const lens = new MeshBuf(), rim = new MeshBuf();
      const col = new T.Color(1, 1, 1);
      for (const side of [1, -1]) {
        const base = lens.v;
        for (let i = 0; i <= N; i++) {
          const u = -Lt + 2 * Lt * i / N, p = at(u), b = bottom(u), t = top(u);
          for (let j = 0; j <= M; j++) {
            const y = b + (t - b) * j / M, o = TH * side + bulge(y), d = -bulgeD(y), l = Math.hypot(1, d);
            lens.vert(p.x + p.nx * o, y, p.z + p.nz * o, p.nx * side / l, d * side / l, p.nz * side / l, tintAt(y));
          }
        }
        for (let i = 0; i < N; i++) for (let j = 0; j < M; j++) {
          const a = base + i * (M + 1) + j, b2 = a + M + 1;
          if (side > 0) { lens.tri(a, b2, a + 1); lens.tri(b2, b2 + 1, a + 1); }
          else { lens.tri(a, a + 1, b2); lens.tri(b2, a + 1, b2 + 1); }
        }
      }
      {
        const base = lens.v;
        for (let i = 0; i <= N; i++) {
          const u = -Lt + 2 * Lt * i / N, p = at(u), b = bottom(u), o = bulge(b), c2 = tintAt(b);
          lens.vert(p.x + p.nx * (TH + o), b, p.z + p.nz * (TH + o), 0, -1, 0, c2);
          lens.vert(p.x - p.nx * (TH - o), b, p.z - p.nz * (TH - o), 0, -1, 0, c2);
        }
        for (let i = 0; i < N; i++) { const a = base + i * 2; lens.tri(a, a + 1, a + 2); lens.tri(a + 1, a + 3, a + 2); }
      }
      const lensGeo = lens.geometry();
      const lensMesh = new T.Mesh(lensGeo, MAT.tint);
      lensMesh.name = 'ochki_linza'; lensMesh.renderOrder = 2; ochki.add(lensMesh);
      lensMesh.castShadow = true;                                   // линза затеняет лицо и переносицу под собой
      const glossMesh = new T.Mesh(lensGeo, MAT.gloss);
      glossMesh.name = 'ochki_blik'; glossMesh.renderOrder = 4; lensMesh.add(glossMesh);
      /* оправа-бровь по всей дуге */
      const RW = 0.12, RH = 0.24;
      {
        const base = rim.v, prof = [[-RW, -RH], [RW, -RH], [RW, 0.02], [RW * 0.6, 0.07], [-RW * 0.6, 0.07], [-RW, 0.02], [-RW, -RH]];
        const nrm = [[0, -1], [0, -1], [1, 0], [0.5, 0.86], [-0.5, 0.86], [-1, 0], [-1, 0]];
        const P = prof.length;
        for (let i = 0; i <= N; i++) {
          const u = -Lt + 2 * Lt * i / N, p = at(u), t = top(u), bo = bulge(t);
          prof.forEach(([o, dy], k) => {
            rim.vert(p.x + p.nx * (o + bo), t + dy, p.z + p.nz * (o + bo), p.nx * nrm[k][0], nrm[k][1], p.nz * nrm[k][0], col);
          });
        }
        for (let i = 0; i < N; i++) for (let k = 0; k < P - 1; k++) {
          const a = base + i * P + k, b2 = a + P;
          rim.tri(a, b2, a + 1); rim.tri(b2, b2 + 1, a + 1);
        }
      }
      /* дужки: петля у линзы с винтом, прямая часть назад, загиб к шарниру на плите */
      [-1, 1].forEach(s => {
        const ax = s * (A + 0.02), yT = YT - 0.1, h = 0.46;
        bevelBox(rim, ax, yT - h / 2, 1.05, 0.1, h / 2, 0.95, 0.04, col, 63);
        bevelBox(rim, ax, yT - 0.55, 0.15, 0.1, 0.36, 0.2, 0.05, col, 63);
        bevelBox(rim, ax, yT - 0.2, ZB + 0.1, 0.17, 0.33, 0.27, 0.05, col, 63);
        axial(rim, CYL, ax + s * 0.16, yT - 0.2, ZB + 0.1, [s, 0, 0], 0.1, 0.05, col.clone().multiplyScalar(0.4));
        axial(rim, HEX, ax + s * 0.2, yT - 0.2, ZB + 0.1, [s, 0, 0], 0.045, 0.02, col.clone().multiplyScalar(0.15));
      });
      const rimMesh = new T.Mesh(rim.geometry(), MAT.frame);
      rimMesh.name = 'ochki_oprava'; rimMesh.castShadow = true; ochki.add(rimMesh);
    }
    parts.ochki = { g: ochki, name: 'ochki' };

    /* ============================================================
       ГОЛОВА v6 «МАСКА НА ШЛЕМЕ» (по умолчанию; лист Александра 23.10 — пять ракурсов, docs/GOLOVA-v6.md):
       шлем — суперэллипсоид из кирпичей (ширина 10, глубина 0.86 ширины, купол сверху и сзади), каждый
       открытый край скошен; спереди его срезает плоская жёлтая маска 8.8 × 6.7 с объёмными щеками —
       мимика (глаза, улыбка, зубы) живёт на ней без изменений. Наушники — серые диски в оранжевых
       квадратных рамках с круглыми шипами над и под ними; сзади — крышка-диск; пришелец — на площадке
       макушки. Числа формы — таблица SHLEM (замер листа: ширина/высота ≈ 1.3, глубина/ширина ≈ 0.86,
       купол сзади сужается до ~0.6). Прежний куб — opts.lico = 'kub'.
       ============================================================ */
    /* ============================================================
       ГОЛОВА ПО ЛИСТУ (opts.golova = 'list', проба): объём и цвет сняты с самого листа — оболочка по силуэтам
       видов спереди и сбоку в сетке шипов, цвет клетки — с вида, куда она смотрит (tools/forma/obolochka.py →
       src/plus-forma.js). Ни одного числа формы на глаз. Мимика: рот-улыбка поверх (на листе уголки вниз).
       ============================================================ */
    const FORMA = FORMA_D;
    if (FORMA) {
      const F = global.PixelMechForma;
      lico.g.children.filter(o => o.isMesh && /_(plastic|metal)$/.test(o.name)).forEach(o => lico.g.remove(o));
      parts.bokL.g.clear(); parts.bokR.g.clear(); parts.shapka.g.clear();   // козырёк — прозрачная линза по замеру листа
      const at = (i, j, k) => (i < 0 || j < 0 || k < 0 || i >= F.NX || j >= F.NY || k >= F.NZ) ? '.' : F.cells[(k * F.NY + j) * F.NX + i];
      const CL = { Y: 'yellow', W: 'head', L: 'LG', M: 'MG', D: 'dark', K: 'BK', P: 'purple', R: 'RD1', r: 'RD2', O: 'O2' };
      let faceZ = -1e9;
      for (let k = 0; k < F.NZ; k++) for (let j = 0; j < F.NY; j++) for (let i = 0; i < F.NX; i++)
        if (at(i, j, k) === 'Y') faceZ = Math.max(faceZ, F.Z0 + (k + 1) * F.S);
      const DIRS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, 0, 1], [0, 0, -1]];
      const fp = part('forma');
      new Vox({ cell: [F.S, F.R, F.S], at: [F.X0, F.Y0, F.Z0], maxLen: 3, merge: 0.5, salt: 151, gap: 0.02, bev: 0.034,
        studs: (c, f) => f === 2 && at(c.x, c.y + 1, c.z) === '.' && 'WLMP'.includes(at(c.x, c.y, c.z)),
        clip: c => {                                                      // скос открытых рёбер — силуэт круглее ступенек
          const open = DIRS.filter(([a, b, d]) => at(c.x + a, c.y + b, c.z + d) === '.'), pl = [];
          for (let p2 = 0; p2 < open.length; p2++) for (let q = p2 + 1; q < open.length; q++) {
            const A2 = open[p2], B = open[q];
            if (A2[0] + B[0] === 0 && A2[1] + B[1] === 0 && A2[2] + B[2] === 0) continue;
            pl.push(...CUT.edge(A2, B, 0.82));
          }
          return pl.length ? pl : null;
        } })
        .box(0, F.NX, 0, F.NY, 0, F.NZ, (i, j, k) => {
          const ch = at(i, j, k);
          if (ch === '.') return null;
          const y = F.Y0 + (j + 0.5) * F.R, z = F.Z0 + (k + 0.5) * F.S;
          if (ch === 'K' && y < F.visor.yb && z > faceZ - 1.2) return 'yellow';   // нарисованный рот листа — под улыбку
          if (ch === 'V' || ch === 'R' || ch === 'r') {                     // козырёк делает линза: впереди лица — пусто, на лице — жёлтое, глаза — чёрные
            if (z > faceZ + 0.05) return null;
            return ch === 'r' ? 'BK' : 'yellow';
          }
          return CL[ch] || 'dark';
        })
        .del(v => !v.cls).build(fp.bufs.plastic);
      fp.finish(); lico.g.add(fp.g);
      faceParts.eyeL.visible = faceParts.eyeR.visible = false;              // глаза нарисованы под козырьком
      ['mouthBar', 'mouthCornerL', 'mouthCornerR', 'mouthLegL', 'mouthLegR', 'teeth'].forEach(n => { faceParts[n].position.z = faceZ + 0.01; faceParts[n].userData.home && (faceParts[n].userData.home.z = faceZ + 0.01); });
    }
    /* ГОЛОВА-СЕТКА (opts.golova = 'setka', проба): литая голова — сетка генератора 3D по виду 3/4 листа, цвета вершин
       с видов листа, сведённые к палитре кирпичей (tools/forma/raskraska.py → src/plus-golova-setka.js). */
    const SETKA = opts.golova === 'setka' && !KUB && global.PixelMechGolovaSetka;
    if (SETKA) {
      const G = global.PixelMechGolovaSetka;
      lico.g.children.filter(o => o.isMesh && /_(plastic|metal)$/.test(o.name)).forEach(o => lico.g.remove(o));
      parts.bokL.g.clear(); parts.bokR.g.clear(); parts.shapka.g.clear(); ochki.clear();   // козырёк — в самой сетке
      const bin = b64 => { const t = (typeof atob === 'function') ? atob(b64) : Buffer.from(b64, 'base64').toString('binary'); const u = new Uint8Array(t.length); for (let i = 0; i < t.length; i++) u[i] = t.charCodeAt(i); return u; };
      const pz = new Float32Array(bin(G.pos).buffer), ix = new Uint32Array(bin(G.idx).buffer), kl = bin(G.cls);
      const KEY = { Y: 'YE', W: 'WH', L: 'LG', M: 'MG', D: 'DG', K: 'BK', P: 'PU', R: 'RD1', r: 'RD2', O: 'O2' };
      const col = new Float32Array(G.nv * 3);
      let faceZ = -1e9;
      for (let i = 0; i < G.nv; i++) {
        const ch = G.kl[kl[i]], c = LIN[KEY[ch] || 'DG'];
        col[i * 3] = c.r; col[i * 3 + 1] = c.g; col[i * 3 + 2] = c.b;
        if (ch === 'Y' && pz[i * 3 + 1] < 3.2 && Math.abs(pz[i * 3]) < 2.5) faceZ = Math.max(faceZ, pz[i * 3 + 2]);
      }
      const zs = FZ - faceZ;                                                // лицо — на месте прежнего (мимика на FZ)
      const pos = pz.slice(); for (let i = 0; i < G.nv; i++) pos[i * 3 + 2] += zs;
      const geo = new T.BufferGeometry();
      geo.setAttribute('position', new T.BufferAttribute(pos, 3)); geo.setAttribute('color', new T.BufferAttribute(col, 3));
      geo.setIndex(new T.BufferAttribute(ix, 1)); geo.computeVertexNormals();
      const mesh = new T.Mesh(geo, MAT.plastic); mesh.name = 'golova_setka'; mesh.castShadow = mesh.receiveShadow = true; lico.g.add(mesh);
      faceParts.eyeL.visible = faceParts.eyeR.visible = false;              // глаза — на козырьке листа
    }
    if (!KUB && !FORMA && !SETKA) {
      const strip = g => g.children.filter(o => o.isMesh && /_(plastic|metal)$/.test(o.name)).forEach(o => g.remove(o));
      strip(lico.g); parts.bokL.g.clear(); parts.bokR.g.clear(); parts.shapka.g.clear();
      const SHLEM = V7 ? { a: 4.7, b: 4.75, c: 4.45, yc: 4.85, zc: -0.2, p: 2.5, q: 2.5 }    // v7: почти шар (ш ≈ в ≈ г, лист 24.10)
        : { a: 5.0, b: 4.45, c: 4.3, yc: 4.3, zc: 0.25, p: 2.4, q: 4.2 };   // полуоси, центр, показатели плана/купола
      const MASKA = V7 ? { x: 3.5, z0: 1.4, y1: W(23.32) - 0.1 }                  // v7: плоская плита лица до верха очков, по бокам — борта шлема
        : { x: 4.4, z0: 1.6, y1: CAP_Y };                                // жёлтая маска: |x| ≤ 4.4, z ≥ 1.6, ниже шапки
      const sq3 = (v, e) => Math.pow(Math.abs(v), e);
      const shlem = (X, Y, Z) => Math.pow(sq3(X / SHLEM.a, SHLEM.p) + sq3((Z - SHLEM.zc) / SHLEM.c, SHLEM.p), SHLEM.q / SHLEM.p)
        + sq3((Y - SHLEM.yc) / SHLEM.b, SHLEM.q) <= 1;
      const maska = (X, Y, Z) => Math.abs(X) < MASKA.x && Z > MASKA.z0 && Z < FZ && Y > FACE_Y0 && Y < MASKA.y1
        && !(V7 && Y < FACE_Y0 + 2 * FACE_ROW && Math.abs(X) > MASKA.x - 0.8);       // v7: нижние углы лица скруглены
      const AY = FACE_Y0 + 17 * FACE_ROW;                                          // площадка пришельца
      const ploshchadka = (X, Y, Z) => !V7 && Math.abs(X) < 3.2 && Z > -1.6 && Z < 3.2 && Y < AY && Y > CAP_Y;
      const R6 = FACE_ROW, X0 = -6.4, Z0 = -6.4, NX = 16, NY = V7 ? 19 : 18, NZ = 16;
      const C = (i, j, k) => [X0 + (i + 0.5) * S, FACE_Y0 + (j + 0.5) * R6, Z0 + (k + 0.5) * S];
      const occ = new Map(), key = (i, j, k) => (i * 64 + j) * 64 + k;
      for (let i = 0; i < NX; i++) for (let j = 0; j < NY; j++) for (let k = 0; k < NZ; k++) {
        const [X, Y, Z] = C(i, j, k);
        if (maska(X, Y, Z)) occ.set(key(i, j, k), 'm');
        else if ((shlem(X, Y, Z) || ploshchadka(X, Y, Z)) && !(V7 && Math.abs(X) < 2.8 && Y > 7.25 && Z > 1.3)) occ.set(key(i, j, k), 's');   // v7: гнездо пришельца на лбу
      }
      const kind = (i, j, k) => occ.get(key(i, j, k));
      const DIRS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, 0, 1], [0, 0, -1]];
      const otkryto = (c, dz) => DIRS.filter(([a, b, d]) => !kind(c.x + a, c.y + b, c.z + d) && (dz || true));
      const skosy = (c, d0) => {                                                   // скос каждого открытого ребра
        const open = otkryto(c), pl = [];
        for (let p = 0; p < open.length; p++) for (let q = p + 1; q < open.length; q++) {
          const A2 = open[p], B = open[q];
          if (A2[0] + B[0] === 0 && A2[1] + B[1] === 0 && A2[2] + B[2] === 0) continue;
          pl.push(...CUT.edge(A2, B, d0));
        }
        return pl.length ? pl : null;
      };
      const nearMaska = (i, j, k) => [[1, 0, 0], [-1, 0, 0], [0, -1, 0], [0, 0, 1]].some(([a, b, d]) => kind(i + a, j + b, k + d) === 'm');
      /* шлем */
      const sh = part('shlem');
      new Vox({ cell: [S, R6, S], at: [X0, FACE_Y0, Z0], maxLen: 3, merge: 0.5, salt: 61, gap: 0.02, bev: 0.034,
        studs: (c, f) => f === 2 && C(c.x, c.y, c.z)[1] > CAP_Y && !ploshchadka(...C(c.x, c.y + 1, c.z)) && !ploshchadka(...C(c.x, c.y, c.z)),
        clip: c => skosy(c, 0.82) })
        .box(0, NX, 0, NY, 0, NZ, (i, j, k) => {
          if (kind(i, j, k) !== 's') return null;
          const [X, Y, Z] = C(i, j, k);
          if (j === 0) return 'dark';
          if (V7) {                                                               // v7: белый купол и затылок, серая полоса через уши
            if (nearMaska(i, j, k) && Y < MASKA.y1) return 'sideFront';
            return (Math.abs(X) > 3.2 && Y > 1.2 && Y < 7.4 && Z > -2.6 && Z < 2.4) ? 'side' : 'head';
          }
          if (nearMaska(i, j, k) && Y < CAP_Y) return 'sideFront';                // тёмная окантовка у маски, как на листе
          if (Z < -1.6) return Y > 7.6 ? 'head' : 'skull';
          return Y > 6.4 ? 'head' : 'side';
        })
        .del(v => !v.cls)
        .build(sh.bufs.plastic);
      if (V7) {                                                                    // v7: пришелец стоит на лбу, откинут назад
        const pr = subPart(sh.g, 'prishelec', [0, 8.85, 2.35], [1.05, 0, 0]);   // низ — на лбу над белой полосой, верх выше купола
        pr.g.scale.setScalar(0.66);
        alienOn(pr.bufs.plastic, -0.52);
        pr.finish();
      } else alienOn(sh.bufs.plastic, AY);
      /* крышка-диск на затылке: серый диск, светлое кольцо, тёмная втулка */
      const zB = SHLEM.zc - SHLEM.c + 0.12, yB = SHLEM.yc - 0.2;
      axial(sh.bufs.plastic, CYL, 0, yB, zB, [0, 0, -1], 1.75, 0.34, LIN.MG, true);
      axial(sh.bufs.plastic, CYL, 0, yB, zB - 0.3, [0, 0, -1], 1.15, 0.14, LIN.LG, true);
      socket(sh, 0, yB, zB - 0.42, [0, 0, -1], LIN.DG2, 0.42);
      [[-1, 1], [1, 1], [-1, -1], [1, -1]].forEach(([u, v]) => socket(sh, u * 2.35, yB + v * 1.5, zB + 0.32, [0, 0, -1], LIN.DG2, 0.26));
      sh.finish(); parts.shapka.g.add(sh.g);
      /* маска: жёлтые ряды, под линзой — длинные кирпичи; углы маски скруглены, подбородок вперёд */
      const ma = part('maska');
      new Vox({ cell: [S, R6, S], at: [X0, FACE_Y0, Z0], maxLen: 6, merge: 0.62, salt: 3, gap: 0.02, bev: 0.034,
        mergeFor: row => (row >= 6 && row <= 11 ? 0.93 : 0.62),
        clip: c => skosy(c, 0.75) })
        .box(0, NX, 0, NY, 0, NZ, (i, j, k) => (kind(i, j, k) === 'm' ? (j === 0 && k < 13 ? 'dark' : 'yellow') : null))
        .del(v => !v.cls)
        .build(ma.bufs.plastic);
      /* объёмные щёки и подбородок — подкова вокруг рта (как 22.10), под ширину маски 11 столбцов */
      const cheek = (x, y) => x >= 0 && x <= 10 && ((x <= 1 || x >= 9) ? y <= 5 : y <= 2);
      (V7 ? [] : [[FZ, 0.4, cheek], [FZ + 0.4, 0.2, (x, y) => (x <= 1 || x >= 9) && x >= 0 && x <= 10 && y >= 1 && y <= 4]].forEach(([z0, dz, fn], n) =>
        new Vox({ cell: [S, R6, dz], at: [-MASKA.x, FACE_Y0, z0], maxLen: 4, merge: 0.55, salt: 70 + n, gap: 0.02, bev: 0.03,
          clip: c => {
            const pl = [];
            if (!fn(c.x - 1, c.y)) pl.push(...CUT.edge([-1, 0, 0], [0, 0, 1], 0.75));
            if (!fn(c.x + 1, c.y)) pl.push(...CUT.edge([1, 0, 0], [0, 0, 1], 0.75));
            if (!fn(c.x, c.y + 1)) pl.push(...CUT.edge([0, 1, 0], [0, 0, 1], 0.75));
            if (c.y === 0) pl.push(...CUT.edge([0, -1, 0], [0, 0, 1], 0.6));
            return pl.length ? pl : null;
          } })
          .box(0, 11, 0, 6, 0, 1, (x, y) => (fn(x, y) ? 'yellow' : null)).del(v => !v.cls).build(ma.bufs.plastic)));
      new Vox({ cell: [S, 0.2, S], at: [-3.2, 0.0, -3.2], maxLen: 4, salt: 5 }).box(0, 8, 0, 1, 0, 8, 'dark').build(ma.bufs.plastic);  // низ к шее
      [[-3.2, -2.4], [3.2, -2.4], [-3.2, 2.4], [3.2, 2.4]].forEach(([x, z]) => magnet(ma, x, 0.02, z, [0, -1, 0]));
      ma.finish(); lico.g.add(ma.g);
      /* наушники: серый диск в оранжевой квадратной рамке, по нормали к шлему; круглые шипы над и под рамкой */
      [-1, 1].forEach(s => {
        const p = part(s < 0 ? 'naushnikL' : 'naushnikR'), P = p.bufs.plastic, d = [s, 0, 0];
        const ey = 4.55, ez = V7 ? -0.2 : 0.35;
        let ex = 0; while (shlem(ex + 0.05, ey, ez)) ex += 0.05;                   // поверхность шлема на высоте уха
        const RO = V7 ? 1.6 : 1.35, RI = V7 ? 1.02 : 0.86, TH = 0.5, x1 = s * (ex - 0.15 + TH / 2);
        const oc = tone('O2', 0.02), oc2 = tone('O1', 0.02);
        [[0, RO - (RO - RI) / 2, RO, (RO - RI) / 2, oc], [0, -(RO - (RO - RI) / 2), RO, (RO - RI) / 2, oc2],
         [RO - (RO - RI) / 2, 0, (RO - RI) / 2, RI, oc], [-(RO - (RO - RI) / 2), 0, (RO - RI) / 2, RI, oc2]].forEach(([dz, dy, hz, hy, col]) => {
          clipBlock(P, [x1, ey + dy, ez + dz], [TH / 2 - 0.012, hy - 0.012, hz - 0.012], [[s, Math.sign(dy), Math.sign(dz), 1.7]], col);
          P.occ.addBox(x1 - TH / 2, ey + dy - hy, ez + dz - hz, x1 + TH / 2, ey + dy + hy, ez + dz + hz);
          stats.slopes++;
        });
        axial(P, CYL, s * (ex - 0.2), ey, ez, d, RI - 0.04, TH + 0.12, LIN.LG2, true);           // диск
        axial(P, CYL, s * (ex + TH - 0.06), ey, ez, d, RI * 0.62, 0.1, LIN.MG, true);
        socket(p, s * (ex + TH + 0.02), ey, ez, d, LIN.DG2, 0.3);
        [ey + RO + 0.62, ey - RO - 0.62].forEach(y => [ez - 0.55, ez + 0.55].forEach(z => {
          let sx = 0; while (shlem(sx + 0.05, y, z)) sx += 0.05;
          socket(p, s * (sx + 0.02), y, z, d, LIN.LG, 0.3);
        }));
        magnet(p, s * (ex - 0.15), ey, ez, [-s, 0, 0]);
        p.finish(); parts[s < 0 ? 'bokL' : 'bokR'].g.add(p.g);
      });
    }

    /* ============================================================
       ВАРИАНТ ГОЛОВЫ «ЗВЕРЬ» (opts.lico = 'zver') — по листу Александра (зелёный громила, HEAD DETAIL).
       Не из деталей Плюса: органическая лепка — src/zver-golova.js (поле расстояний → гладкая сетка; зубы, глаза,
       шипы гребня — отдельная геометрия). Голова Плюса здесь убрана, тело то же; глаза моргают.
       ============================================================ */
    let zverBezShei = false;
    if (opts.lico === 'zver') {
      ['shapka', 'bokL', 'bokR', 'lico'].forEach(n => parts[n].g.clear());
      ochki.clear();
      const ZV = global.PixelMechZver;                                       // src/zver-golova.js — органическая лепка
      if (!ZV) throw new Error('lico=zver: подключи src/zver-golova.js перед pixel-mech.js');
      const zg = ZV.build(T, Object.assign({}, opts, { lite: !!opts.lite, bezShipov: !!opts.bezShipov, lepka: !!opts.lepka }));
      lico.g.add(zg.group);
      zverBezShei = !!zg.bezShei;
      Object.assign(faceParts, zg.glaza);                                   // глаза моргают (масштаб по y)
      /* мимика обычного лица — пустышки (рта-улыбки у зверя нет) */
      ['mouthBar', 'mouthCornerL', 'mouthCornerR', 'mouthLegL', 'mouthLegR', 'teeth'].forEach(n => {
        const o = new T.Object3D(); o.name = n; o.visible = false; o.userData.home = o.position.clone(); faceParts[n] = o;
      });
    }

    /* ============================================================
       ШЕЯ-ШТЫРЬ: воронёный ПВХ, воротник с винтами; входит в гнездо на верху груди
       ============================================================ */
    const sheya = part('sheya', [0, 16.3 + LIFT, 0]);
    {
      const pb = sheya.bufs.plastic, mb = sheya.bufs.metal, gm = new T.Color('#0f1012').convertSRGBToLinear(), dk = LIN.BK;
      axial(pb, CYL_FINE, 0, -1.62, 0.1, [0, 1, 0], 0.86, 0.22, gm, true);    // нижний поясок
      axial(pb, CYL_FINE, 0, -1.45, 0.1, [0, 1, 0], 1.04, 1.8, gm, true);     // тело штыря
      axial(pb, CYL_FINE, 0, -0.5, 0.1, [0, 1, 0], 1.08, 0.07, dk, true);     // канавка
      axial(pb, CYL_FINE, 0, 0.26, 0.1, [0, 1, 0], 1.47, 0.44, gm, true);     // воротник под головой
      axial(pb, CYL_FINE, 0, 0.2, 0.1, [0, 1, 0], 1.32, 0.07, dk, true);
      [-0.9, 0, 0.9, 2.3, -2.3].forEach(a => {                                // винты с шестигранником
        const nx = Math.sin(a), nz = Math.cos(a);
        axial(mb, CYL, nx * 1.43, 0.48, 0.1 + nz * 1.43, [nx, 0, nz], 0.16, 0.07, gm.clone().multiplyScalar(1.8));
        axial(mb, HEX, nx * 1.46, 0.48, 0.1 + nz * 1.46, [nx, 0, nz], 0.075, 0.05, dk.clone().multiplyScalar(0.3));
      });
    }
    sheya.finish(); root.add(sheya.g); parts.sheya = sheya;
    if (zverBezShei) sheya.g.visible = false;                              // у «зверя-2» шея — широкая, из самой головы

    /* ============================================================
       КОРПУС v5 (лист 2, «атлетичный»): грудь ±4.75 × 15.1…19.5 × ±3.0 — белая кладка
       со скошенными передними рёбрами, наклонными «шевронами» и швом, тёмные верхние углы;
       серый пояс 14.0…15.1, тёмный 13.2…14.0; живот сужается до ±3.35 (12.0…13.2).
       v6: по бокам груди — плечевые корпуса с шаровыми шарнирами на оси 18.2.
       ============================================================ */
    const korpus = part('korpus');
    {
      const P = korpus.bufs.plastic;
      /* грудь: 10 × 5 × 6 клеток 0.95 × 0.88 × 1.0 */
      const GX = [0.95, 0.88, 1.0], GA = [-4.75, 15.1, -3.0];
      const edge = c => Math.min(c.x, 9 - c.x);                // 0 — крайний столбец
      new Vox({
        cell: GX, at: GA, maxLen: 3, merge: 0.4, salt: 35, bev: 0.045,
        studTone: c => (c.cls === 'DG' ? 'DG2' : null),
        studs: (c, f) => {
          if (f === 4) return (c.y === 3 && edge(c) >= 1 && edge(c) <= 2) || (c.y === 1 && edge(c) === 1) ? 0.3 : false;
          if (f === 2) return c.cls === 'DG' ? (c.z === 3 || c.z === 4 ? 0.3 : false) : (c.z >= 1 && c.z <= 4 && (edge(c) === 1 || edge(c) === 3) ? 0.28 : false);
          if (f === 5) return (c.y === 1 || c.y === 3) && edge(c) >= 1 && (c.x & 1) === 0 ? 0.3 : false;
          return false;
        },
        clip: c => {                                     // плечи груди: верхние углы уходят внутрь (как на листе)
          const sx = c.x === 0 ? -1 : (c.x === 9 ? 1 : 0), s1 = c.x === 1 ? -1 : (c.x === 8 ? 1 : 0);
          if (s1 && c.y === 4) return c.z === 5 ? CUT.corner(s1, 1, 1, 1.3) : CUT.edge([s1, 0, 0], [0, 1, 0], 1.0);
          if (sx && c.z === 5) return CUT.edge([sx, 0, 0], [0, 0, 1], 0.62);
          if (c.y === 4 && c.z === 5) return CUT.edge([0, 1, 0], [0, 0, 1], 1.25);
          if (sx && c.z === 0) return CUT.edge([sx, 0, 0], [0, 0, -1], 1.0);
          return null;
        }
      }).box(0, 10, 0, 5, 0, 6, (x, y, z) => {
        if (z === 5 && x >= 3 && x <= 6) return 'WH';
        if (z === 0) return 'headBack';
        return 'chest';
      }).del(c => c.y >= 3 && (c.x === 0 || c.x === 9)).build(P);
      /* плечевые корпуса в угловых выемках груди: тёмный блок с сизыми шипами сверху,
         на его боку — гнездо шарового шарнира руки */
      [-1, 1].forEach(sx => new Vox({ cell: [0.475, 0.58, 0.667], at: [sx < 0 ? -4.75 : 3.8, 17.74, -1.0], maxLen: 1, salt: 30 + sx,
        studTone: () => 'DG2', studs: (c, f) => (f === 2 && c.z === 1 && (sx < 0 ? c.x === 0 : c.x === 1)) ? 0.26 : false,
        clip: c => (c.y === 1 && (sx < 0 ? c.x === 0 : c.x === 1) && c.z !== 1 ? CUT.corner(sx, 1, c.z ? 1 : -1, 1.5) : null)
      }).box(0, 2, 0, 2, 0, 3, 'DG').build(P));
      /* шипы на скошенных рёбрах груди (рядом с краем, как на листе: ±4.3) */
      [-1, 1].forEach(sx => [1].forEach(yi => {
        const hx = GX[0] / 2 - 0.011, hz = GX[2] / 2 - 0.011, u = 0.31;
        const cx = sx * (4.75 - GX[0] / 2), cy = GA[1] + (yi + 0.5) * GX[1], cz = GA[2] + 5.5 * GX[2];
        studOn(P, cx + sx * u * hx, cy, cz + u * hz, [sx / hx, 0, 1 / hz], 0.3, tone('WH'));
      }));
      /* «шевроны»: наклонные светло-серые пластины от края к середине, на каждой два шипа */
      [-1, 1].forEach(s => {
        const x0 = s * 4.1, y0 = 17.85, x1 = s * 2.0, y1 = 16.05;
        const L = Math.hypot(x1 - x0, y1 - y0), ang = Math.atan2(y1 - y0, x1 - x0);
        const q = new T.Quaternion().setFromAxisAngle(new T.Vector3(0, 0, 1), ang);
        const mm = new T.Matrix4().compose(new T.Vector3((x0 + x1) / 2, (y0 + y1) / 2, 3.07), q, new T.Vector3(1, 1, 1));
        bevelBox(P, 0, 0, 0, L / 2, 0.3, 0.09, 0.05, tone('LG', 0.02), 63 - 32, mm);
        P.occ.addBox(Math.min(x0, x1), y1 - 0.3, 2.98, Math.max(x0, x1), y0 + 0.3, 3.16);
        [0.32, 0.8].forEach(t => studOn(P, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 3.16, [0, 0, 1], 0.27, tone('LG', 0.02)));
      });
      bevelBox(P, 0, 17.02, 3.0, 2.15, 0.045, 0.02, 0.012, LIN.BK.clone(), 63 - 32, null, true);    // шов поперёк груди
      /* серый пояс 14.0…15.1: тёмные шипы по краям */
      new Vox({ cell: [0.92, 1.1, 0.725], at: [-4.6, 14.0, -2.9], maxLen: 3, merge: 0.45, salt: 33,
        studTone: () => 'DG',
        studs: (c, f) => (f === 4 && (c.x === 0 || c.x === 9)) ? 0.3 : (f === 5 && (c.x & 1) ? 0.3 : false)
      }).box(0, 10, 0, 1, 0, 8, 'band').build(P);
      /* тёмный пояс 13.2…14.0: светлые шипы-точки по краям */
      new Vox({ cell: [0.86, 0.8, 0.7], at: [-4.3, 13.2, -2.8], maxLen: 3, merge: 0.45, salt: 34,
        studTone: () => 'LG2',
        studs: (c, f) => (f === 4 && (c.x === 0 || c.x === 9)) ? 0.19 : false
      }).box(0, 10, 0, 1, 0, 8, 'darkBand').build(P);
      /* живот 12.0…13.2: ±3.35 вверху, сужается к тазу (±2.68 внизу) — туловище не ложится на бёдра */
      new Vox({ cell: [0.67, 0.4, 0.8], at: [-3.35, 12.0, -2.4], maxLen: 3, merge: 0.4, salt: 38,
        clip: c => {
          const sx = c.x < 5 ? -1 : 1;
          if (c.y === 1 && (c.x === 0 || c.x === 9)) return CUT.edge([sx, 0, 0], [0, -1, 0], 0.7);
          if (c.y === 0 && (c.x === 1 || c.x === 8)) return CUT.edge([sx, 0, 0], [0, -1, 0], 0.8);
          return null;
        }
      }).box(0, 10, 0, 3, 0, 6, x => (x >= 3 && x <= 6 ? 'abdMid' : 'abdSide'))
        .del(c => c.y === 0 && (c.x === 0 || c.x === 9)).build(P);
      /* гнездо шеи наверху груди */
      new Vox({ cell: [S, 0.3, S], at: [-1.6, 19.5, -1.5], maxLen: 4, salt: 39,
        studs: (c, f) => f === 2 && (c.x === 0 || c.x === 3) && (c.z === 0 || c.z === 3) })
        .box(0, 4, 0, 1, 0, 4, 'dark').build(P);
      [-1, 1].forEach(s => {
        balljoint(korpus, s * 4.76, 18.2, 0, [s, 0, 0], 0.46);                                       // плечевой шарнир
        magnet(korpus, s * 4.76, 17.2, -1.0, [s, 0, 0]);
        magnet(korpus, s * 1.2, 12.0, 0, [0, -1, 0]);
      });
    }
    korpus.finish(); root.add(korpus.g); parts.korpus = korpus;

    /* ============================================================
       ТАЗ (отдельный модуль, как в разлёте листа 2): блок ±2.0 (10.2…12.0),
       промежность ±1.0 до 9.4; v6: по бокам шаровые шарниры бёдер на оси HIP_Y — верх бедра
       входит в них, а не лежит под тазом
       ============================================================ */
    const HIP_Y = 10.9;                                        // ось тазобедренных шарниров
    const taz = part('taz');
    {
      const P = taz.bufs.plastic;
      new Vox({ cell: [0.8, 0.6, 0.8], at: [-2.0, 10.2, -2.0], maxLen: 3, merge: 0.5, salt: 45,
        studTone: () => 'DG2',
        studs: (c, f) => (f === 4 && c.y === 1 && (c.x === 0 || c.x === 4)) ? 0.26 : false,
        clip: c => (c.y === 0 && (c.x === 0 || c.x === 4) && (c.z === 0 || c.z === 4) ? CUT.corner(c.x ? 1 : -1, -1, c.z ? 1 : -1, 1.4) : null)
      }).box(0, 5, 0, 3, 0, 5, (x, y, z) => (z === 4 && x === 2 ? (y <= 1 ? 'crotch' : 'abdMid') : 'pelvis')).build(P);
      new Vox({ cell: [0.667, 0.4, 0.8], at: [-1.0, 9.4, -1.2], maxLen: 3, salt: 46,
        clip: c => {
          if (c.y !== 0) return null;
          if (c.x === 0 || c.x === 2) return CUT.edge([c.x ? 1 : -1, 0, 0], [0, -1, 0], 0.9);
          if (c.z === 2) return CUT.edge([0, 0, 1], [0, -1, 0], 1.0);
          return null;
        }
      }).box(0, 3, 0, 2, 0, 3, 'crotch').build(P);
      [-1, 1].forEach(s => {
        if (opts.nogi === 'utka') {
          /* «утка»: таз шире за счёт шарниров — по бокам лежат поперечные сервоприводы тазобедренного сустава,
             фланец смотрит вперёд (как у уточки); бедро висит прямо под их концом */
          const hx = +(opts.tazX || 3.1);                                // = LEG.hipX у «утки»
          bevelBox(P, s * (2.0 + (hx - 1.45) / 2), 10.75, 0, (hx - 1.45) / 2 + 0.25, 0.55, 0.85, 0.12, tone('DG2', 0.03), 63);   // сервопривод вбок
          const fz = 0.86;
          axial(taz.bufs.metal, CYL_FINE, s * hx, 10.75, fz, [0, 0, 1], 0.5, 0.1, LIN.SV);                // фланец вперёд
          axial(taz.bufs.metal, CYL_FINE, s * hx, 10.75, fz + 0.08, [0, 0, 1], 0.22, 0.06, LIN.GM);
          [0, 1, 2, 3].forEach(i => { const a = i * Math.PI / 2 + 0.4;
            axial(taz.bufs.metal, CYL, s * hx + Math.cos(a) * 0.36, 10.75 + Math.sin(a) * 0.36, fz + 0.08, [0, 0, 1], 0.055, 0.04, LIN.GM); });
          magnet(taz, s * hx, 10.2, 0, [0, -1, 0]);
          return;
        }
        balljoint(taz, s * 2.01, HIP_Y, 0, [s, 0, 0], 0.44);                                         // тазобедренный шарнир
        magnet(taz, s * 2.01, HIP_Y + 0.7, 1.2, [s, 0, 0]);
      });
      magnet(taz, 0, 12.0, 0.8, [0, 1, 0]);
    }
    taz.finish(); root.add(taz.g); parts.taz = taz;

    /* ============================================================
       РУКИ v5: наплечник (тёмная внутренняя половина с крупными шипами, белая наружная,
       оранжевая кромка сзади), тёмный локоть, наруч 4.6 × 4.4 × 4.0 со столбцами серых
       шипов спереди, оранжевой вставкой и тёмным узлом снаружи; кулак — три тёмных пальца.
       Узел плеча (±5.3, 18.2); наруч — подгруппа у локтя (поза).
       ============================================================ */
    const ARM = Object.assign({ swing: 0.0, pad: 0.15, elbow: 0.0, fwd: 0.08 }, opts.armPose || {});
    function arm(s) {
      const name = s < 0 ? 'rukaL' : 'rukaR';
      const p = part(name, [s * 5.3, 18.2, 0]);
      const P = p.bufs.plastic, out = s < 0 ? 1 : 0;
      /* наплечник: 6 × 5 × 4 клеток 0.667 × 0.7 × 0.95 (x 5.2…9.2, y 16.1…19.6), наружу чуть опущен (ARM.pad) */
      const PX = 4.0 / 6, oxP = c => (s < 0 ? 5 - c.x : c.x);
      new Vox({
        cell: [PX, 0.7, 0.95], at: [s < 0 ? -3.9 : -0.1, -2.1, -1.9], maxLen: 2, merge: 0.4, salt: 51 + s, bev: 0.05,
        studs: (c, f) => {
          const o = oxP(c);
          if (f === 4) return (o === 1 && (c.y === 2 || c.y === 3)) ? 0.4 : (o === 4 && (c.y === 1 || c.y === 3) ? 0.3 : false);
          if (f === out) return c.y >= 1 && c.y <= 3 && (c.z === 1 || c.z === 2) ? 0.3 : false;
          if (f === 2) return (o === 0 || o === 2) && (c.z === 1 || c.z === 2) ? 0.3 : (o === 4 && (c.z === 1 || c.z === 2) ? 0.28 : false);
          return false;
        },
        clip: c => {                                   // белая половина сверху скруглена ниже тёмной
          const o = oxP(c);
          if (o === 4 && c.y === 4) return CUT.edge([s, 0, 0], [0, 1, 0], 1.0);
          if (o === 5 && c.y === 3) return (c.z === 0 || c.z === 3) ? CUT.corner(s, 1, c.z ? 1 : -1, 1.5) : CUT.edge([s, 0, 0], [0, 1, 0], 1.0);
          if (o === 5 && c.y === 0) return CUT.edge([s, 0, 0], [0, -1, 0], 1.0);
          if (o === 4 && c.y === 3 && c.z === 3) return CUT.edge([0, 1, 0], [0, 0, 1], 1.15);
          return null;
        }
      }).box(0, 6, 0, 5, 0, 4, (x, y, z) => {
        const o = s < 0 ? 5 - x : x;
        if (o === 5 && z === 0 && y >= 1 && y <= 3) return 'orangePlate';
        if (y === 0 && o >= 3) return 'LG';
        return o <= 2 ? 'paulDark' : 'fist';
      }).del(c => c.y === 4 && oxP(c) === 5).build(P);
      /* ответное гнездо плечевого шарнира на внутренней грани наплечника */
      axial(P, CYL_FINE, s * -0.1, 0, 0, [-s, 0, 0], 0.66, 0.1, LIN.DG2.clone().multiplyScalar(0.7), true);
      axial(P, CYL_FINE, s * -0.19, 0, 0, [-s, 0, 0], 0.46, 0.03, LIN.BK, true);
      /* локоть: тёмный блок с осью под наплечником */
      new Vox({ cell: [0.6, 0.5, 0.7], at: [s < 0 ? -2.9 : 1.7, -2.6, -0.7], maxLen: 2, salt: 53 + s })
        .box(0, 2, 0, 2, 0, 2, 'knee').build(P);
      axial(p.bufs.metal, CYL, s * 1.65, -2.1, 0, [s, 0, 0], 0.2, 1.35, LIN.SV);
      /* наруч — подгруппа у локтя (наклон наплечника компенсирован — наруч висит прямо) */
      const naruch = subPart(p.g, name + '_naruch', [s * 2.3, -2.1, 0], [-ARM.fwd, 0, s * (ARM.pad + ARM.elbow)]);
      const NP = naruch.bufs.plastic, oxG = c => (s < 0 ? 4 - c.x : c.x);
      const GW = 4.0, G0 = s < 0 ? -3.05 : -0.95, GY = -4.6;
      const E = c => (oxG(c) === 0 || oxG(c) === 4) + (c.y === 0 || c.y === 4) + (c.z === 0 || c.z === 3);
      new Vox({
        cell: [GW / 5, 0.88, 1.0], at: [G0, GY, -2.0], maxLen: 2, merge: 0.35, gap: 0.03, bev: 0.06, salt: 55 + s,
        bevFor: c => (E(c) >= 2 ? 0.14 : 0),
        clip: c => (E(c) === 3 ? CUT.corner(c.x ? 1 : -1, c.y ? 1 : -1, c.z ? 1 : -1, 1.9) : null),
        studs: (c, f) => {
          const o = oxG(c);
          if (f === 4) return (o === 1 || o === 2) && c.y >= 1 && c.y <= 3 ? 0.4 : (o === 4 && (c.y === 1 || c.y === 3) ? 0.3 : false);
          if (f === out) return (c.y === 1 || c.y === 4) && (c.z === 1 || c.z === 2) ? 0.3 : false;
          if (f === 5) return c.y >= 1 && c.y <= 3 && ((o + c.y) & 1) === 0 ? 0.3 : false;
          return false;
        }
      }).box(0, 5, 0, 5, 0, 4, (x, y, z) => {
        const o = s < 0 ? 4 - x : x;
        if (y === 4) return 'gauntTop';
        return o <= 2 ? 'gauntDark' : 'fist';
      }).build(NP);
      /* оранжевая накладка снаружи (выступает — видна и спереди полоской), на ней тёмная ступица */
      const ox0 = s < 0 ? G0 - 0.3 : G0 + GW;
      new Vox({ cell: [0.3, 0.62, 0.7], at: [ox0, GY + 1.64, -1.05], maxLen: 1, salt: 57 + s,
        clip: c => (c.z === 2 ? CUT.edge([s, 0, 0], [0, 0, 1], 1.0) : (c.z === 0 ? CUT.edge([s, 0, 0], [0, 0, -1], 1.2) : null))
      }).box(0, 1, 0, 2, 0, 3, 'orangeBright').build(NP);
      const hx = s * (s < 0 ? -G0 : G0 + GW) + s * 0.312;
      axial(naruch.bufs.metal, CYL_FINE, hx, GY + 3 * 0.88, 0, [s, 0, 0], 0.66, 0.16, LIN.DG2);
      axial(naruch.bufs.metal, CYL_FINE, hx + s * 0.16, GY + 3 * 0.88, 0, [s, 0, 0], 0.42, 0.14, LIN.GM);
      axial(naruch.bufs.metal, HEX, hx + s * 0.3, GY + 3 * 0.88, 0, [s, 0, 0], 0.16, 0.04, LIN.BK);
      /* кулак: три пальца и большой палец изнутри (y 9.9…10.9 в мире) */
      const cxg = G0 + GW / 2, fcol = tone('FG');
      [-0.7, 0, 0.7].forEach((dx, i) => {
        const x = cxg + dx, c = fcol.clone().multiplyScalar(0.94 + 0.06 * i);
        bevelBox(NP, x, GY - 0.65, 0.25, 0.32, 0.65, 0.95, 0.12, c, 63 - 4);
        NP.occ.addBox(x - 0.32, GY - 1.3, -0.7, x + 0.32, GY, 1.2);
      });
      bevelBox(NP, cxg - s * 1.25, GY - 0.35, 0.9, 0.26, 0.4, 0.42, 0.1, fcol, 63 - 4);
      axial(naruch.bufs.metal, CYL, cxg - s * 0.6, GY + 4.4, 0, [0, 1, 0], 0.4, 0.3, LIN.GM);   // штифт к локтю
      p.naruch = naruch.finish();
      p.g.rotation.z = s * (ARM.swing - ARM.pad);
      p.finish(); root.add(p.g); parts[name] = p;
    }
    arm(-1); arm(1);

    /* ============================================================
       НОГИ v5: бедро 3.2 × 4.2 × 3.0 (светлый верх, 2 шипа вперёд, оранжевая пластина
       снаружи, тёмный узел с болтом внутрь), тёмное колено с осью и оранжевым кольцом,
       голень — подгруппа у колена, отведена наружу (ступни шире бёдер, как на листе 2)
       ============================================================ */
    const LEG = Object.assign(opts.nogi === 'utka' ? { hipX: +(opts.tazX || 3.1), shin: 0, toeOut: 0 } : { hipX: 4.0, shin: 0.08, toeOut: 0.25 }, opts.legPose || {});   // «утка»: ноги на боковых шарнирах таза
    const KNEE_Y = -3.9;
    const ANKLE = 2.9;                                         // ось голеностопа ниже колена
    const ankle = s => [s * (LEG.hipX + Math.sin(LEG.shin) * ANKLE), HIP_Y + KNEE_Y - Math.cos(LEG.shin) * ANKLE];
    function leg(s) {
      const name = s < 0 ? 'nogaL' : 'nogaR';
      const p = part(name, [s * LEG.hipX, HIP_Y, 0]);
      const P = p.bufs.plastic;
      const ox = c => (s < 0 ? 3 - c.x : c.x);
      /* бедро 3.2 × 3.4 × 3.0 (7.9…11.3): висит на тазобедренном шарнире, с тазом — зазор под ось;
         наружный верхний угол скруглён */
      new Vox({ cell: [0.8, 3.4 / 6, 0.75], at: [-1.6, -3.0, -1.5], maxLen: 2, merge: 0.4, salt: 61 + s,
        studTone: c => (c.y === 5 ? 'DG2' : null),
        studs: (c, f) => {
          if (f === 4) return c.y === 4 && (c.x === 1 || c.x === 2) ? 0.32 : false;
          if (f === 2) return (c.z === 1 || c.z === 2) && (ox(c) === 1 || ox(c) === 2) ? 0.3 : false;
          if (f === 5) return c.y === 3 && (c.x === 1 || c.x === 2) ? 0.3 : false;
          return false;
        },
        clip: c => {
          if (c.y === 5 && ox(c) === 2) return c.z === 3 ? CUT.corner(s, 1, 1, 1.2) : CUT.edge([s, 0, 0], [0, 1, 0], 1.0);
          if (c.y === 4 && ox(c) === 3) return c.z === 3 ? CUT.corner(s, 1, 1, 1.2) : CUT.edge([s, 0, 0], [0, 1, 0], 1.0);
          if (c.y === 5 && c.z === 3) return CUT.edge([0, 1, 0], [0, 0, 1], 1.1);
          if (c.y === 0 && (c.z === 0 || c.z === 3) && (c.x === 0 || c.x === 3)) return CUT.corner(c.x ? 1 : -1, -1, c.z ? 1 : -1, 1.5);
          return null;
        }
      }).box(0, 4, 0, 6, 0, 4, (x, y) => (y === 5 ? 'thighTop' : 'thigh')).del(c => c.y === 5 && ox(c) === 3).build(P);
      /* оранжевая пластина на внешней стороне (8.8…10.5) */
      new Vox({ cell: [0.34, 0.56, 0.75], at: [s < 0 ? -1.94 : 1.6, -2.1, -1.125], maxLen: 1, salt: 63 + s,
        clip: c => (c.z === 2 ? CUT.edge([s, 0, 0], [0, 0, 1], 0.9) : (c.y === 2 ? CUT.edge([s, 0, 0], [0, 1, 0], 1.0) : null))
      }).box(0, 1, 0, 3, 0, 3, 'orangeBright').build(P);
      /* узел изнутри: тёмная ступица и крупный болт-ось */
      axial(P, CYL_FINE, -s * 1.6, -2.0, 0.2, [-s, 0, 0], 0.62, 0.22, LIN.DG2.clone().multiplyScalar(0.8), true);
      bolt(p, -s * 1.82, -2.0, 0.2, [-s, 0, 0], 1.25);
      /* ответное гнездо тазобедренного шарнира на внутренней грани */
      axial(P, CYL_FINE, -s * 1.6, 0, 0, [-s, 0, 0], 0.72, 0.12, LIN.DG2.clone().multiplyScalar(0.7), true);
      axial(P, CYL_FINE, -s * 1.7, 0, 0, [-s, 0, 0], 0.5, 0.04, LIN.BK, true);
      /* наколенник 7.3…8.0 (серый) и тёмный шарнир 6.7…7.3 с осью, снаружи оранжевое кольцо с болтом */
      new Vox({ cell: [2.3 / 3, 0.3, 0.733], at: [-1.15, KNEE_Y + 0.3, -1.1], maxLen: 3, salt: 66 + s,
        clip: c => (c.y === 1 && c.z === 2 ? CUT.edge([0, 1, 0], [0, 0, 1], 1.1) : null)
      }).box(0, 3, 0, 2, 0, 3, 'shin').build(P);
      new Vox({ cell: [0.66, 0.3, 0.66], at: [-0.99, KNEE_Y - 0.3, -0.99], maxLen: 3, salt: 64 + s })
        .box(0, 3, 0, 2, 0, 3, 'knee').build(P);
      axial(p.bufs.metal, CYL, -s * 1.25, KNEE_Y, 0, [s, 0, 0], 0.18, 2.5, LIN.SV);
      socket(p, s * 0.991, KNEE_Y, 0, [s, 0, 0], LIN.O2, 0.52);
      bolt(p, s * 1.13, KNEE_Y, 0, [s, 0, 0], 0.85);
      /* голень: подгруппа у колена, мелкая серая кладка 2.6 × 4.4 × 2.6, тёмный верх */
      const golen = subPart(p.g, name + '_golen', [0, KNEE_Y, 0], [0, 0, s * LEG.shin]);
      new Vox({ cell: [0.7, 0.4, 0.7], at: [-1.4, -4.8, -1.4], maxLen: 3, merge: 0.35, salt: 65 + s,
        clip: c => (c.y === 10 && (c.x === 0 || c.x === 3) && (c.z === 0 || c.z === 3) ? CUT.corner(c.x ? 1 : -1, 1, c.z ? 1 : -1, 1.5) : null)
      }).box(0, 4, 0, 11, 0, 4, (x, y) => (y === 10 ? 'dark' : 'shin')).build(golen.bufs.plastic);
      axial(golen.bufs.metal, CYL, -1.55, -ANKLE, 0, [1, 0, 0], 0.17, 3.1, LIN.SV);          // ось голеностопа
      [-0.8, 0.8].forEach(z => magnet(golen, 0, -4.8, z, [0, -1, 0]));
      p.golen = golen.finish();
      [-0.8, 0.8].forEach(z => magnet(p, 0, 0.4, z, [0, 1, 0]));
      p.finish(); root.add(p.g); parts[name] = p;
    }
    /* ============================================================
       НОГИ «УТКА» (opts.nogi = 'utka', проба по роботу-уточке): три сустава, как у неё — тазобедренный (поперечный
       сервопривод в тазу), колено (согнуто назад, как у птицы) и щиколотка (сервопривод сидит прямо на стопе).
       Корпуса чёрные, фланцы серебряные; снаружи на бедре — замшевая крышка цвета «Пикколо». Стопа — низкая плашка
       (stopaUtka). Носков нет.
       ============================================================ */
    const UTKA_NOGI = opts.nogi === 'utka';
    const KNEE_FWD = -(+(opts.koleno || 1.3));                     // колено согнуто назад
    const TAPOK = UTKA_NOGI && global.PixelMechTapok && opts.stopa !== 'plashka' ? global.PixelMechTapok : null;   // стопа в тапочке «Пикколо»
    const ANK_Y = TAPOK ? 2.1 : 1.5;                                  // ось щиколотки: у ворота тапочки (или на плашке, верх 0.9)
    const PRISED = UTKA_NOGI ? +(opts.prisest || (TAPOK ? 1.0 : 2.4)) : 0;          // ноги короче, как у уточки: всё выше таза опускается
    function legUtka(s) {
      const name = s < 0 ? 'nogaL' : 'nogaR';
      const HY = 10.75 - PRISED;                                      // ось тазобедренного сервопривода (таз опущен)
      const p = part(name, [s * LEG.hipX, HY, 0]);
      const A = [0, ANK_Y + 0.6 - HY, 0];                             // верх сервопривода щиколотки
      const T0 = [0, -0.6, 0], K = [0, (T0[1] + A[1]) / 2 + 0.3, KNEE_FWD];   // колено — посредине, назад
      const tv = [K[0] - T0[0], K[1] - T0[1], K[2] - T0[2]], thL = Math.hypot(...tv), thA = Math.atan2(-tv[2], -tv[1]);
      const sv = [A[0] - K[0], A[1] - K[1], A[2] - K[2]], shL = Math.hypot(...sv), shA = Math.atan2(-sv[2], -sv[1]);
      const blk = () => tone('DG2', 0.04);
      const rog = (pp, x, y, z, r) => {                               // фланец сервопривода: диск, ступица, винты
        axial(pp.bufs.metal, CYL_FINE, x, y, z, [s, 0, 0], r, 0.12, LIN.SV);
        axial(pp.bufs.metal, CYL_FINE, x + s * 0.1, y, z, [s, 0, 0], r * 0.45, 0.06, LIN.GM);
        [0, 1, 2, 3].forEach(i => { const a = i * Math.PI / 2 + 0.4;
          axial(pp.bufs.metal, CYL, x + s * 0.1, y + Math.cos(a) * r * 0.72, z + Math.sin(a) * r * 0.72, [s, 0, 0], 0.07, 0.05, LIN.GM); });
      };
      /* бедро: корпус от таза к колену; снаружи — скруглённая крышка (по листу ног — белая, у плашки — замша) */
      const bed = subPart(p.g, name + '_bedro', T0, [thA, 0, 0]);
      bevelBox(bed.bufs.plastic, 0, -thL / 2, 0, 0.95, thL / 2 + 0.1, 0.92, 0.12, blk(), 63);
      bevelBox(bed.bufs.plastic, s * 1.07, -thL / 2 + 0.25, 0.05, 0.14, thL / 2 - 0.1, 1.12, TAPOK ? 0.13 : 0.12, tone(TAPOK ? 'WH2' : 'SU1', 0.02), 63);
      if (TAPOK) [[-0.45, 0.5], [0.5, -0.45]].forEach(([y, z]) => axial(bed.bufs.metal, CYL, s * 1.22, -thL / 2 + y, z, [s, 0, 0], 0.09, 0.05, LIN.GM));   // винты крышки
      bed.finish();
      /* колено: сервопривод с фланцем; голень — корпус до сервопривода щиколотки */
      const golen = subPart(p.g, name + '_golen', K, [shA, 0, 0]);
      bevelBox(golen.bufs.plastic, 0, 0, 0, 1.15, 1.05, 1.2, 0.14, blk(), 63);
      rog(golen, s * 1.17, 0, 0, 0.9);
      if (TAPOK) {                                                    // голень: сверху серый корпус, снизу жёлтый кронштейн до щиколотки
        bevelBox(golen.bufs.plastic, 0, -shL * 0.3 - 0.1, 0, 0.92, shL * 0.3 - 0.1, 0.88, 0.12, blk(), 63);
        bevelBox(golen.bufs.plastic, 0, -shL * 0.72, 0, 0.98, shL * 0.28 + 0.05, 0.8, 0.12, tone('YE', 0.02), 63);
        [-1, 1].forEach(sx => bevelBox(golen.bufs.plastic, sx * 0.86, -shL + 0.2, 0, 0.14, 0.5, 0.62, 0.1, tone('YE2', 0.02), 63));   // щёки вилки щиколотки
      } else bevelBox(golen.bufs.plastic, 0, -shL / 2 - 0.1, 0, 0.92, shL / 2 - 0.1, 0.88, 0.12, blk(), 63);
      [-0.6, 0.6].forEach(z => magnet(golen, 0, -shL, z, [0, -1, 0]));
      p.golen = golen.finish();
      [-0.8, 0.8].forEach(z => magnet(p, 0, -0.55, z, [0, 1, 0]));
      p.finish(); root.add(p.g); parts[name] = p;
    }
    /* ТАПОЧКА «ПИККОЛО» (лист ног 24.10): светло-мятная литая тапочка генератора 3D (src/plus-tapok.js) — подошва
       с волнистым протектором, сверху валики-складки, мягкий ворот. Стопа уточки (сервопривод щиколотки) — внутри,
       над воротом виден чёрный шарнир с фланцем. Проём ворота — под осью голени. */
    function tapokUtka(s, name, p, P) {
      const TG = TAPOK, bin = b64 => { const t = (typeof atob === 'function') ? atob(b64) : Buffer.from(b64, 'base64').toString('binary'); const u = new Uint8Array(t.length); for (let i = 0; i < t.length; i++) u[i] = t.charCodeAt(i); return u; };
      const pz = new Float32Array(bin(TG.pos).buffer), ix = new Uint32Array(bin(TG.idx).buffer), n = TG.nv;
      const KS = +(opts.tapokK || 0.92), ax = s * LEG.hipX;
      let ymax = 0; for (let i = 0; i < n; i++) ymax = Math.max(ymax, pz[i * 3 + 1]);
      let ox = 0, oz = 0, on = 0;                                     // проём ворота: верхние вершины
      for (let i = 0; i < n; i++) if (pz[i * 3 + 1] > ymax * 0.9) { ox += pz[i * 3]; oz += pz[i * 3 + 2]; on++; }
      ox /= on; oz /= on;
      const pos = new Float32Array(n * 3), col = new Float32Array(n * 3), c1 = tone('MN1', 0), c2 = tone('MN2', 0);
      for (let i = 0; i < n; i++) {
        const y = pz[i * 3 + 1] * KS;
        pos[i * 3] = ax + s * (pz[i * 3] - ox) * KS; pos[i * 3 + 1] = y; pos[i * 3 + 2] = (pz[i * 3 + 2] - oz) * KS + (+(opts.tapokZ || 0.5));
        const c = y < 0.45 * KS ? c2 : c1; col[i * 3] = c.r; col[i * 3 + 1] = c.g; col[i * 3 + 2] = c.b;
      }
      const idx = s > 0 ? ix : ix.slice().map((v, i) => (i % 3 === 1 ? ix[i + 1] : i % 3 === 2 ? ix[i - 1] : v));
      const geo = new T.BufferGeometry();
      geo.setAttribute('position', new T.BufferAttribute(pos, 3)); geo.setAttribute('color', new T.BufferAttribute(col, 3));
      geo.setIndex(new T.BufferAttribute(idx, 1)); geo.computeVertexNormals();
      const mat = new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.75, metalness: 0, envMapIntensity: 0.3 }); mat.name = 'tapok';
      const mesh = new T.Mesh(geo, mat); mesh.name = name + '_tapok'; mesh.castShadow = mesh.receiveShadow = true; p.g.add(mesh);
      /* стопа уточки спрятана в тапочке; из ворота торчит чёрный сервопривод щиколотки с фланцами */
      bevelBox(P, ax, ANK_Y, 0, 0.75, 0.55, 0.75, 0.12, tone('DG2', 0.04), 63);
      [-1, 1].forEach(sx => {
        axial(p.bufs.metal, CYL_FINE, ax + sx * 1.0, ANK_Y, 0, [sx, 0, 0], 0.5, 0.12, LIN.GM);
        axial(p.bufs.metal, CYL_FINE, ax + sx * 1.1, ANK_Y, 0, [sx, 0, 0], 0.26, 0.06, LIN.SV);
      });
      [-0.8, 0.8].forEach(z => magnet(p, ax, ANK_Y + 0.55, z * 0.5, [0, 1, 0]));
      p.finish(); root.add(p.g); parts[name] = p;
    }
    /* СТОПА «УТКА»: низкая плашка со скруглёнными углами, как у уточки, но в цветах «Пикколо» — неоновая подошва
       с круглым протектором, зелёный бампер под носком, сверху оранжевая замша, по боку фиолетовая полоска.
       На стопе сидит сервопривод щиколотки (фланец снаружи), сзади — замшевое ушко с отверстиями. */
    function stopaUtka(s, name, p, P) {
      if (TAPOK) return tapokUtka(s, name, p, P);
      const ax = s * LEG.hipX, L = +(opts.stopaL || 5.0), W = L * 0.6, ZH = -1.55, zc = ZH + L / 2;
      const cN = tone('NG1', 0);
      bevelBox(P, ax, 0.17, zc, W / 2 + 0.05, 0.17, L / 2 + 0.05, 0.14, cN, 63);                    // подошва
      bevelBox(P, ax, 0.24, zc + L / 2 - 0.12, W / 2 - 0.25, 0.22, 0.2, 0.14, tone('NG2', 0), 63);   // бампер под носком
      bevelBox(P, ax, 0.62, zc - 0.04, W / 2 - 0.04, 0.3, L / 2 - 0.08, 0.28, tone('SU1', 0.02), 63);  // замша
      bevelBox(P, ax + s * (W / 2 - 0.02), 0.6, zc + 0.2, 0.05, 0.08, L / 2 - 0.9, 0.04, tone('PU', 0), 63);   // полоска
      const lug = (x, z, d) => axial(p.bufs.plastic, CYL, x, 0.12, z, d, 0.15, 0.12, cN, true);
      for (let i = 0; i < 7; i++) { const z = ZH + 0.35 + i * (L - 0.7) / 6; [-1, 1].forEach(sx => lug(ax + sx * (W / 2 + 0.02), z, [sx, -0.4, 0])); }
      for (let i = -2; i <= 2; i++) lug(ax + i * W * 0.15, zc + L / 2 + 0.03, [0, -0.4, 1]);
      /* сервопривод щиколотки на стопе, фланец снаружи */
      bevelBox(P, ax, ANK_Y, 0, 1.0, 0.6, 1.1, 0.12, tone('DG2', 0.04), 63);
      axial(p.bufs.metal, CYL_FINE, ax + s * 1.02, ANK_Y, 0, [s, 0, 0], 0.5, 0.1, LIN.SV);
      axial(p.bufs.metal, CYL_FINE, ax + s * 1.1, ANK_Y, 0, [s, 0, 0], 0.22, 0.06, LIN.GM);
      /* ушко на пятке */
      bevelBox(P, ax, 1.45, ZH + 0.2, 0.75, 0.6, 0.15, 0.14, tone('SU2', 0.02), 63);
      [-0.35, 0.35].forEach(x => axial(p.bufs.metal, CYL, ax + x, 1.55, ZH + 0.02, [0, 0, -1], 0.11, 0.06, LIN.GM));
      [-0.8, 0.8].forEach(z => magnet(p, ax, 2.12, z * 0.6, [0, 1, 0]));
      p.finish(); root.add(p.g); parts[name] = p;
    }
    if (UTKA_NOGI) { legUtka(-1); legUtka(1); } else { leg(-1); leg(1); }

    /* ============================================================
       СТУПНИ v6 — компактные, форма и цвета как на листе 1: 6 × 10 шипов (4.8 × 8.0),
       основной объём до 2.38. Ярусы уступами: основание 0…0.9 (жёлто-оранжевые высокие кирпичи),
       средний 0.9…1.4 (жжёный оранжевый), верхний 1.4…2.38 (оранжевый, скруглённая передняя кромка,
       начинается позади носка), задник до 3.0. Манжет голеностопа обхватывает голень со всех сторон:
       боковые щёки, передняя и задняя стяжки; он собран в подгруппе с тем же наклоном, что голень,
       поэтому прилегает к ней вплотную. Две пары болтов — ось и заклёпка.
       ============================================================ */
    function foot(s) {
      const name = s < 0 ? 'stupnyaL' : 'stupnyaR';
      const p = part(name);
      const P = p.bufs.plastic;
      const NL = +(opts.footLen || 10), toe = NL - 1, NW = 6;
      const bx = s * (LEG.hipX + Math.sin(LEG.shin) * 4.4);        // голень у верха ступни
      const fx = bx + s * LEG.toeOut, X0 = fx - NW * S / 2, Z0 = -3.0;
      const plan = (c, inset) => {                                 // скругление носка и пятки в плане
        const sx = c.x === 0 ? -1 : (c.x === NW - 1 ? 1 : 0);
        if (!sx) return [];
        if (c.z === toe - inset) return [[sx, 0, 1, 1.0]];
        if (c.z === 0) return [[sx, 0, -1, 1.25]];
        return [];
      };
      const clipOf = (inset, extra) => c => { const pl = plan(c, inset).concat((extra && extra(c)) || []); return pl.length ? pl : null; };
      if (UTKA_NOGI) return stopaUtka(s, name, p, P);
      if (opts.obuv === 'piccolo' || opts.obuv === 'utka') return sapog(s, name, p, P, NL, NW, bx, X0, Z0, plan, clipOf);
      /* основание 0…0.9: два ряда высоких кирпичей, у носка снизу скос */
      new Vox({ cell: [S, 0.45, S], at: [X0, 0, Z0], maxLen: 3, merge: 0.5, salt: 71 + s,
        clip: clipOf(0, c => (c.y === 0 && c.z === toe ? CUT.edge([0, -1, 0], [0, 0, 1], 1.1) : (c.y === 0 && c.z === 0 ? CUT.edge([0, -1, 0], [0, 0, -1], 1.3) : null)))
      }).box(0, NW, 0, 2, 0, NL, 'footBase').build(P);
      /* средний ярус 0.9…1.4: плиты, носок скруглён сверху */
      new Vox({ cell: [S, 0.5, S], at: [X0, 0.9, Z0], maxLen: 4, merge: 0.55, salt: 72 + s,
        clip: clipOf(0, c => (c.z === toe ? CUT.edge([0, 1, 0], [0, 0, 1], 1.0) : null))
      }).box(0, NW, 0, 1, 0, NL, 'footMid').build(P);
      /* верхний ярус 1.4…2.38: от пятки до полутора шипов от носка, передняя кромка скруглена,
         проём под голень; спереди ряд шипов */
      const inShin = c => Math.abs(X0 + (c.x + 0.5) * S - bx) < 1.4 && Math.abs(Z0 + (c.z + 0.5) * S) < 1.4;
      const topEnd = toe - 1;                                        // последний ряд верхнего яруса
      new Vox({ cell: [S, 0.98, S], at: [X0, 1.4, Z0], maxLen: 1, salt: 74 + s,
        studs: (c, f) => (f === 2 && c.z === topEnd - 1 && c.x >= 1 && c.x <= NW - 2) ? 0.24 : false,
        clip: clipOf(1, c => {
          if (c.z === topEnd) return CUT.edge([0, 1, 0], [0, 0, 1], 1.15);
          if (c.z === 0) return CUT.edge([0, 1, 0], [0, 0, -1], 1.2);
          return null;
        })
      }).box(0, NW, 0, 1, 0, topEnd + 1, 'footTop').del(inShin).build(P);
      /* задник 2.38…3.0 за голенью: скруглённая пятка шириной с манжет */
      new Vox({ cell: [4.04 / 4, 0.62, S], at: [bx - 2.02, 2.38, Z0], maxLen: 4, merge: 0.8, salt: 75 + s,
        clip: c => [...CUT.edge([0, 1, 0], [0, 0, -1], 1.0), ...((c.x === 0 || c.x === 3) ? CUT.edge([c.x ? 1 : -1, 0, 0], [0, 1, 0], 1.2) : [])]
      }).box(0, 4, 0, 1, 0, 2, 'footTop').build(P);
      /* манжет голеностопа: подгруппа у колена с наклоном голени — координаты те же, что у голени
         (голень ±1.4, y −4.8…−0.4); щёки 0.6 по бокам, стяжки спереди и сзади */
      const cuff = subPart(p.g, name + '_manzhet', [s * LEG.hipX, HIP_Y + KNEE_Y, 0], [0, 0, s * LEG.shin]);
      const CP = cuff.bufs.plastic, CB = -4.8, CT = -2.1;               // низ (в ступне) и верх манжета
      [-1, 1].forEach(side => {
        new Vox({ cell: [0.6, (CT - CB) / 4, 0.6], at: [side < 0 ? -2.02 : 1.42, CB, -0.9], maxLen: 2, merge: 0.4, salt: 77 + s + side,
          studTone: () => 'DG2',
          studs: (c, f) => (f === 2 && c.z === 1 ? 0.22 : false),
          clip: c => (c.y === 3 && c.z !== 1 ? CUT.corner(side, 1, c.z ? 1 : -1, 1.4) : null)
        }).box(0, 1, 0, 4, 0, 3, 'bracket').build(CP);
        bolt(cuff, side * 2.032, -ANKLE, 0, [side, 0, 0], 1.2);                  // ось голеностопа
        bolt(cuff, side * 2.032, CB + 0.95, 0.1, [side, 0, 0], 1.0);             // заклёпка
      });
      [-1, 1].forEach(fz => {                                              // стяжки поперёк голени
        new Vox({ cell: [4.04 / 5, 0.55, 0.32], at: [-2.02, CT - 0.62, fz > 0 ? 1.42 : -1.74], maxLen: 5, merge: 0.9, salt: 79 + s + fz,
          clip: c => (c.x === 0 || c.x === 4) ? CUT.edge([c.x ? 1 : -1, 0, 0], [0, 0, fz], 0.8) : null
        }).box(0, 5, 0, 1, 0, 1, fz > 0 ? 'footMid' : 'bracket').build(CP);
      });
      [-0.8, 0.8].forEach(z => magnet(cuff, 0, CB + 0.02, z, [0, 1, 0]));
      p.manzhet = cuff.finish();
      p.finish(); root.add(p.g); parts[name] = p;
    }
    /* ============================================================
       САПОГ «ПИККОЛО» (opts.obuv = 'piccolo', проба по картинке Александра 24.10): мягкий замшевый сапожок
       оранжево-красного цвета на неоновой зелёной подошве с круглым протектором; на голени — толстый
       подвёрнутый ворот; по боку и на пятке — складки замши; снаружи — фиолетовая полоса (своя, не логотип).
       След тот же, что у ступни (6 × 10 шипов), ворот — в подгруппе голени, как манжет.
       ============================================================ */
    function sapog(s, name, p, P, NL, NW, bx, X0, Z0, plan, clipOf) {
      const SG = global.PixelMechSapog;
      if (SG && SG.pos && !opts.sapogKirpichi) {
        /* литой сапог: гладкая поверхность генератора в размерах Плюса, цвет по картинке (неон / замша) */
        const bin = b64 => { const t = (typeof atob === 'function') ? atob(b64) : Buffer.from(b64, 'base64').toString('binary'); const u = new Uint8Array(t.length); for (let i = 0; i < t.length; i++) u[i] = t.charCodeAt(i); return u; };
        const pz = new Float32Array(bin(SG.pos).buffer), ix = new Uint32Array(bin(SG.idx).buffer), kl = bin(SG.kls);
        /* посадка: проём ворота (верхние вершины) — на ось голени; размер — множитель obuvK (Плюс коренастый,
           сапог в размер прежней ступни смотрится мелким) */
        const UTKA = opts.obuv === 'utka';
        if (UTKA && opts.stopa === 'klin') {
          /* СТОПА «УТКА» (клин, opts.stopa = 'klin') (форма как у уточки MicroDuck, низ как у «Пикколо»): клин — высокая пятка под шарниром
             щиколотки, верх скатом к скруглённому носку; в плане — скруглённый прямоугольник ~0,55 длины.
             Низ: неоновый протектор с круглыми шишками по всей кромке (и под носком), над ним оранжевый рант
             чуть шире, выше — замша. Пятка — под осью голени; голень и шарнир закрывает белый носок. */
          const H = ankle(s)[1] - 0.8, L = +(opts.stopaL || H / 0.42), Wd = L * 0.55, ZB = -L * 0.24;
          const NZ = 56, NA = 64, P = 6, mb = new MeshBuf(true);
          const cN = tone('NG1', 0), cR = tone('SU4', 0), cU = tone('SU1', 0);
          const sm = (a, b, x) => { const t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
          const plan = t => Math.pow(Math.max(0, 1 - Math.pow(Math.abs(2 * t - 1), 5)), 1 / 5);       // скругление носка и пятки
          const verh = t => H * (1 - 0.62 * sm(0.3, 1.0, t)) * Math.pow(plan(t), 0.35);               // клин: пятка высокая, носок низкий
          const sg = (v, e) => Math.sign(v) * Math.pow(Math.abs(v), e);
          for (let k = 0; k <= NZ; k++) {
            const t = k / NZ, z = ZB + t * L, hw = Wd / 2 * plan(t), ht = verh(t);
            for (let a = 0; a <= NA; a++) {
              const th = a / NA * Math.PI * 2, cx_ = sg(Math.cos(th), 2 / P), cy_ = sg(Math.sin(th), 2 / P);
              let y = ht * (0.5 + 0.5 * cy_), x = hw * cx_;
              const rant = y > 0.36 && y < 0.86 ? 1.06 : 1;                                        // рант чуть шире
              x *= rant;
              const c = y < 0.36 ? cN : (y < 0.86 ? cR : cU);
              mb.vert(bx + x, Math.max(0, y), z, 0, 1, 0, c);
            }
          }
          for (let k = 0; k < NZ; k++) for (let a = 0; a < NA; a++) {
            const i0 = k * (NA + 1) + a, i1 = i0 + NA + 1;
            if (s > 0) { mb.tri(i0, i0 + 1, i1); mb.tri(i1, i0 + 1, i1 + 1); } else { mb.tri(i0, i1, i0 + 1); mb.tri(i1, i1 + 1, i0 + 1); }
          }
          const geo = mb.geometry(); geo.computeVertexNormals();
          const mat = new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.9, metalness: 0, envMapIntensity: 0.2, side: T.DoubleSide }); mat.name = 'stopa_utka';
          const mesh = new T.Mesh(geo, mat); mesh.name = name + '_stopa'; mesh.castShadow = mesh.receiveShadow = true; p.g.add(mesh);
          /* протектор: круглые неоновые шишки по кромке подошвы, и под носком */
          const lug = new MeshBuf(true);
          for (let k = 1; k < NZ; k += 2) {
            const t = k / NZ, z = ZB + t * L, hw = Wd / 2 * plan(t) * 0.97;
            [-1, 1].forEach(sx => axial(lug, CYL, bx + sx * hw, 0.13, z, [sx, -0.35, 0], 0.17, 0.14, cN, true));
          }
          for (let a = -3; a <= 3; a++) axial(lug, CYL, bx + a * Wd * 0.12, 0.13, ZB + L * 0.985, [0, -0.35, 1], 0.17, 0.14, cN, true);
          const lm = new T.Mesh(lug.geometry(), mat); lm.name = name + '_protektor'; p.g.add(lm);
        } else {
        const n = SG.nv, pos = new Float32Array(n * 3), col = new Float32Array(n * 3);
        let ymax = 0; for (let i = 0; i < n; i++) ymax = Math.max(ymax, pz[i * 3 + 1]);
        /* у «утки» — стопа «Пикколо» под шарниром щиколотки: длина и ширина как у сапога, высота ниже —
           ворот кончается под осью, над ним виден шарнир в носке */
        const KS = +(opts.obuvK || (UTKA ? 0.78 : 1.35)), KY = UTKA ? +(opts.obuvKY || (ankle(s)[1] - 1.3) / ymax) : KS;
        let ox = 0, oz = 0, on = 0;
        for (let i = 0; i < n; i++) if (pz[i * 3 + 1] > ymax * 0.88) { ox += pz[i * 3]; oz += pz[i * 3 + 2]; on++; }
        ox /= on; oz /= on;
        const cx = bx;
        const cS = [tone('SU1', 0), tone('SU2', 0)], cN = tone('NG1', 0);
        for (let i = 0; i < n; i++) {
          pos[i * 3] = cx + s * (pz[i * 3] - ox) * KS; pos[i * 3 + 1] = pz[i * 3 + 1] * KY; pos[i * 3 + 2] = (pz[i * 3 + 2] - oz) * KS + (UTKA ? +(opts.obuvZ || 0.95 * KS) : 0);   // в данных z растёт от пятки к носку
          const c = kl[i] ? cN : cS[0];
          col[i * 3] = c.r; col[i * 3 + 1] = c.g; col[i * 3 + 2] = c.b;
        }
        const idx = s > 0 ? ix : ix.slice().map((v, i) => (i % 3 === 1 ? ix[i + 1] : i % 3 === 2 ? ix[i - 1] : v));   // зеркало — обратный обход
        const geo = new T.BufferGeometry();
        geo.setAttribute('position', new T.BufferAttribute(pos, 3)); geo.setAttribute('color', new T.BufferAttribute(col, 3));
        geo.setIndex(new T.BufferAttribute(idx, 1)); geo.computeVertexNormals();
        const mat = new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.92, metalness: 0, envMapIntensity: 0.2 }); mat.name = 'sapog_zamsha';
        const mesh = new T.Mesh(geo, mat); mesh.name = name + '_sapog'; mesh.castShadow = mesh.receiveShadow = true; p.g.add(mesh);
        }
        const cuff = subPart(p.g, name + '_manzhet', [s * LEG.hipX, HIP_Y + KNEE_Y, 0], [0, 0, s * LEG.shin]);
        if (UTKA) {
          /* белый носок на резинке (как спортивный, без надписи): трубка вокруг голени и шарнира щиколотки,
             сечение — скруглённый квадрат (накрывает углы голени), продольный рубчик; сверху — резинка шире и мельче */
          const sock = new MeshBuf(true), NA = 96, wc = tone('WH2', 0), wc2 = tone('WH', 0);
          const kvad = (t, h) => h / Math.pow(Math.pow(Math.abs(Math.cos(t)), 4) + Math.pow(Math.abs(Math.sin(t)), 4), 1 / 4);
          const yb = -ANKLE - 2.1, yt = -ANKLE + 0.75, yr = yt - 0.55;          // низ — глубоко в вороте сапога, верх — сразу над шарниром
          const ng = null;   // у ног «утка» — по наклону голени
          const ring = (y, h, ribs, dep) => { const st = sock.v; for (let a = 0; a <= NA; a++) {
            const t = a / NA * Math.PI * 2, r = kvad(t, h) * (1 + dep * Math.cos(t * ribs)), nx = Math.cos(t), nz = Math.sin(t);
            let vy = y, vz = r * nz, wy = 0, wz = nz;
            if (UTKA_NOGI && y < -ANKLE) {                                     // ниже щиколотки носок встаёт отвесно — в ворот сапога
              const yy = y + ANKLE, k = Math.min(1, -yy / 0.9), ang = 0 * k * k * (3 - 2 * k), c = Math.cos(ang), sn = Math.sin(ang);
              vy = -ANKLE + yy * c - vz * sn; vz = yy * sn + vz * c; wy = -nz * sn; wz = nz * c;
            }
            sock.vert(r * nx, vy, vz, nx, wy, wz, (UTKA_NOGI ? y > -ANKLE + 1.1 : y > yr) ? wc : wc2); } return st; };
          const band = (y0, y1, h0, h1, ribs, dep, rows) => {
            let prev = ring(y0, h0, ribs, dep);
            for (let k = 1; k <= rows; k++) { const t = k / rows, cur = ring(y0 + (y1 - y0) * t, h0 + (h1 - h0) * t, ribs, dep);
              for (let a = 0; a < NA; a++) { sock.tri(prev + a, cur + a, prev + a + 1); sock.tri(cur + a, cur + a + 1, prev + a + 1); } prev = cur; }
          };
          if (UTKA_NOGI) {
            /* носок в обтяжку по голени (голень 0.98 × 0.92): от ворота сапога до резинки над щиколоткой;
               у оси щиколотки ткань чуть вздута — под ней шарнир, снаружи — его фланцы */
            const Y0 = -ANKLE - 2.5, Y1 = -ANKLE + 1.1, Y2 = -ANKLE + 1.45, NR = 22;
            const prof = y => 1.17 + 0.1 * Math.exp(-Math.pow((y + ANKLE) / 0.55, 2)) - 0.4 * Math.min(1, Math.max(0, -(y + ANKLE) / 1.0));   // к вороту сужается
            let prev = null;
            for (let k = 0; k <= NR; k++) { const y = Y0 + (Y1 - Y0) * k / NR, cur = ring(y, prof(y), 28, 0.018);
              if (prev !== null) for (let a = 0; a < NA; a++) { sock.tri(prev + a, cur + a, prev + a + 1); sock.tri(cur + a, cur + a + 1, prev + a + 1); } prev = cur; }
            band(Y1, Y2, 1.17, 1.23, 48, 0.03, 3);                              // резинка
            band(Y2, Y2 + 0.06, 1.23, 1.05, 48, 0.02, 1);                       // край резинки внутрь
          } else {
          band(yb, yr, 1.72, 1.72, 28, 0.022, 6);                              // стопа носка с рубчиком
          band(yr, yt, 1.8, 1.8, 48, 0.03, 4);                                 // резинка
          band(yt, yt + 0.06, 1.8, 1.5, 48, 0.02, 1);                          // край резинки внутрь
          }
          const sm = new T.Mesh(sock.geometry(), new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.95, metalness: 0 }));
          sm.name = name + '_nosok_tkan'; sm.castShadow = sm.receiveShadow = true; (ng ? ng.g : cuff.g).add(sm); if (ng) { sm.position.y = ANKLE; p.g.add(ng.g); }   // у щиколотки: ось носка — по голени
          if (UTKA_NOGI) {
            /* шарнир щиколотки, как у уточки: по бокам носка — серебряные фланцы на оси, от них в ворот сапога
               уходят тёмные щёки U-кронштейна стопы */
            const [ax, ay] = ankle(s), top = ay - 1.3;                     // верх ворота сапога
            [-1, 1].forEach(sx => {
              const xf = ax + sx * 1.3;
              axial(p.bufs.metal, CYL_FINE, xf - sx * 0.05, ay, 0, [sx, 0, 0], 0.62, 0.08, LIN.GM);
              axial(p.bufs.metal, CYL_FINE, xf + sx * 0.03, ay, 0, [sx, 0, 0], 0.5, 0.1, LIN.SV);
              axial(p.bufs.metal, CYL_FINE, xf + sx * 0.12, ay, 0, [sx, 0, 0], 0.2, 0.06, LIN.GM);
              [0, 1, 2, 3].forEach(i => { const a = i * Math.PI / 2 + 0.4;
                axial(p.bufs.metal, CYL, xf + sx * 0.12, ay + Math.cos(a) * 0.36, Math.sin(a) * 0.36, [sx, 0, 0], 0.055, 0.04, LIN.GM); });
              bevelBox(P, xf - sx * 0.02, (ay - 0.2 + top - 0.5) / 2, 0, 0.07, (ay - 0.2 - top + 0.5) / 2, 0.42, 0.05, tone('DG2', 0.03), 63);
            });
          }
        }
        [-0.8, 0.8].forEach(z => magnet(cuff, 0, -4.78, z, [0, 1, 0]));
        p.manzhet = cuff.finish();
        p.finish(); root.add(p.g); parts[name] = p;
        return;
      }
      if (SG) {
        /* по картинке: сетка генератора 3D → кирпичи полшипа (tools/forma/kirpichi.py → src/plus-sapog.js);
           пятка у z = Z0, носок вперёд; левый — зеркало правого */
        const at = (i, j, k) => (i < 0 || j < 0 || k < 0 || i >= SG.NX || j >= SG.NY || k >= SG.NZ) ? '.' : SG.cells[((SG.NZ - 1 - k) * SG.NY + j) * SG.NX + i];   // в данных носок у k = 0
        const xi = i => (s < 0 ? SG.NX - 1 - i : i);
        const cx = X0 + NW * S / 2;
        const DIRS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, 0, 1], [0, 0, -1], [0, -1, 0]];
        new Vox({ cell: [SG.CX, SG.CY, SG.CZ], at: [cx + SG.X0, 0, Z0], maxLen: 6, merge: 0.92, gap: 0.006, salt: 181 + s, bev: 0.06,
          clip: c => {
            const open = DIRS.filter(([a2, b2, d2]) => at(xi(c.x + (s < 0 ? -a2 : a2)), c.y + b2, c.z + d2) === '.'), pl = [];
            for (let i2 = 0; i2 < open.length; i2++) for (let j2 = i2 + 1; j2 < open.length; j2++) {
              const A2 = open[i2], B2 = open[j2];
              if (A2[0] + B2[0] === 0 && A2[1] + B2[1] === 0 && A2[2] + B2[2] === 0) continue;
              pl.push(...CUT.edge(A2, B2, 0.8));
            }
            return pl.length ? pl : null;
          } })
          .box(0, SG.NX, 0, SG.NY, 0, SG.NZ, (i, j, k) => { const ch = at(xi(i), j, k); return ch === '.' ? null : (ch === 'N' ? 'sole' : 'suede'); })
          .del(v => !v.cls).build(P);
        const cuff = subPart(p.g, name + '_manzhet', [s * LEG.hipX, HIP_Y + KNEE_Y, 0], [0, 0, s * LEG.shin]);
        [-0.8, 0.8].forEach(z => magnet(cuff, 0, -4.78, z, [0, 1, 0]));
        p.manzhet = cuff.finish();
        p.finish(); root.add(p.g); parts[name] = p;
        return;
      }
      const toe = NL - 1;
      const inShin = c => Math.abs(X0 + (c.x + 0.5) * S - bx) < 1.4 && Math.abs(Z0 + (c.z + 0.5) * S) < 1.4;
      /* подошва 0…0.5: неон, носок и пятка снизу скошены */
      new Vox({ cell: [S, 0.5, S], at: [X0, 0, Z0], maxLen: 4, merge: 0.6, salt: 171 + s, bev: 0.05,
        clip: clipOf(0, c => (c.z === toe ? CUT.edge([0, -1, 0], [0, 0, 1], 1.0) : (c.z === 0 ? CUT.edge([0, -1, 0], [0, 0, -1], 1.1) : null)))
      }).box(0, NW, 0, 1, 0, NL, 'sole').build(P);
      /* протектор: круглые шишки по кромке подошвы (бока и носок) */
      for (let k = 1; k < NL - 1; k++) [-1, 1].forEach(sx => {
        axial(P, CYL, sx < 0 ? X0 + 0.04 : X0 + NW * S - 0.04, 0.17, Z0 + (k + 0.5) * S, [sx, 0, 0], 0.2, 0.16, LIN.NG2, true);
      });
      for (let i = 1; i < NW - 1; i++) axial(P, CYL, X0 + (i + 0.5) * S, 0.17, Z0 + NL * S - 0.04, [0, 0, 1], 0.2, 0.16, LIN.NG2, true);
      /* замша 0.5…1.5 по всему следу, носок мягко скруглён сверху */
      new Vox({ cell: [S, 0.5, S], at: [X0, 0.5, Z0], maxLen: 6, merge: 0.92, gap: 0.008, salt: 172 + s, bev: 0.09,
        clip: clipOf(0, c => (c.y === 1 && c.z === toe ? CUT.edge([0, 1, 0], [0, 0, 1], 0.85) : null))
      }).box(0, NW, 0, 2, 0, NL, 'suede').build(P);
      /* 1.5…2.5: подъём к голени — каждый ряд короче, передняя кромка скруглена; проём под голень */
      const front = y => toe - 2 - y * 2;
      new Vox({ cell: [S, 0.5, S], at: [X0, 1.5, Z0], maxLen: 6, merge: 0.92, gap: 0.008, salt: 173 + s, bev: 0.1,
        clip: clipOf(9, c => {
          if (c.z === front(c.y)) return CUT.edge([0, 1, 0], [0, 0, 1], 0.9);
          if (c.z === 0) return CUT.edge([0, 1, 0], [0, 0, -1], 1.0);
          return null;
        })
      }).box(0, NW, 0, 2, 0, NL, 'suede').del(c => inShin(c) || c.z > front(c.y)).build(P);
      /* складки замши: валики по наружному боку у пятки и поперёк пятки */
      const xo = s > 0 ? X0 + NW * S : X0, zh = Z0;
      [0.95, 1.3, 1.68, 2.05].forEach((y, i) => {
        const len = 3.4 - i * 0.35;
        bevelBox(P, xo + s * 0.06, y, zh + len / 2 + 0.1, 0.09, 0.08, len / 2, 0.05, tone(i & 1 ? 'SU4' : 'SU2', 0.03), 63);
        bevelBox(P, X0 + NW * S / 2, y, zh - 0.06, NW * S / 2 - 0.35, 0.08, 0.09, 0.05, tone(i & 1 ? 'SU2' : 'SU4', 0.03), 63);
      });
      /* фиолетовая полоса снаружи: косая, от носка к пятке вверх (своя форма) */
      for (let k = 0; k < 6; k++) {
        const z = Z0 + NL * S - 1.4 - k * 0.62, y = 0.72 + k * 0.1;
        bevelBox(P, xo + s * 0.05, y, z, 0.06, 0.1, 0.34, 0.03, tone(k > 3 ? 'PU2' : 'PU', 0.02), 63);
      }
      /* ворот на голени: подгруппа с наклоном голени — гладкое голенище и толстый подвёрнутый валик сверху */
      const cuff = subPart(p.g, name + '_manzhet', [s * LEG.hipX, HIP_Y + KNEE_Y, 0], [0, 0, s * LEG.shin]);
      const CP = cuff.bufs.plastic, CB = -4.8, CT = -3.6;               // низкое голенище — сапожок до щиколотки
      new Vox({ cell: [0.7, (CT - CB) / 3, 0.7], at: [-2.1, CB, -2.1], maxLen: 6, merge: 0.92, gap: 0.008, salt: 176 + s, bev: 0.09,
        clip: c => ((c.x === 0 || c.x === 5) && (c.z === 0 || c.z === 5) ? CUT.edge([c.x ? 1 : -1, 0, 0], [0, 0, c.z ? 1 : -1], 0.75) : null)
      }).box(0, 6, 0, 3, 0, 6, (x, y, z) => (x >= 1 && x <= 4 && z >= 1 && z <= 4 ? null : 'suede')).del(v => !v.cls).build(CP);
      new Vox({ cell: [0.85, 0.45, 0.85], at: [-2.55, CT - 0.1, -2.55], maxLen: 6, merge: 0.9, gap: 0.008, salt: 178 + s, bev: 0.14,
        clip: c => {
          const pl = [], sx = c.x === 0 ? -1 : (c.x === 5 ? 1 : 0), sz = c.z === 0 ? -1 : (c.z === 5 ? 1 : 0);
          if (sx) pl.push(...CUT.edge([sx, 0, 0], [0, c.y ? 1 : -1, 0], 0.62));
          if (sz) pl.push(...CUT.edge([0, 0, sz], [0, c.y ? 1 : -1, 0], 0.62));
          if (sx && sz) pl.push(...CUT.edge([sx, 0, 0], [0, 0, sz], 0.7));
          return pl.length ? pl : null;
        }
      }).box(0, 6, 0, 2, 0, 6, (x, y, z) => (x >= 2 && x <= 3 && z >= 2 && z <= 3 ? null : 'suede')).del(v => !v.cls).build(CP);
      [-0.8, 0.8].forEach(z => magnet(cuff, 0, CB + 0.02, z, [0, 1, 0]));
      p.manzhet = cuff.finish();
      p.finish(); root.add(p.g); parts[name] = p;
    }
    foot(-1); foot(1);
    if (PRISED) {                                                    // «утка»: туловище, руки и голова ниже — ноги короче
      const nogi = new Set(['nogaL', 'nogaR', 'stupnyaL', 'stupnyaR'].map(k => parts[k].g));
      root.children.forEach(o => { if (!nogi.has(o) && o.type !== 'Mesh') o.position.y -= PRISED; });
    }

    /* ============================================================
       ПЛЮС ЦЕЛИКОМ-СЕТКА (opts.telo = 'setka', проба по листу тела Александра 24.10): вся фигура — одна литая сетка
       генератора 3D по виду 3/4 листа, цвет вершин — с видов листа, сведённый к палитре (tools/forma/telo_raskraska.py
       → src/plus-telo-setka.js). Прежние детали прячутся; улыбка — отдельной чёрной деталью по рамке лица.
       ============================================================ */
    const TELO = opts.telo === 'setka' && global.PixelMechTeloSetka;
    if (TELO) {
      // 30.10: --opt=teloCvet=v6 — цвет с кирпичной v6 (tools/forma/cvet_v6.py); =detali — цвет листа заливкой по деталям
      // (tools/forma/cvet_detali.py)
      const G = (opts.teloCvet === 'v6' && global.PixelMechTeloSetkaV6) || (opts.teloCvet === 'detali' && global.PixelMechTeloSetkaD) ||
        global.PixelMechTeloSetka;
      root.traverse(q => { if (q.isMesh || q.isLine || q.isPoints) q.visible = false; });   // прежние детали — не видны (группы нужны раскладке)
      Object.values(faceParts).forEach(o => o.traverse(q => { if (q.isMesh) q.scale.setScalar(1e-4); }));   // мимика прежней головы сама включает рот — сжать в точку
      const bin = b64 => { const t = (typeof atob === 'function') ? atob(b64) : Buffer.from(b64, 'base64').toString('binary'); const u = new Uint8Array(t.length); for (let i = 0; i < t.length; i++) u[i] = t.charCodeAt(i); return u; };
      const pos = new Float32Array(bin(G.pos).buffer), ix = new Uint32Array(bin(G.idx).buffer), kl = bin(G.cls);
      const col = new Float32Array(G.nv * 3);
      for (let i = 0; i < G.nv; i++) { const c = LIN[G.klassy[kl[i]]] || LIN.DG; col[i * 3] = c.r; col[i * 3 + 1] = c.g; col[i * 3 + 2] = c.b; }
      const geo = new T.BufferGeometry();
      geo.setAttribute('position', new T.BufferAttribute(pos, 3)); geo.setAttribute('color', new T.BufferAttribute(col, 3));
      geo.setIndex(new T.BufferAttribute(ix, 1)); geo.computeVertexNormals();
      const tg = new T.Group(); tg.name = 'telo_setka';
      const mesh = new T.Mesh(geo, MAT.plastic); mesh.name = 'telo_setka_plastic'; mesh.castShadow = mesh.receiveShadow = true; tg.add(mesh);
      if (G.lico) {                                                    // улыбка: полоса и уголки вверх, по середине лица
        const [x0, x1, y0, y1, zf] = G.lico, fw = x1 - x0, fh = y1 - y0, cx = (x0 + x1) / 2, t = fh * 0.1, mb = new MeshBuf(true);
        const ch = LIN.BK, yU = y0 + fh * 0.38;
        bevelBox(mb, cx, yU, zf + 0.04, fw * 0.2 + t / 2, t / 2, 0.06, 0.02, ch, 63);
        [-1, 1].forEach(sx => bevelBox(mb, cx + sx * fw * 0.2, yU + t * 0.8, zf + 0.04, t / 2, t * 1.3, 0.06, 0.02, ch, 63));
        const um = new T.Mesh(mb.geometry(), MAT.plastic); um.name = 'telo_setka_ulybka'; tg.add(um);
      }
      root.add(tg); parts.teloSetka = { g: tg, name: 'teloSetka' };
    }

    /* ---------- разлёт (разобранный вид) — снизу вверх, как на листе ---------- */
    const EXPLODE = {
      shapka: [0, 6.2, 0], ochki: [0, 5.3, 1.5], lico: [0, 0, 0], bokL: [-5.4, 0, 0], bokR: [5.4, 0, 0],
      golova: [0, 14.2, 0], sheya: [0, 11.8, 0], korpus: [0, 8.8, 0], rukaL: [-4.6, 8.8, 0], rukaR: [4.6, 8.8, 0],
      taz: [0, 6.0, 0], nogaL: [-0.9, 3.6, 0], nogaR: [0.9, 3.6, 0], stupnyaL: [-0.6, 0, 0], stupnyaR: [0.6, 0, 0]
    };
    parts.golova = { g: head, name: 'golova' };
    root.traverse(o => {
      o.userData.home = o.position.clone();
      o.userData.homeRot = o.rotation.clone();
      o.userData.homeScale = o.scale.clone();
      o.userData.homeVisible = o.visible;
    });

    return { root, parts, face: faceParts, MAT, EXPLODE, stats, HEAD_Y, PALETTE, bricks,
      joints: { HIP_Y, KNEE_Y, ANKLE, LEG, ARM } };
  }

  /* ======================= СТУДИЯ: свет, отражения, пол ======================= */
  function studioEnvironment(T, renderer) {
    const sc = new T.Scene();
    const box = new T.BoxGeometry(1, 1, 1);
    const room = new T.Mesh(box, new T.MeshBasicMaterial({ color: 0x423d37, side: T.BackSide }));
    room.scale.set(60, 40, 60); room.position.y = 10; sc.add(room);
    const floor = new T.Mesh(box, new T.MeshBasicMaterial({ color: 0x25221f }));
    floor.scale.set(60, 0.2, 60); floor.position.y = -9.8; sc.add(floor);
    const soft = (w, h, x, y, z, k) => {
      const m = new T.Mesh(new T.PlaneGeometry(w, h), new T.MeshBasicMaterial({ color: new T.Color(k, k, k), side: T.DoubleSide }));
      m.position.set(x, y, z); m.lookAt(0, 4, 0); sc.add(m);
    };
    /* свет на листе падает справа-спереди (правое ухо и плечо светлее левых) */
    soft(4, 44, 15, 4, 18, 3.2);           // главный софтбокс справа-спереди: вертикальный блик
    soft(2.2, 44, 8, 4, 22, 2.4);          // второй узкий — двойная полоса на очках
    soft(3, 40, -14, 4, 20, 1.6);          // заполняющий слева
    soft(24, 5, 0, 26, 4, 1.2);            // верхний
    soft(5, 16, -4, 8, -24, 1.8);          // контровой
    const pm = new T.PMREMGenerator(renderer);
    const tex = pm.fromScene(sc, 0.03).texture;
    pm.dispose();
    return tex;
  }
  /* отражения для зеркальной линзы: тёмная комната (середина линзы тёмная), узкие вертикальные
     полосы под ±17° и софтбоксы под ±40° — на дуге щитка они дают две пары бликов, как на листе */
  function lensEnvironment(T, renderer) {
    const sc = new T.Scene();
    const box = new T.BoxGeometry(1, 1, 1);
    const room = new T.Mesh(box, new T.MeshBasicMaterial({ color: 0x000000, side: T.BackSide }));
    room.scale.set(60, 40, 60); room.position.y = 10; sc.add(room);
    /* красные полосы: на дуге щитка (R 20) отражаются в левой и правой третях линзы */
    const strip = (w, h, x, y, z, k) => {
      const m = new T.Mesh(new T.PlaneGeometry(w, h), new T.MeshBasicMaterial({ color: new T.Color(k, k * 0.05, k * 0.012), side: T.DoubleSide }));
      m.position.set(x, y, z); m.lookAt(0, y, 0); sc.add(m);
    };
    strip(3.2, 70, 7.6, 8, 22.5, 1.0);
    strip(3.4, 70, -7.9, 8, 22.5, 1.1);
    strip(2.5, 70, 11.2, 8, 21, 0.45);
    strip(2.5, 70, -11.2, 8, 21, 0.45);
    strip(8, 70, 20, 8, 12, 0.22);                          // виски
    strip(8, 70, -20, 8, 12, 0.22);
    strip(30, 6, 0, 30, 0, 0.35);                            // верхняя кромка
    const pm = new T.PMREMGenerator(renderer);
    const tex = pm.fromScene(sc, 0.02).texture;
    pm.dispose();
    return tex;
  }
  /* сцена-студия: общий свет для вьювера и рендеров листа */
  function stage(T, renderer, scene, o) {
    o = o || {};
    renderer.outputEncoding = T.sRGBEncoding;
    renderer.toneMapping = T.NoToneMapping;
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = T.PCFSoftShadowMap;
    scene.environment = studioEnvironment(T, renderer);
    const lensEnv = lensEnvironment(T, renderer);
    const hemi = new T.HemisphereLight(0xfffcf6, 0x8a8478, 0.34); scene.add(hemi);
    const key = new T.DirectionalLight(0xfff8ee, 0.75);
    key.position.set(6, 26, 26); key.castShadow = true;
    const sm = o.shadowMap || 2048;
    key.shadow.mapSize.set(sm, sm);
    Object.assign(key.shadow.camera, { left: -24, right: 24, top: 44, bottom: -18, near: 1, far: 110 });
    key.shadow.bias = -0.0004; key.shadow.normalBias = 0.03;
    scene.add(key);
    const rim = new T.DirectionalLight(0xdce9ff, 0.4); rim.position.set(-15, 14, -18); scene.add(rim);
    const fill = new T.DirectionalLight(0xe9f1ff, 0.3); fill.position.set(-20, 3, 10); scene.add(fill);
    const floor = new T.Mesh(new T.PlaneGeometry(240, 240), new T.ShadowMaterial({ opacity: o.shadow == null ? 0.2 : o.shadow }));
    floor.rotation.x = -Math.PI / 2; floor.receiveShadow = true; floor.name = 'pol'; scene.add(floor);
    return { hemi, key, rim, fill, floor, lensEnv };
  }

  /* ======================= АНИМАЦИЯ СОСТОЯНИЙ ======================= */
  function makeAnimator(T, model) {
    const P = model.parts, F = model.face;
    const st = { explode: 0, explodeTarget: 0, glasses: 1, glassesTarget: 1, blinkAt: 2400, state: 'тихо', glow: 0 };
    const lerpK = (a, dtK) => 1 - Math.pow(1 - a, dtK);
    const SIDE_C = { bokL: new T.Vector3(-5.9, 0, -0.8), bokR: new T.Vector3(5.9, 0, -0.8) };
    const tmpV = new T.Vector3(), Y_AX = new T.Vector3(0, 1, 0);
    const glowLight = new T.PointLight(0x9a6bff, 0, 9, 2);
    glowLight.position.set(0, 11.6, 0);           // над пришельцем
    P.golova.g.add(glowLight);
    function ease(obj, key, target, k) { obj[key] += (target - obj[key]) * k; }
    function update(t, dtK) {
      const k = lerpK(0.14, dtK), s = st.state;
      const talk = s === 'говорит', think = s === 'думает', smile = s === 'улыбка', listen = s === 'слушает';
      if (t > st.blinkAt + 150) st.blinkAt = t + 2200 + Math.random() * 3200;
      const blink = st.blinkAt - t < 150 && st.blinkAt - t > 0;
      const eyeH = blink ? 0.1 : (listen ? 1.12 : 1);
      ease(F.eyeL.scale, 'y', eyeH, blink ? 1 : k); F.eyeR.scale.y = F.eyeL.scale.y;
      const look = think ? 0.35 : 0, lookY = think ? 0.3 : 0;
      ease(F.eyeL.position, 'x', F.eyeL.userData.home.x + look, k);
      ease(F.eyeR.position, 'x', F.eyeR.userData.home.x + look, k);
      ease(F.eyeL.position, 'y', F.eyeL.userData.home.y + lookY, k); F.eyeR.position.y = F.eyeL.position.y;
      const H = F.mouthBar.userData.home;
      const open = talk ? (Math.sin(t / 90) > -0.2 ? 2.6 + Math.sin(t / 47) * 0.6 : 1.2) : 1;
      ease(F.mouthBar.scale, 'y', open, talk ? 1 : k);
      ease(F.mouthBar.scale, 'x', think ? 0.55 : 1, k);
      ease(F.mouthBar.position, 'x', H.x + (think ? 0.7 : 0), k);
      /* уголки: в покое — одна ступень вверх; в улыбке — вторая ступень (ножки) и рот шире */
      ['L', 'R'].forEach(side => {
        const leg = F['mouthLeg' + side], cor = F['mouthCorner' + side];
        cor.visible = !talk && !think;
        leg.visible = smile;
      });
      F.teeth.visible = smile || (talk && open > 2);
      F.teeth.scale.x = talk ? 0.8 : 1;
      F.teeth.position.y = H.y + (smile ? 0.0 : 0.08);
      st.glasses += (st.glassesTarget - st.glasses) * lerpK(0.1, dtK);
      const o = P.ochki.g, oh = o.userData.home, up = think ? 1 : 0;
      const ex = st.explode * model.EXPLODE.ochki[1], ez = st.explode * model.EXPLODE.ochki[2];
      ease(o.position, 'y', oh.y + up * 2.1 * st.glasses + (1 - st.glasses) * 7.2 + ex, k);
      ease(o.position, 'z', oh.z + up * -0.5 * st.glasses + (1 - st.glasses) * 5 + ez, k);
      ease(o.rotation, 'x', -up * 0.38 * st.glasses - (1 - st.glasses) * 0.12, k);
      if (st.glassesTarget === 0) o.rotation.y = Math.sin(t / 1300) * 0.35;
      else o.rotation.y *= Math.pow(0.9, dtK);
      const hg = P.golova.g;
      const hr0 = hg.userData.homeRot || hg.rotation;
      ease(hg.rotation, 'x', hr0.x + (talk ? Math.sin(t / 150) * 0.035 : (listen ? 0.05 : 0)), k);
      ease(hg.rotation, 'z', hr0.z + (think ? 0.07 : (listen ? -0.06 : 0)), k);
      st.glow += ((listen ? 0.6 + 0.4 * Math.sin(t / 300) : 0) - st.glow) * k;
      glowLight.intensity = st.glow * 2.4;
      const hl = P.rukaL.g.userData.homeRot, hr = P.rukaR.g.userData.homeRot;
      P.rukaL.g.rotation.x = hl.x + Math.sin(t / 1700) * 0.04;
      P.rukaR.g.rotation.x = hr.x + Math.sin(t / 1700 + 1.9) * 0.04;
      P.rukaL.g.rotation.z = hl.z + (smile ? -0.08 : 0) + Math.sin(t / 2100) * 0.012;
      P.rukaR.g.rotation.z = hr.z + (smile ? 0.08 : 0) - Math.sin(t / 2100) * 0.012;
      st.explode += (st.explodeTarget - st.explode) * lerpK(0.09, dtK);
      Object.keys(model.EXPLODE).forEach(n => {
        if (n === 'ochki') return;
        const g = P[n].g, h = g.userData.home, d = model.EXPLODE[n];
        g.position.set(h.x + d[0] * st.explode, h.y + d[1] * st.explode, h.z + d[2] * st.explode);
      });
      /* боковые плиты в разлёте поворачиваются наружной стороной к зрителю, как на листе */
      [['bokL', 1], ['bokR', -1]].forEach(([n, sg]) => {
        const g = P[n].g, c = SIDE_C[n];
        g.rotation.y = sg * Math.PI / 2 * st.explode;
        tmpV.copy(c).applyAxisAngle(Y_AX, g.rotation.y);
        g.position.x += c.x - tmpV.x; g.position.z += c.z - tmpV.z;
      });
    }
    return { st, update };
  }

  /* ======================= ЭКСПОРТ: чистая сцена для glTF ======================= */
  function exportScene(T, model) {
    const sc = new T.Scene();
    const clone = model.root.clone(true);
    const drop = [];
    clone.traverse(o => {
      if (o.isLight || o.name === 'ochki_blik') drop.push(o);
      if (o.name === 'ochki_linza') o.material = model.MAT.glass;
    });
    drop.forEach(o => o.parent.remove(o));
    clone.traverse(o => {
      const u = o.userData;
      if (!u || !u.home) return;
      o.position.copy(u.home); o.rotation.copy(u.homeRot); o.scale.copy(u.homeScale); o.visible = u.homeVisible;
    });
    sc.add(clone);
    return sc;
  }

  /* деталь → кость гуманоидного скелета (Godot SkeletonProfileHumanoid); L/R в именах деталей — со стороны
     зрителя, в именах костей — со стороны фигурки */
  const BONE_OF_PART = { golova: 'Head', lico: 'Head', bokL: 'Head', bokR: 'Head', shapka: 'Head', ochki: 'Head',
    sheya: 'Neck', taz: 'Hips', korpus: 'Chest',
    rukaR: 'LeftUpperArm', rukaR_naruch: 'LeftLowerArm', rukaL: 'RightUpperArm', rukaL_naruch: 'RightLowerArm',
    nogaR: 'LeftUpperLeg', nogaR_golen: 'LeftLowerLeg', nogaL: 'RightUpperLeg', nogaL_golen: 'RightLowerLeg',
    stupnyaR: 'LeftFoot', stupnyaR_manzhet: 'LeftFoot', stupnyaL: 'RightFoot', stupnyaL_manzhet: 'RightFoot' };
  const MAP = BONE_OF_PART;

  /* ======================= ПАСПОРТ КИРПИЧЕЙ (для будущей разборки на кирпичики) =======================
     Модель создаётся с { manifest: true }. Для каждой детали — её матрица в мире (домашняя поза) и кость;
     для каждого кирпича — центр и полуразмеры в координатах детали, цвет sRGB, класс, шипы. */
  function brickManifest(T, model) {
    if (!model.bricks) throw new Error('модель создана без { manifest: true }');
    model.root.updateMatrixWorld(true);
    const names = new Set(model.bricks.map(b => b.part)), parts = {};
    model.root.traverse(o => {
      if (!names.has(o.name) || parts[o.name]) return;
      let bone = null; for (let x = o; x && !bone; x = x.parent) bone = BONE_OF_PART[x.name] || null;
      parts[o.name] = { bone, matrix: o.matrixWorld.elements.map(v => +v.toFixed(5)) };
    });
    return {
      format: 'pixel-mech-bricks/1',
      units: '1 = 1 см; шаг шипа 0.8 (8 мм); y — вверх, фигурка смотрит в +z',
      faces: ['+x', '-x', '+y', '-y', '+z', '-z'],
      counts: { bricks: model.bricks.filter(b => b.kind === 'brick').length, slopes: model.bricks.filter(b => b.kind === 'slope').length,
        studs: model.bricks.reduce((a, b) => a + (b.studs ? b.studs.length : 0), 0) },
      parts, bricks: model.bricks
    };
  }

  /* ======================= ЭКСПОРТ СО СКЕЛЕТОМ (для Godot/VR, рэгдолла, анимации) =======================
     Все детали сливаются в одну сетку на материал; каждая вершина жёстко привязана (вес 1) к кости
     своей детали. Имена костей — как в гуманоидном профиле Godot (SkeletonProfileHumanoid), поэтому
     ретаргетинг анимаций и «Create Physical Skeleton» работают сразу. Лево/право — со стороны фигурки
     (она смотрит в +z, её левая сторона — +x). Масштаб по умолчанию 0.01: 1 единица = 1 см, в glTF — метры
     (шаг шипа 8 мм, как у настоящих кирпичиков; фигурка ≈ 31 см). */
  function exportRig(T, model, o) {
    o = o || {};
    const K = o.scale == null ? 0.01 : o.scale;
    const sc = exportScene(T, model);
    sc.updateMatrixWorld(true);
    const src = sc.children[0];
    const node = n => { let r = null; src.traverse(x => { if (!r && x.name === n) r = x; }); return r; };
    const at = (n, loc) => node(n).localToWorld(new T.Vector3(...(loc || [0, 0, 0])));
    const J = model.joints;
    /* кости: имя, родитель, точка в мире (домашняя поза) */
    const B = [
      ['Root', null, new T.Vector3(0, 0, 0)],
      ['Hips', 'Root', new T.Vector3(0, J.HIP_Y, 0)],
      ['Spine', 'Hips', new T.Vector3(0, 12.4, 0)],
      ['Chest', 'Spine', new T.Vector3(0, 14.0, 0)],
      ['Neck', 'Chest', at('sheya')],
      ['Head', 'Neck', at('golova')]
    ];
    [['Left', 'R'], ['Right', 'L']].forEach(([side, ours]) => {
      const s = ours === 'R' ? 1 : -1;
      B.push([side + 'UpperArm', 'Chest', at('ruka' + ours)]);
      B.push([side + 'LowerArm', side + 'UpperArm', at('ruka' + ours + '_naruch')]);
      B.push([side + 'Hand', side + 'LowerArm', at('ruka' + ours + '_naruch', [s * 1.05, -4.9, 0.25])]);
      B.push([side + 'UpperLeg', 'Hips', at('noga' + ours)]);
      B.push([side + 'LowerLeg', side + 'UpperLeg', at('noga' + ours + '_golen')]);
      B.push([side + 'Foot', side + 'LowerLeg', at('noga' + ours + '_golen', [0, -J.ANKLE, 0])]);
      B.push([side + 'Toes', side + 'Foot', new T.Vector3(at('noga' + ours + '_golen', [0, -J.ANKLE, 0]).x + s * J.LEG.toeOut, 0.9, 3.4)]);
    });
    const bones = [], byName = {};
    B.forEach(([name, parent, w]) => {
      const b = new T.Bone(); b.name = name; b.userData.w = w.clone().multiplyScalar(K);
      if (parent) {
        const pw = byName[parent].userData.w;
        b.position.copy(b.userData.w).sub(pw);
        byName[parent].add(b);
      } else b.position.copy(b.userData.w);
      byName[name] = b; bones.push(b);
    });
    /* деталь → кость: по ближайшему предку с известным именем */
    const boneOf = m => { for (let x = m; x; x = x.parent) if (MAP[x.name]) return MAP[x.name]; return 'Root'; };
    const chunks = new Map();                                   // материал → куски геометрии
    src.traverse(m => {
      if (!m.isMesh || !m.visible) return;
      let vis = true; for (let x = m; x; x = x.parent) if (!x.visible) vis = false;
      if (!vis) return;
      const g = m.geometry.clone().applyMatrix4(m.matrixWorld);
      const n = g.attributes.position.count, bi = new Uint16Array(n * 4), bw = new Float32Array(n * 4);
      const base = boneOf(m), pos = g.attributes.position;
      for (let i = 0; i < n; i++) {
        /* корпус делится по шву 14.0: живот и пояса — на Spine, грудь — на Chest */
        const nm = base === 'Chest' && pos.getY(i) < 14.0 ? 'Spine' : base;
        bi[i * 4] = bones.indexOf(byName[nm]); bw[i * 4] = 1;
      }
      g.scale(K, K, K);
      g.setAttribute('skinIndex', new T.Uint16BufferAttribute(bi, 4));
      g.setAttribute('skinWeight', new T.Float32BufferAttribute(bw, 4));
      if (!chunks.has(m.material)) chunks.set(m.material, []);
      chunks.get(m.material).push(g);
    });
    const out = new T.Scene();
    const rig = new T.Group(); rig.name = 'PixelMech'; out.add(rig);
    rig.add(bones[0]);
    rig.updateMatrixWorld(true);
    const skeleton = new T.Skeleton(bones);
    for (const [mat, list] of chunks) {
      const g = new T.BufferGeometry(), names = ['position', 'normal', 'color', 'skinIndex', 'skinWeight'];
      let total = 0, idx = 0;
      list.forEach(x => { total += x.attributes.position.count; idx += x.index.count; });
      const index = new Uint32Array(idx);
      names.forEach(a => {
        const size = list[0].attributes[a].itemSize, Arr = a === 'skinIndex' ? Uint16Array : Float32Array;
        const arr = new Arr(total * size); let off = 0;
        list.forEach(x => { arr.set(x.attributes[a].array, off); off += x.attributes[a].array.length; });
        g.setAttribute(a, new T.BufferAttribute(arr, size));
      });
      let vo = 0, io = 0;
      list.forEach(x => { const ia = x.index.array; for (let i = 0; i < ia.length; i++) index[io++] = ia[i] + vo; vo += x.attributes.position.count; });
      g.setIndex(new T.BufferAttribute(index, 1));
      g.computeBoundingSphere();
      const mesh = new T.SkinnedMesh(g, mat);
      mesh.name = 'PixelMech_' + (mat.name || 'mat');
      rig.add(mesh);
      mesh.bind(skeleton);
    }
    return out;
  }

  global.PixelMech = { create, studioEnvironment, lensEnvironment, stage, makeAnimator, exportScene, exportRig, brickManifest, BONE_OF_PART, PALETTE };
})(typeof window !== 'undefined' ? window : globalThis);
