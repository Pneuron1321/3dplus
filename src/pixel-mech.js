/* ============================================================================
   PIXEL MECH v3 — модульная коллекционная фигурка из кирпичиков.
   Генератор модели для three.js r128 (глобальный THREE).
   Задание и целевые замеры: docs/PROMPT-v3.md; сверка с листом: tools/qa.py.

   Как устроено:
   • Каждая деталь — воксельная сетка со своим шагом клетки (обычный кирпич —
     0.8 × h × 0.8, «макро»-блоки кулаков и груди — крупнее).
   • Видимые клетки «кирпичатся»: соседние клетки одного класса сливаются в
     1×2…1×6 с перевязкой рядов, у каждого кирпича фаска и свой оттенок.
   • Клетка может быть «скосом»: коробка, отсечённая плоскостями (срезанные
     углы кулаков, скруглённая кромка ступней, скосы на шапке и плечах).
   • За каждым кирпичом стоит тёмная «подложка» — сквозь швы видна глубина.
   • Затенение в углах (AO) запекается в цвета вершин по заполненности
     соседних клеток — только внутри детали, чтобы разлёт оставался чистым.
   • Шипы (обычные и боковые) — точёные; техник-втулки, болты-оси, шея,
     очки и магнитные шайбы на стыках — отдельная геометрия.
   Всё одной детали сливается в одну сетку с цветами вершин.

   Координаты: x — вправо (от зрителя), y — вверх, z — к зрителю (лицо).
   Единица: шаг шипа 0.8. Пол — y = 0. Высота до шипов рожек — 28.
   ========================================================================== */
(function (global) {
  'use strict';

  /* ---------- палитра (sRGB), откалибрована по зонам листа-образца ---------- */
  const PALETTE = {
    WH: '#dcdddb', WH2: '#e6e6e4', LG: '#bdbebe', LG2: '#aeafb0', MG: '#8a8b8c', MG2: '#747270',
    DG: '#565759', DG2: '#444547', GM: '#0f1012', BK: '#121214', SV: '#9ea3a8',
    YE: '#f6cd10', YE2: '#efc20c', YE3: '#f9d52c',
    O1: '#d06400', O2: '#f27400', O3: '#ee9010', O4: '#f39a12', O5: '#bf5a00', O6: '#ea7c06',
    OT1: '#e06c00', OT2: '#cc5e00', OT3: '#ec7a04',
    PU: '#8656ec', PU2: '#7444dc', PU3: '#9464f0'
  };

  /* смеси: класс клетки → из чего выбирается цвет каждого кирпича */
  const MIX = {
    head: [['WH', 0.62], ['WH2', 0.18], ['LG', 0.2]],
    headBack: [['LG', 0.5], ['WH', 0.25], ['MG', 0.25]],
    side: [['LG', 0.5], ['WH', 0.3], ['LG2', 0.2]],
    sideFront: [['DG', 0.6], ['MG', 0.25], ['DG2', 0.15]],
    yellow: [['YE', 0.72], ['YE2', 0.16], ['YE3', 0.12]],
    purple: [['PU', 0.8], ['PU2', 0.12], ['PU3', 0.08]],
    dark: [['DG', 0.72], ['DG2', 0.28]],
    darkMid: [['DG', 0.55], ['MG', 0.2], ['DG2', 0.25]],
    beltMid: [['MG', 0.75], ['DG', 0.25]],
    light: [['LG', 0.68], ['WH', 0.16], ['LG2', 0.16]],
    topBand: [['LG2', 0.45], ['MG', 0.4], ['LG', 0.15]],
    fist: [['WH', 0.5], ['WH2', 0.38], ['LG', 0.12]],
    shin: [['MG2', 0.55], ['MG', 0.2], ['DG', 0.25]],
    ear: [['O1', 0.5], ['O5', 0.5]],
    footBase: [['O4', 0.6], ['O3', 0.4]],
    footMid: [['O1', 0.62], ['O6', 0.2], ['O5', 0.18]],
    footTop: [['OT1', 0.45], ['OT3', 0.3], ['OT2', 0.25]],
    bracket: [['O2', 0.5], ['O1', 0.3], ['O6', 0.2]],
    techDG: [['DG', 1]]
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
      for (const poly of cur) {
        /* нормаль Ньюэлла */
        let nx = 0, ny = 0, nz = 0;
        for (let i = 0; i < poly.length; i++) {
          const A = poly[i], B = poly[(i + 1) % poly.length];
          nx += (A[1] - B[1]) * (A[2] + B[2]); ny += (A[2] - B[2]) * (A[0] + B[0]); nz += (A[0] - B[0]) * (A[1] + B[1]);
        }
        const l = Math.hypot(nx, ny, nz) || 1; nx /= l; ny /= l; nz /= l;
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
        idx.push(a, b, a + 1, b, b + 1, a + 1);
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
    ], 20);
    /* цилиндр с фаской (для осей, штифтов, шайб): радиус 1, высота 1, от 0 до 1 */
    function cylTpl(bev, seg) {
      const b = bev || 0.08;
      return latheTpl([
        [0, 0, 0, -1], [1 - b, 0, 0, -1], [1, b, 0.7, -0.7], [1, b, 1, 0],
        [1, 1 - b, 1, 0], [1, 1 - b, 0.7, 0.7], [1 - b, 1, 0, 1], [0, 1, 0, 1]
      ], seg || 24);
    }
    const CYL = cylTpl(0.1, 24), CYL_FINE = cylTpl(0.06, 40), HEX = cylTpl(0.02, 6);
    /* трубка-втулка (техник-отверстие) */
    function tubeTpl(rIn) {
      return latheTpl([
        [rIn, 0.2, -1, 0], [rIn, 1, -1, 0], [rIn, 1, 0, 1], [0.92, 1, 0, 1], [1, 0.92, 0.7, 0.7], [1, 0, 1, 0]
      ], 24);
    }
    const TUBE = tubeTpl(0.55);

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
          stats.bricks++;
          const dark = col.clone().multiplyScalar(this.coreDark);
          if (!opts.noCore) for (const c of run) this.core(mb, c, masks.get(this.K(c.x, c.y, c.z)), dark);
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
    /* магнитная шайба на стыке: в сборке скрыта, в разлёте видна */
    function magnet(p, x, y, z, dir) {
      axial(p.bufs.metal, CYL_FINE, x - dir[0] * 0.02, y - dir[1] * 0.02, z - dir[2] * 0.02, dir, 0.27, 0.05, LIN.SV);
      axial(p.bufs.metal, CYL_FINE, x + dir[0] * 0.025, y + dir[1] * 0.025, z + dir[2] * 0.025, dir, 0.12, 0.012, LIN.GM);
      stats.magnets++;
    }

    const parts = {};
    const root = new T.Group(); root.name = 'PixelMech';

    /* ============================================================
       ГОЛОВА — узел у верха шеи (0, 17.0, 0). Замеры — сетка по листу (docs/PROMPT-v4.md).
       lico (блок с лицевой панелью), bokL/bokR (боковые плиты),
       shapka (череп-шапка с пришельцем), ochki (очки).
       ============================================================ */
    const HEAD_Y = 17.0;
    const W = y => y - HEAD_Y;                               // мировая высота → координаты головы
    const head = new T.Group(); head.name = 'golova'; head.position.set(0, HEAD_Y, 0); root.add(head);
    head.rotation.x = opts.headTilt == null ? 0 : +opts.headTilt;     // наклон головы вперёд (поза)
    const FACE_ROW = 0.512, FACE_ROWS = 13, FACE_Y0 = 0.2;
    const CAP_Y = FACE_Y0 + FACE_ROWS * FACE_ROW;            // низ шапки: 23.86 в мире

    /* ---- лицевой блок: 12 × 13 рядов по 0.512 × 12; спереди 2 клетки жёлтые;
            нижний ряд утоплен (подбородок ±3.6); под линзой — длинные кирпичи ---- */
    const lico = part('lico');
    {
      new Vox({ cell: [S, FACE_ROW, S], at: [-4.8, FACE_Y0, -4.8], maxLen: 6, merge: 0.62, salt: 3, gap: 0.02, bev: 0.034,
        mergeFor: row => (row >= 6 && row <= 11 ? 0.93 : 0.62),
        clip: c => (c.y === 0 && (c.x === 1 || c.x === 10) ? CUT.edge([c.x === 1 ? -1 : 1, 0, 0], [0, -1, 0], 0.9) : null) })
        .box(0, 12, 0, FACE_ROWS, 0, 12, (x, y, z) => (z >= 10 ? 'yellow' : (z <= 1 ? 'headBack' : 'head')))
        .paint(v => (v.y === 0 && v.z < 10 ? 'dark' : null))
        .del(v => v.y === 0 && (v.x === 0 || v.x === 11))
        .build(lico.bufs.plastic);
      new Vox({ cell: [S, 0.2, S], at: [-3.6, 0.0, -3.6], maxLen: 4, salt: 5 })   // тёмные плиты низа головы
        .box(0, 9, 0, 1, 0, 9, 'dark').build(lico.bufs.plastic);
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
    inkTile('mouthBar', 4.4, 0.52, 0, 19.2);
    inkTile('mouthLegL', 0.42, 0.52, -1.99, 18.7);
    inkTile('mouthLegR', 0.42, 0.52, 1.99, 18.7);
    inkTile('mouthCornerL', 0.4, 0.4, -2.45, 19.98);
    inkTile('mouthCornerR', 0.4, 0.4, 2.45, 19.98);
    {                                                        // зубы для улыбки/речи
      const mb = new MeshBuf();
      bevelBox(mb, 0, 0, 0, 1.7, 0.15, 0.05, 0.03, LIN.WH2, 63 - 32);
      const t = new T.Mesh(mb.geometry(), MAT.plastic);
      t.name = 'teeth'; t.position.set(0, W(19.2), FZ + 0.02); t.visible = false;
      lico.g.add(t); faceParts.teeth = t;
    }

    /* ---- боковые плиты: толщина 1.25, y 18.35…25.55; круглые шипы вбок и вперёд,
            тёмный техник-кирпич с двумя отверстиями сверху спереди, оранжевое ухо сзади ---- */
    function sidePlate(s) {
      const name = s < 0 ? 'bokL' : 'bokR';
      const p = part(name);
      const out = s < 0 ? 1 : 0;                           // индекс грани наружу
      const TH = 1.25, x0 = s < 0 ? -4.8 - TH : 4.8, Y0 = W(18.15);
      new Vox({
        cell: [TH, S, S], at: [x0, Y0, -4.8], maxLen: 3, merge: 0.5, salt: s < 0 ? 11 : 12,
        studTone: c => (c.cls === 'sideFront' || c.cls === 'techDG' ? 'LG' : null),
        studs: (c, f) => {
          if (f === out) return (((c.y === 1 || c.y === 3) && (c.z === 6 || c.z === 8)) || (c.y === 5 && c.z === 6)) ? 0.38 : false;
          if (f === 4) return (c.y === 0 || c.y === 2 || c.y === 7) ? 0.32 : false;   // шипы вперёд на торце плиты
          if (f === 2) return c.y === 8 && (c.z & 1) === 1 && c.z <= 7;
          return false;
        },
        clip: c => {
          if (c.y === 8) {
            if (c.z === 0) return CUT.corner(s, 1, -1, 1.4);
            if (c.z === 9) return CUT.corner(s, 1, 1, 1.4);
            return CUT.edge([s, 0, 0], [0, 1, 0], 1.05);
          }
          if (c.y === 0 && c.z === 0) return CUT.corner(s, -1, -1, 1.4);
          return null;
        }
      }).box(0, 1, 0, 9, 0, 10, (x, y, z) => {
        if ((y === 6 || y === 7) && z >= 6 && z <= 8) return 'techDG';
        if (y === 8) return z === 0 ? 'DG' : 'LG';
        if (z === 9 && y <= 5) return 'sideFront';
        return 'side';
      }).build(p.bufs.plastic);
      /* ухо: выступ 1.0, 5 рядов (19.95…23.95), два боковых шипа */
      new Vox({
        cell: [1.0, S, S], at: [s < 0 ? x0 - 1.0 : x0 + TH, Y0, -4.8], maxLen: 2, merge: 0.2, salt: s < 0 ? 13 : 14,
        studs: (c, f) => (f === out && c.z === 1 && (c.y === 3 || c.y === 5)) ? 0.33 : false,
        clip: c => ((c.y === 6 || c.y === 2) && c.z === 0 ? CUT.edge([0, c.y === 6 ? 1 : -1, 0], [0, 0, -1], 0.9) : null)
      }).box(0, 1, 2, 7, 0, 3, 'ear').build(p.bufs.plastic);
      const xo = s < 0 ? x0 - 0.011 : x0 + TH + 0.011, d = [s, 0, 0];
      socket(p, xo, W(23.75), 0.6, d, LIN.DG, 0.36);                             // техник-кирпич: два отверстия
      socket(p, xo, W(23.75), 1.8, d, LIN.DG, 0.36);
      socket(p, s * (4.8 + TH / 2), W(24.15), 3.211, [0, 0, 1], LIN.DG2, 0.3);   // отверстие на торце
      pin(p, xo, W(22.6), 0.1, d, LIN.BK.clone().multiplyScalar(1.6), 0.22);     // шарнир дужки очков
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
        clip: c => (corner(c) && c.z === 0 ? CUT.edge([c.x ? 1 : -1, 0, 0], [0, 0, -1], 0.62) : null)
      }).box(0, 6, 0, 1, 0, 6, (x, y, z) => (z === 0 ? 'headBack' : 'head')).build(shapka.bufs.plastic);
      new Vox({ cell: [M, S, M], at: [-4.8, CAP_Y + S, -4.8], maxLen: 2, merge: 0.35, salt: 22, bev: 0.05,
        studs: (c, f) => (f === 2 && !inAlien(c.x, c.z)) ? 0.46 : false,
        clip: c => (!corner(c) ? null : (c.z === 0 ? CUT.corner(c.x ? 1 : -1, 1, -1, 1.25) : CUT.edge([c.x ? 1 : -1, 0, 0], [0, 1, 0], 1.45)))
      }).box(0, 6, 0, 1, 0, 6, (x, y, z) => ((x === 5 && z === 0) ? 'DG' : (z === 0 ? 'headBack' : (z === 5 ? 'WH' : 'head'))))
        .build(shapka.bufs.plastic);
      /* пришелец на шапке: тело — кирпич 0.8 + гладкая плитка 0.24 (1.04),
         рожки — два блока по 1.0 с шипом (выше тела на ~1); глазницы — утопленные чёрные плитки */
      const AY = CAP_Y + 2 * S;
      const HORN = +(opts.hornH || 1.0);
      const alien = new Vox({ cell: [S, 0.8, S], at: [-3.2, AY, -1.6], maxLen: 4, merge: 0.8, salt: 23, gap: 0.024 });
      const tiles = new Vox({ cell: [S, 0.24, S], at: [-3.2, AY + 0.8, -1.6], maxLen: 4, merge: 0.85, salt: 24, gap: 0.024, bev: 0.03 });
      const horns = new Vox({ cell: [S, HORN, S], at: [-3.2, AY, -1.6], maxLen: 1, salt: 26, gap: 0.024,
        studs: (c, f) => f === 2 && c.y === 1 });
      ALIEN.forEach((row, i) => [...row].forEach((ch, j) => {
        if (ch === 'X') { alien.set(j, 0, i, 'purple'); tiles.set(j, 0, i, 'purple'); }
        if (ch === 'o') alien.set(j, 0, i, 'BK');
        if (ch === 'A') for (let y = 0; y < 2; y++) horns.set(j, y, i, 'PU');
      }));
      [alien, tiles, horns].forEach(v => v.build(shapka.bufs.plastic));
    }
    shapka.finish(); head.add(shapka.g); parts.shapka = shapka;

    /* ---- ОЧКИ: зеркальный щиток с переносицей, тёмная тонировка, оправа, дужки к шарниру ---- */
    const ochki = new T.Group(); ochki.name = 'ochki'; head.add(ochki);
    {
      /* в плане: пологая дуга спереди (радиус RF — зеркальные полосы расходятся к краям, как на листе),
         скругление R у висков, прямые бока назад до ZB */
      const A = 6.2, ZF = 5.56, R = 0.95, RF = 20, ZB = 1.9;
      const YT = W(23.32), YB = W(20.45), YN = W(21.24), YS = W(22.4);
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
      const N = 150, M = 12, TH = 0.055;
      const YM = (YT + YB) / 2 + 0.2, HH = (YT - YB) / 2, BUL = 0.24;
      const bulge = y => -BUL * Math.pow((y - YM) / HH, 2);
      const bulgeD = y => -2 * BUL * (y - YM) / (HH * HH);
      /* тонировка: тёмный красный (жёлтое под ним → #70…90 красный, чёрное → чёрное), светлее у верхней кромки */
      const tLow = new T.Color('#720c00').convertSRGBToLinear(), tHigh = new T.Color('#861206').convertSRGBToLinear();
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
       ШЕЯ-ШТЫРЬ: воронёный ПВХ, воротник с винтами
       ============================================================ */
    const sheya = part('sheya', [0, 16.3, 0]);
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

    /* ============================================================
       КОРПУС (замеры с листа): таз ±4.1 (8.35…10.3) с гнёздами бёдер и выступом
       промежности, тёмный пояс (10.3…11.3), грудь 6 × 2 макро-блока ±4.74 (11.3…14.8):
       4 крупных шипа спереди, белая середина 2 × 2, крайние столбцы со скосом
       и шипами на скосе; верхний ряд (14.8…15.4) и верх с шипами (15.4…16.2);
       тёмные плечевые блоки с чёрными штырями, светлая ось к руке.
       ============================================================ */
    const korpus = part('korpus');
    {
      const P = korpus.bufs.plastic;
      /* таз: ±3.7, y 8.3…10.4, спереди круглые гнёзда бёдер, по углам чёрные штифты; промежность ниже, до 7.6 */
      new Vox({ cell: [0.925, 0.683, 0.8], at: [-3.7, 8.3, -2.8], maxLen: 3, merge: 0.5, salt: 31,
        clip: c => (c.y === 0 && (c.x === 0 || c.x === 7) && (c.z === 0 || c.z === 6) ? CUT.corner(c.x ? 1 : -1, -1, c.z ? 1 : -1, 1.4) : null)
      }).box(0, 8, 0, 3, 0, 7, (x, y, z) => (z === 6 && (x === 3 || x === 4) ? 'darkMid' : 'dark')).build(P);
      new Vox({ cell: [0.75, 0.7, 0.8], at: [-1.5, 7.6, -2.0], maxLen: 4, salt: 32 })
        .box(0, 4, 0, 1, 0, 5, 'darkMid').build(P);
      [-1, 1].forEach(s => {
        socket(korpus, s * 3.0, 9.6, 2.811, [0, 0, 1], LIN.DG2, 0.5);                           // гнёзда бёдер
        axial(P, CYL, s * 3.45, 10.35, 2.4, [0, 1, 0], 0.17, 0.3, LIN.BK, true);                // чёрные штифты по углам
        magnet(korpus, s * 3.3, 8.3, 0, [0, -1, 0]);
      });
      /* пояс под грудью: тёмный ряд 10.35…10.9 на всю ширину груди */
      new Vox({ cell: [S, 0.55, S], at: [-4.8, 10.35, -3.2], maxLen: 4, merge: 0.5, salt: 33 })
        .box(0, 12, 0, 1, 0, 8, 'darkMid').build(P);
      /* грудь: 6 × 2 × 4 макро-блоков 1.58 × 1.8 × 1.55 (10.9…14.5) */
      const CX = [1.58, 1.8, 1.55], CA = [-4.74, 10.9, -3.1];
      new Vox({
        cell: CX, at: CA, maxLen: 1, gap: 0.03, bev: 0.06, salt: 35,
        studs: (c, f) => {
          if (c.cls === 'WH') return false;
          if (f === 4) return (c.x === 1 || c.x === 4) ? 0.52 : false;
          if (f === 0 || f === 1) return (c.z === 1 || c.z === 2) ? 0.44 : false;
          if (f === 5) return (c.x === 1 || c.x === 4) ? 0.48 : false;
          return false;
        },
        clip: c => ((c.x === 0 || c.x === 5) && c.z === 3 ? CUT.edge([c.x ? 1 : -1, 0, 0], [0, 0, 1], 0.55) : null)
      }).box(0, 6, 0, 2, 0, 4, (x, y, z) => (z === 3 && (x === 2 || x === 3) ? 'WH' : 'light')).build(P);
      [0, 5].forEach(xi => [0, 1].forEach(yi => {                                             // шипы на скошенных углах
        const sx = xi ? 1 : -1, hx = CX[0] / 2 - 0.015, hz = CX[2] / 2 - 0.015, u = 0.275;
        const cx = CA[0] + (xi + 0.5) * CX[0], cy = CA[1] + (yi + 0.5) * CX[1], cz = CA[2] + 3.5 * CX[2];
        studOn(P, cx + sx * u * hx, cy, cz + u * hz, [sx / hx, 0, 1 / hz], 0.44, tone('LG'));
      }));
      /* верхний ряд груди 14.5…15.1: спереди белые кирпичи */
      new Vox({
        cell: [S, 0.6, S], at: [-4.8, 14.5, -3.2], maxLen: 2, merge: 0.3, salt: 36,
        clip: c => ((c.x === 0 || c.x === 11) && (c.z === 0 || c.z === 7) ? CUT.edge([c.x ? 1 : -1, 0, 0], [0, 0, c.z ? 1 : -1], 0.7) : null)
      }).box(0, 12, 0, 1, 0, 8, (x, y, z) => (z === 7 && x >= 2 && x <= 9 ? 'WH' : 'light')).build(P);
      /* верх 15.1…15.7: шипы по периметру, передняя кромка скошена */
      new Vox({
        cell: [S, 0.6, S], at: [-4.8, 15.1, -3.2], maxLen: 4, merge: 0.45, salt: 37,
        studs: (c, f) => {
          if (f !== 2) return false;
          const x = -4.8 + (c.x + 0.5) * S, z = -3.2 + (c.z + 0.5) * S;
          return c.z < 7 && Math.hypot(x, z - 0.1) > 1.9;
        },
        clip: c => {
          const sx = c.x === 0 ? -1 : (c.x === 11 ? 1 : 0);
          if (sx && (c.z === 0 || c.z === 7)) return CUT.corner(sx, 1, c.z ? 1 : -1, 1.45);
          if (c.z === 7) return CUT.edge([0, 0, 1], [0, 1, 0], 1.1);
          if (sx) return CUT.edge([sx, 0, 0], [0, 1, 0], 1.2);
          return null;
        }
      }).box(0, 12, 0, 1, 0, 8, (x, y, z) => (z === 0 ? 'headBack' : 'topBand')).build(P);
      new Vox({ cell: [S, 0.32, S], at: [-1.6, 15.7, -1.5], maxLen: 4, salt: 39,                 // гнездо шеи
        studs: (c, f) => f === 2 && (c.x === 0 || c.x === 3) && (c.z === 0 || c.z === 3) })
        .box(0, 4, 0, 1, 0, 4, 'dark').build(P);
      /* плечевые блоки: x 5.3…8.3, y 12.3…15.3, с чёрными штырями сверху */
      [-1, 1].forEach(s => {
        new Vox({
          cell: [0.75, 0.75, 0.8], at: [s < 0 ? -8.3 : 5.3, 12.3, -1.6], maxLen: 3, merge: 0.45, salt: 41 + s,
          clip: c => (c.y === 3 && (s < 0 ? c.x === 0 : c.x === 3) ? CUT.edge([s, 0, 0], [0, 1, 0], 0.9) : null)
        }).box(0, 4, 0, 4, 0, 4, (x, y) => (y === 3 ? 'darkMid' : 'dark')).build(P);
        axial(P, CYL, s * 6.0, 15.3, 0, [0, 1, 0], 0.32, 0.45, LIN.BK.clone().multiplyScalar(1.3), true);
        axial(P, CYL, s * 6.0, 15.73, 0, [0, 1, 0], 0.2, 0.08, LIN.BK, true);
        axial(P, CYL, s * 7.95, 15.0, 0, [s * 0.5, 0.87, 0], 0.26, 0.42, LIN.BK.clone().multiplyScalar(1.3), true);
        socket(korpus, s * 7.55, 13.4, 1.611, [0, 0, 1], LIN.DG2, 0.34);
        socket(korpus, s * 6.1, 14.5, 1.611, [0, 0, 1], LIN.DG2, 0.26);
        rod(korpus, s * 4.74, 14.3, 0.6, [s, 0, 0], 0.6);                                      // светлая ось к плечу
        magnet(korpus, s * 5.3, 14.0, -0.8, [s, 0, 0]);
      });
    }
    korpus.finish(); root.add(korpus.g); parts.korpus = korpus;

    /* ============================================================
       РУКИ: кулак-блок 3 × 5 × 3 макро-клеток 1.4 (4.2 × 7.0 × 4.2),
       на каждой наружной клетке один крупный шип, нижний ряд — тёмные костяшки;
       поза подобрана по силуэту листа (крепится под плечевым блоком)
       ============================================================ */
    const ARM_POSE = opts.armPose || { pos: [2.6, -1.8, 0.35], rot: [0.4, 0.05, 0.45], tilt: 0 };
    function arm(s) {
      const name = s < 0 ? 'rukaL' : 'rukaR';
      const p = part(name, [s * 6.8, 12.3, 0]);
      const fist = subPart(p.g, name + '_kulak', [s * ARM_POSE.pos[0], ARM_POSE.pos[1], ARM_POSE.pos[2]],
        [ARM_POSE.rot[0], s * ARM_POSE.rot[1], s * ARM_POSE.rot[2]]);
      const outF = s < 0 ? 1 : 0, inF = s < 0 ? 0 : 1;
      const FR = +(opts.fistRows || 5), FC = +(opts.fistCell || 1.4), FH = FR * FC / 2;
      const E = c => (c.x === 0 || c.x === 2) + (c.y === 0 || c.y === FR - 1) + (c.z === 0 || c.z === 2);
      new Vox({
        cell: [FC, FC, FC], at: [-1.5 * FC, -FH, -1.5 * FC], maxLen: 1, gap: 0.036, bev: 0.08, salt: 55 + s,
        bevFor: c => (E(c) >= 2 ? 0.16 : 0),
        clip: c => (E(c) === 3 ? CUT.corner(c.x ? 1 : -1, c.y ? 1 : -1, c.z ? 1 : -1, 1.9) : null),
        studs: (c, f) => {
          if (c.cls === 'dark' || f === 3) return false;
          if (f === 4 || f === outF || f === 2) return 0.46;
          if (f === 5) return ((c.x + c.y) & 1) === 0 ? 0.46 : false;
          if (f === inF) return ((c.y + c.z) & 1) === 1 ? 0.44 : false;
          return false;
        }
      }).box(0, 3, 0, FR, 0, 3, (x, y) => (y === 0 ? 'dark' : 'fist')).build(fist.bufs.plastic);
      axial(fist.bufs.metal, CYL, 0, FH, 0, [0, 1, 0], 0.42, 0.3, LIN.GM);            // штифт к плечу
      p.fist = fist.finish();
      p.g.rotation.z = -s * ARM_POSE.tilt;
      p.finish(); root.add(p.g); parts[name] = p;
    }
    arm(-1); arm(1);

    /* ============================================================
       НОГИ: один наклонный блок (верх тёмный, дальше средне-серая мелкая кладка),
       3 × 4.2 × 3, ось бедра наружу; ось голеностопа проходит сквозь низ голени
       и наклонена вместе с ней (наружный болт выше внутреннего, как на листе)
       ============================================================ */
    const LEG_TILT = 0.2;
    function leg(s) {
      const name = s < 0 ? 'nogaL' : 'nogaR';
      const p = part(name, [s * 4.15, 8.6, 0]);
      new Vox({ cell: [0.85, 0.42, 0.75], at: [-1.7, -4.62, -1.5], maxLen: 3, merge: 0.35, salt: 65 + s,
        clip: c => (c.y === 10 && (c.z === 0 || c.z === 3) && (s < 0 ? c.x === 0 : c.x === 3) ? CUT.corner(s, 1, c.z ? 1 : -1, 1.5) : null)
      }).box(0, 4, 0, 11, 0, 4, (x, y) => (y >= 9 ? 'dark' : (y === 0 ? 'darkMid' : 'shin'))).build(p.bufs.plastic);
      bolt(p, s * 1.71, -0.9, 0, [s, 0, 0], 0.9);                                        // ось бедра
      axial(p.bufs.metal, CYL, -s * 2.75, -3.45, 0, [s, 0, 0], 0.17, 5.5, LIN.SV);        // ось голеностопа
      bolt(p, s * 2.75, -3.45, 0, [s, 0, 0], 1.05);
      bolt(p, -s * 2.75, -3.45, 0, [-s, 0, 0], 1.05);
      [-0.8, 0.8].forEach(z => magnet(p, 0, 0, z, [0, 1, 0]));
      p.g.rotation.z = s * LEG_TILT;
      p.finish(); root.add(p.g); parts[name] = p;
    }
    leg(-1); leg(1);

    /* ============================================================
       СТУПНИ 7 × 13 (ширина 5.8, центр ±5.2):
       основание 0…1.65 — по центру два высоких жёлто-оранжевых кирпича, по бокам стопки плит,
       средний ярус 1.65…2.45 — две плиты; верхний 2.45…3.5 — ряд кирпичей со скруглённым
       носком; боковые ступени 3.5…4.4 спереди; вилка голеностопа сзади; нижние болты.
       ============================================================ */
    function foot(s) {
      const name = s < 0 ? 'stupnyaL' : 'stupnyaR';
      const p = part(name);
      const P = p.bufs.plastic;
      const FL = +(opts.footLen || 13);                          // длина ступни в шипах
      const cx = s * 5.2, X0 = cx - 2.905, Z0 = -4.0;
      new Vox({ cell: [1.45, 1.45, S], at: [cx - 1.45, 0.2, Z0], maxLen: 1, salt: 71 + s })
        .box(0, 2, 0, 1, 0, FL, 'O4').build(P);
      [cx - 2.9, cx + 1.45].forEach((x, i) => new Vox({ cell: [0.725, 0.3625, S], at: [x, 0.2, Z0], maxLen: 4, merge: 0.5, salt: 72 + s + i })
        .box(0, 2, 0, 4, 0, FL, 'footBase')
        .del(c => (i === 0 ? c.x === 0 : c.x === 1) && c.y === 0 && (c.z === 0 || c.z === FL - 1))
        .build(P));
      new Vox({ cell: [S, 0.2, S], at: [cx - 2.4, 0, Z0 + 0.4], maxLen: 4, salt: 76 + s })        // утопленное дно
        .box(0, 6, 0, 1, 0, FL - 1, 'O5').build(P);
      new Vox({ cell: [0.83, 0.4, S], at: [X0, 1.65, Z0], maxLen: 4, merge: 0.55, salt: 73 + s })
        .box(0, 7, 0, 2, 0, FL, 'footMid')
        .del(c => c.y === 1 && (c.x === 0 || c.x === 6) && (c.z === 0 || c.z === FL - 1))
        .build(P);
      /* верхний ярус 2.45…3.6: четыре кирпича 1.45 во всю ширину, передняя кромка скруглена, на верху шипы */
      new Vox({
        cell: [1.45, 1.15, S], at: [X0, 2.45, Z0], maxLen: 1, salt: 74 + s,
        studs: (c, f) => f === 2 && c.z === FL - 3 ? 0.26 : false,
        clip: c => {
          if (c.z === FL - 2) return CUT.edge([0, 1, 0], [0, 0, 1], 1.3);
          if (c.z === 0) return CUT.edge([0, 1, 0], [0, 0, -1], 1.0);
          return null;
        }
      }).box(0, 4, 0, 1, 0, FL - 1, 'footTop').build(P);
      /* задний верхний ярус 3.5…4.1: плита-подкова вокруг гнезда голени, мелкие ступеньки с шипами */
      new Vox({
        cell: [0.83, 0.6, S], at: [X0, 3.6, Z0], maxLen: 2, merge: 0.3, salt: 75 + s,
        studs: (c, f) => f === 2 && (c.z === 1 || c.z === 6) && ((c.x + c.z) & 1) === 0 ? 0.22 : false,
        clip: c => {
          const sx = c.x === 1 ? -1 : (c.x === 5 ? 1 : 0);
          if (c.z === 6) return sx ? CUT.corner(sx, 1, 1, 1.35) : CUT.edge([0, 1, 0], [0, 0, 1], 0.85);
          if (c.z === 0) return sx ? CUT.corner(sx, 1, -1, 1.35) : CUT.edge([0, 1, 0], [0, 0, -1], 1.0);
          return null;
        }
      }).box(1, 6, 0, 1, 0, 7, 'footTop')
        .del(c => c.x >= 2 && c.x <= 4 && c.z >= 2 && c.z <= 5)
        .build(P);
      /* вилка голеностопа: тонкие стойки по бокам низа голени, наружная выше (ось наклонена) */
      const shinX = s * (4.15 + Math.sin(LEG_TILT) * 4.3);
      [[1, 3, 0.77], [-1, 2, 0.75]].forEach(([side, rows, h]) => {
        const x = shinX + side * s * 1.75 - (side * s < 0 ? 0.6 : 0);
        new Vox({ cell: [0.6, h, 0.8], at: [x, 4.1, -1.2], maxLen: 2, merge: 0.4, salt: 77 + s + side,
          clip: c => (c.y === rows - 1 && (c.z === 0 || c.z === 2) ? CUT.edge([0, 1, 0], [0, 0, c.z ? 1 : -1], 0.85) : null)
        }).box(0, 1, 0, rows, 0, 3, 'bracket').build(P);
      });
      bolt(p, cx + s * 2.905 + s * 0.01, 3.0, 2.2, [s, 0, 0], 1.05);                             // нижние болты
      bolt(p, cx - s * 2.905 - s * 0.01, 3.0, 2.2, [-s, 0, 0], 1.05);
      [-0.8, 0.8].forEach(z => magnet(p, shinX, 3.52, z, [0, 1, 0]));
      p.finish(); root.add(p.g); parts[name] = p;
    }
    foot(-1); foot(1);

    /* ---------- разлёт (разобранный вид) — снизу вверх, как на листе ---------- */
    const EXPLODE = {
      shapka: [0, 6.2, 0], ochki: [0, 5.3, 1.5], lico: [0, 0, 0], bokL: [-5.4, 0, 0], bokR: [5.4, 0, 0],
      golova: [0, 11.5, 0], sheya: [0, 9.0, 0], korpus: [0, 6.0, 0], rukaL: [-5.4, 6.0, 0], rukaR: [5.4, 6.0, 0],
      nogaL: [-1.0, 3.8, 0], nogaR: [1.0, 3.8, 0], stupnyaL: [-0.6, 0, 0], stupnyaR: [0.6, 0, 0]
    };
    parts.golova = { g: head, name: 'golova' };
    root.traverse(o => {
      o.userData.home = o.position.clone();
      o.userData.homeRot = o.rotation.clone();
      o.userData.homeScale = o.scale.clone();
      o.userData.homeVisible = o.visible;
    });

    return { root, parts, face: faceParts, MAT, EXPLODE, stats, HEAD_Y, PALETTE };
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
    Object.assign(key.shadow.camera, { left: -24, right: 24, top: 36, bottom: -18, near: 1, far: 100 });
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
    glowLight.position.set(0, 28.6 - model.HEAD_Y, 0);
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
      const legUp = smile ? 1 : 0;
      ['L', 'R'].forEach((side, i) => {
        const leg = F['mouthLeg' + side], cor = F['mouthCorner' + side];
        const lh = leg.userData.home, ch = cor.userData.home;
        ease(leg.position, 'y', lh.y + legUp * 1.0, k);
        ease(cor.position, 'y', ch.y + legUp * 0.6, k);
        ease(cor.position, 'x', ch.x + (smile ? (i ? 0.4 : -0.4) : 0), k);
        leg.visible = !talk && !think; cor.visible = !talk && !think;
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

  global.PixelMech = { create, studioEnvironment, lensEnvironment, stage, makeAnimator, exportScene, PALETTE };
})(typeof window !== 'undefined' ? window : globalThis);
