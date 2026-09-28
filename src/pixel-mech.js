/* ============================================================================
   PIXEL MECH — модульная коллекционная фигурка из кирпичиков.
   Генератор модели для three.js r128 (глобальный THREE).

   Как устроено:
   • Каждая деталь — воксельная сетка со своим шагом клетки (обычный кирпич —
     0.8 × h × 0.8, «макро»-блоки кулаков и груди — крупнее).
   • Видимые клетки «кирпичатся»: соседние клетки одного класса сливаются в
     1×2…1×6 с перевязкой рядов, у каждого кирпича фаска и свой оттенок.
   • За каждым кирпичом стоит тёмная «подложка» — сквозь швы видна глубина,
     а не небо (так выглядят настоящие швы между деталями).
   • Шипы (обычные и боковые) — точёные, со скруглённой кромкой.
   • Техник-детали, болты-оси, шея-штырь, очки — отдельная геометрия.
   Всё одной детали сливается в одну сетку с цветами вершин: быстро на
   телефоне и сразу готово к экспорту в glTF.

   Координаты: x — вправо (от зрителя), y — вверх, z — к зрителю (лицо).
   Единица: шаг шипа 0.8. Пол — y = 0.
   ========================================================================== */
(function (global) {
  'use strict';

  /* ---------- палитра (sRGB, снята с листа-образца) ---------- */
  const PALETTE = {
    WH: '#eceeec', LG: '#c3c7cc', MG: '#9ba0a8', DG: '#5f636b', DG2: '#4a4e55',
    GM: '#2f3237', BK: '#18191c', TR: '#232427',
    YE: '#f7d11a', YE2: '#f1c30f', YE3: '#fbdc45',
    O1: '#e57d25', O2: '#cf661b', O3: '#f0a232', O4: '#f6b547', O5: '#bb5716',
    PU: '#6d40d8', PU2: '#5a2fbf', PU3: '#8458e8',
    RD: '#9c0d08'
  };

  /* смеси: класс клетки → из чего выбирается цвет каждого кирпича */
  const MIX = {
    head: [['WH', 0.55], ['LG', 0.45]],
    headBack: [['LG', 0.5], ['WH', 0.25], ['MG', 0.25]],
    side: [['LG', 0.5], ['WH', 0.32], ['MG', 0.18]],
    yellow: [['YE', 0.72], ['YE2', 0.16], ['YE3', 0.12]],
    purple: [['PU', 0.7], ['PU2', 0.18], ['PU3', 0.12]],
    dark: [['DG', 0.72], ['DG2', 0.28]],
    darkMid: [['DG', 0.55], ['MG', 0.2], ['DG2', 0.25]],
    belt: [['MG', 0.7], ['DG', 0.3]],
    light: [['LG', 0.68], ['WH', 0.16], ['MG', 0.16]],
    fist: [['LG', 0.52], ['WH', 0.27], ['MG', 0.21]],
    shin: [['DG', 0.45], ['MG', 0.35], ['DG2', 0.2]],
    footBase: [['O4', 0.6], ['O3', 0.4]],
    footMid: [['O1', 0.62], ['O3', 0.22], ['O2', 0.16]],
    footTop: [['O2', 0.55], ['O1', 0.3], ['O5', 0.15]],
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
      const s = spread == null ? 0.05 : spread;
      c.setHSL(tmpHSL.h, tmpHSL.s, Math.max(0, Math.min(1, tmpHSL.l * (1 - s + rnd() * 2 * s))));
      return c;
    }
    function pick(cls) {
      if (!MIX[cls]) return cls;
      const x = rnd(); let s = 0;
      for (const [k, w] of MIX[cls]) { s += w; if (x < s) return k; }
      return MIX[cls][0][0];
    }

    /* ---------- сборщик сетки: позиции, нормали, цвета ---------- */
    class MeshBuf {
      constructor() { this.p = []; this.n = []; this.c = []; this.i = []; this.v = 0; }
      vert(x, y, z, nx, ny, nz, col) {
        this.p.push(x, y, z); this.n.push(nx, ny, nz); this.c.push(col.r, col.g, col.b);
        return this.v++;
      }
      tri(a, b, c) { this.i.push(a, b, c); }
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
    function bevelBox(mb, cx, cy, cz, hx, hy, hz, r, col, mask, m4) {
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
          mb.vert(v3.x, v3.y, v3.z, n3.x, n3.y, n3.z, col);
        }
        for (let j = 0; j < 3; j++) for (let i = 0; i < 3; i++) {
          const k = base + j * 4 + i;
          if (s > 0) { mb.tri(k, k + 1, k + 5); mb.tri(k, k + 5, k + 4); }
          else { mb.tri(k, k + 5, k + 1); mb.tri(k, k + 4, k + 5); }
        }
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
    function emitTpl(mb, tpl, m4, col) {
      const nm = new T.Matrix3().getNormalMatrix(m4), v = new T.Vector3(), n = new T.Vector3(), base = mb.v;
      for (let i = 0; i < tpl.pos.length; i += 3) {
        v.set(tpl.pos[i], tpl.pos[i + 1], tpl.pos[i + 2]).applyMatrix4(m4);
        n.set(tpl.nor[i], tpl.nor[i + 1], tpl.nor[i + 2]).applyMatrix3(nm).normalize();
        mb.vert(v.x, v.y, v.z, n.x, n.y, n.z, col);
      }
      for (const k of tpl.idx) mb.i.push(base + k);
    }
    /* шип: радиус 1, высота 0.71 — масштабируется под клетку */
    const STUD = latheTpl([
      [1, -0.06, 1, 0], [1, 0.56, 1, 0], [0.955, 0.67, 0.72, 0.7], [0.86, 0.71, 0.18, 0.98], [0, 0.71, 0, 1]
    ], 18);
    /* цилиндр с фаской (для осей, штифтов, шайб): радиус 1, высота 1, от 0 до 1 */
    function cylTpl(bev, seg) {
      const b = bev || 0.08;
      return latheTpl([
        [0, 0, 0, -1], [1 - b, 0, 0, -1], [1, b, 0.7, -0.7], [1, b, 1, 0],
        [1, 1 - b, 1, 0], [1, 1 - b, 0.7, 0.7], [1 - b, 1, 0, 1], [0, 1, 0, 1]
      ], seg || 24);
    }
    const CYL = cylTpl(0.1, 24), CYL_FINE = cylTpl(0.06, 40);
    /* трубка-втулка (техник-отверстие): внешний и внутренний радиус */
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
    function axial(mb, tpl, x, y, z, dir, rad, len, col) {
      qq.setFromUnitVectors(UP, vv.set(dir[0], dir[1], dir[2]).normalize());
      m4.compose(new T.Vector3(x, y, z), qq, ss.set(rad, len, rad));
      emitTpl(mb, tpl, m4, col);
    }

    let stats = { bricks: 0, studs: 0 };

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
        this.bev = o.bev == null ? 0.04 : o.bev;
        this.salt = o.salt || 1;
        this.studR = o.studR || 0.24 * Math.min(this.cell[0], this.cell[2]) / S;
        this.coreDark = o.coreDark == null ? 0.2 : o.coreDark;
        this.rowPhase = o.rowPhase || ((y, line) => y);
        this.bevFor = o.bevFor || null;     // своя фаска для клетки (скруглённые углы кулака)
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
      /* центр клетки в координатах детали */
      cx(x) { return this.at[0] + (x + 0.5) * this.cell[0]; }
      cy(y) { return this.at[1] + (y + 0.5) * this.cell[1]; }
      cz(z) { return this.at[2] + (z + 0.5) * this.cell[2]; }

      build(mb) {
        const [sx, sy, sz] = this.cell, g = this.gap;
        const claimed = new Set();
        const cells = [...this.m.values()].sort((a, b) => a.y - b.y || a.z - b.z || a.x - b.x);
        const masks = new Map();
        for (const v of cells) masks.set(this.K(v.x, v.y, v.z), this.openMask(v));
        const isBreak = (pos, row, line, dir) => {
          const ph = this.rowPhase(row, line) & 1;
          if (((pos + ph) & 1) !== 0) return false;              // перевязка: стыки через ряд со сдвигом
          return hash(pos * 7 + this.salt, row * 13 + (dir === 'x' ? 1 : 2), line * 31) > this.merge;
        };
        for (const v of cells) {
          const key = this.K(v.x, v.y, v.z);
          if (claimed.has(key)) continue;
          const mask = masks.get(key);
          if (!mask) continue;                                    // внутренность — не рисуем
          const fz = mask & 48, fx = mask & 3;
          let dir;
          if (fz && !fx) dir = 'x';
          else if (fx && !fz) dir = 'z';
          else if (fx && fz) dir = (v.y & 1) ? 'z' : 'x';
          else dir = ((v.y + v.x + v.z + this.salt) & 1) ? 'x' : 'z';
          const main = dir === 'x' ? fz : fx;
          /* длина кирпича: пока тот же класс, видимая та же грань и нет стыка */
          const run = [v];
          let mk = mask;
          for (let s = 1; s < this.maxLen; s++) {
            const nx = v.x + (dir === 'x' ? s : 0), nz = v.z + (dir === 'z' ? s : 0);
            const nk = this.K(nx, v.y, nz), n = this.m.get(nk);
            if (!n || n.cls !== v.cls || claimed.has(nk)) break;
            const nm = masks.get(nk);
            if (!nm || (main && !(nm & main))) break;
            if (isBreak(dir === 'x' ? nx : nz, v.y, dir === 'x' ? v.z : v.x, dir)) break;
            run.push(n); mk |= nm;
          }
          run.forEach(c => claimed.add(this.K(c.x, c.y, c.z)));
          const L = run.length, last = run[L - 1];
          const lx = dir === 'x' ? L : 1, lz = dir === 'z' ? L : 1;
          /* грани кирпича: торцы — от крайних клеток, остальные — если открыты хоть где-то */
          let fm = mk & (dir === 'x' ? ~3 : ~48);
          if (dir === 'x') fm |= (masks.get(this.K(v.x, v.y, v.z)) & 2) | (masks.get(this.K(last.x, last.y, last.z)) & 1);
          else fm |= (masks.get(this.K(v.x, v.y, v.z)) & 32) | (masks.get(this.K(last.x, last.y, last.z)) & 16);
          const col = tone(pick(v.cls));
          const x0 = this.at[0] + v.x * sx, y0 = this.at[1] + v.y * sy, z0 = this.at[2] + v.z * sz;
          const bev = (this.bevFor && L === 1 && this.bevFor(v)) || this.bev;
          bevelBox(mb, x0 + lx * sx / 2, y0 + sy / 2, z0 + lz * sz / 2,
            lx * sx / 2 - g / 2, sy / 2 - g / 2, lz * sz / 2 - g / 2, bev, col, fm);
          stats.bricks++;
          /* тёмная подложка шва за кирпичом */
          const dark = col.clone().multiplyScalar(this.coreDark);
          if (!opts.noCore) for (const c of run) this.core(mb, c, masks.get(this.K(c.x, c.y, c.z)), dark);
          /* шипы */
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
              emitTpl(mb, STUD, m4, col.clone().lerp(LIN.WH, 0.03));
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
            mb.vert(cx + p[0], cy + p[1], cz + p[2], FACE[f][0], FACE[f][1], FACE[f][2], col);
          }
          if (s > 0) { mb.tri(base, base + 1, base + 3); mb.tri(base, base + 3, base + 2); }
          else { mb.tri(base, base + 3, base + 1); mb.tri(base, base + 2, base + 3); }
        }
      }
    }

    /* ======================= МАТЕРИАЛЫ ======================= */
    const MAT = {
      plastic: new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.38, metalness: 0, envMapIntensity: 0.75 }),
      metal: new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.3, metalness: 0.7, envMapIntensity: 1.25 }),
      /* линза для экспорта (glTF): тонированный полупрозрачный пластик */
      glass: new T.MeshStandardMaterial({
        color: new T.Color('#e3160c').convertSRGBToLinear(), roughness: 0.05, metalness: 0.2,
        transparent: true, opacity: 0.86, envMapIntensity: 2.2
      }),
      /* линза на экране: тонировка умножением (жёлтое → красное, чёрные глаза видны) … */
      tint: new T.MeshBasicMaterial({
        color: new T.Color('#f01c0c').convertSRGBToLinear(), blending: T.MultiplyBlending, transparent: true,
        premultipliedAlpha: true, depthWrite: false, toneMapped: false
      }),
      /* … плюс зеркальные блики софтбоксов поверх */
      gloss: new T.MeshStandardMaterial({
        color: new T.Color('#ff5a3c').convertSRGBToLinear(), roughness: 0.07, metalness: 1,
        envMapIntensity: 0.34, blending: T.AdditiveBlending, transparent: true, depthWrite: false
      }),
      frame: new T.MeshStandardMaterial({ color: new T.Color('#a90f0a').convertSRGBToLinear(), roughness: 0.22, metalness: 0.25, envMapIntensity: 1.6 }),
      ink: new T.MeshStandardMaterial({ color: LIN.BK.clone(), roughness: 0.26, metalness: 0, envMapIntensity: 1.1 })
    };
    MAT.glass.name = 'sunglass_lens'; MAT.frame.name = 'sunglass_frame'; MAT.plastic.name = 'abs_plastic';
    MAT.metal.name = 'gunmetal'; MAT.ink.name = 'black_tile';

    /* деталь = группа с сетками по материалам */
    function part(name, pivot) {
      const g = new T.Group(); g.name = name;
      if (pivot) g.position.set(pivot[0], pivot[1], pivot[2]);
      const bufs = { plastic: new MeshBuf(), metal: new MeshBuf() };
      return {
        g, bufs, name,
        finish() {
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

    /* ---------- мелкие механические детали ---------- */
    const sgn = s => (s < 0 ? -1 : 1);
    /* болт-ось: шайба + стержень + головка со шлицем, наружу по dir */
    function bolt(p, x, y, z, dir, k) {
      k = k || 1;
      const d = dir, gm = LIN.GM, bk = LIN.BK;
      axial(p.bufs.metal, CYL, x, y, z, d, 0.36 * k, 0.07 * k, gm);
      axial(p.bufs.metal, CYL, x + d[0] * 0.06 * k, y + d[1] * 0.06 * k, z + d[2] * 0.06 * k, d, 0.2 * k, 0.42 * k, bk);
      axial(p.bufs.metal, CYL_FINE, x + d[0] * 0.46 * k, y + d[1] * 0.46 * k, z + d[2] * 0.46 * k, d, 0.31 * k, 0.2 * k, gm);
      /* шлиц */
      const q = new T.Quaternion().setFromUnitVectors(new T.Vector3(0, 0, 1), new T.Vector3(d[0], d[1], d[2]));
      const c = new T.Vector3(x + d[0] * 0.665 * k, y + d[1] * 0.665 * k, z + d[2] * 0.665 * k);
      m4.compose(c, q, ss.set(1, 1, 1));
      bevelBox(p.bufs.metal, 0, 0, 0, 0.23 * k, 0.035 * k, 0.03 * k, 0.01, bk, 63, m4);
    }
    /* техник-втулка: тёмное кольцо с отверстием */
    function socket(p, x, y, z, dir, col, rad) {
      rad = rad || 0.34;
      axial(p.bufs.plastic, TUBE, x, y, z, dir, rad, 0.14, col);
      axial(p.bufs.plastic, CYL, x, y, z, dir, rad * 0.56, 0.05, LIN.BK.clone().multiplyScalar(0.4));
    }
    /* техник-штифт: фланец + головка */
    function pin(p, x, y, z, dir, col, rad) {
      rad = rad || 0.24;
      axial(p.bufs.plastic, CYL, x, y, z, dir, rad * 1.35, 0.08, col);
      axial(p.bufs.plastic, CYL, x + dir[0] * 0.07, y + dir[1] * 0.07, z + dir[2] * 0.07, dir, rad, 0.3, col);
      axial(p.bufs.plastic, CYL, x + dir[0] * 0.36, y + dir[1] * 0.36, z + dir[2] * 0.36, dir, rad * 0.45, 0.02, col.clone().multiplyScalar(0.35));
    }

    const parts = {};
    const root = new T.Group(); root.name = 'PixelMech';

    /* ============================================================
       ГОЛОВА — узел поворота у шеи (0, 17.3, 0).
       Детали: lico (блок с лицевой панелью), bokL/bokR (боковые плиты),
       shapka (череп-шапка с пришельцем), ochki (очки).
       ============================================================ */
    const HEAD_Y = 17.3;
    const head = new T.Group(); head.name = 'golova'; head.position.set(0, HEAD_Y, 0); root.add(head);

    /* ---- лицевой блок: 12 × 15 рядов × 12, спереди 2 клетки жёлтые ---- */
    const lico = part('lico');
    const face = new Vox({ cell: [S, 0.48, S], at: [-4.8, 0.3, -4.8], maxLen: 6, merge: 0.62, salt: 3, gap: 0.02, bev: 0.035 });
    face.box(0, 12, 0, 15, 0, 12, (x, y, z) => (z >= 10 ? 'yellow' : (z <= 1 ? 'headBack' : 'head')));
    face.paint(v => (v.y === 0 && v.z < 10 ? 'dark' : null));
    face.build(lico.bufs.plastic);
    /* низ головы: тёмная пластина над шеей */
    new Vox({ cell: [S, 0.32, S], at: [-4.0, -0.02, -4.0], maxLen: 4, salt: 5 })
      .box(0, 10, 0, 1, 0, 10, 'dark').build(lico.bufs.plastic);
    lico.finish(); head.add(lico.g); parts.lico = lico;

    /* ---- живое лицо: чёрные плитки глаз и рта (отдельные — для мимики) ---- */
    const FZ = 4.8 - HEAD_Y * 0;                           // фронт лица (в координатах головы z = 4.8)
    const faceParts = {};
    function inkTile(name, w, h, x, y) {
      const mb = new MeshBuf();
      bevelBox(mb, 0, 0, 0, w / 2, h / 2, 0.06, 0.03, LIN.BK, 63 - 32);
      const mesh = new T.Mesh(mb.geometry(), MAT.ink);
      mesh.name = name; mesh.position.set(x, y - HEAD_Y, FZ - 0.005); mesh.castShadow = true;
      lico.g.add(mesh); faceParts[name] = mesh;
      mesh.userData.home = mesh.position.clone();
      return mesh;
    }
    inkTile('eyeL', 1.56, 1.68, -1.6, 22.14);
    inkTile('eyeR', 1.56, 1.68, 1.6, 22.14);
    inkTile('mouthBar', 3.96, 0.42, 0, 19.8);
    inkTile('mouthLegL', 0.4, 0.8, -1.78, 19.19);
    inkTile('mouthLegR', 0.4, 0.8, 1.78, 19.19);
    inkTile('mouthCornerL', 0.4, 0.4, -2.18, 20.2);
    inkTile('mouthCornerR', 0.4, 0.4, 2.18, 20.2);
    {                                                        // зубы для улыбки/речи
      const mb = new MeshBuf();
      bevelBox(mb, 0, 0, 0, 1.5, 0.14, 0.05, 0.03, LIN.WH, 63 - 32);
      const t = new T.Mesh(mb.geometry(), MAT.plastic);
      t.name = 'teeth'; t.position.set(0, 19.8 - HEAD_Y, FZ + 0.02); t.visible = false;
      lico.g.add(t); faceParts.teeth = t; t.userData.home = t.position.clone();
    }

    /* ---- боковые плиты: белый/серый/оранжевый, боковые шипы, техник ---- */
    function sidePlate(s) {
      const name = s < 0 ? 'bokL' : 'bokR';
      const p = part(name);
      const x0 = s < 0 ? -5.6 : 4.8;
      /* основная плита 1 × 9 × 11, клетки кубические → шипы вбок по сетке */
      const earZ = [0, 3], earY = [3, 7];
      const out = s < 0 ? 1 : 0;                           // индекс грани наружу: −x = 1, +x = 0
      const v = new Vox({
        cell: [S, S, S], at: [x0, 0.3, -4.8], maxLen: 3, merge: 0.5, salt: s < 0 ? 11 : 12,
        studs: (c, f) => {
          if (f !== out) return false;
          if (c.cls === 'O1' || c.cls === 'O2') return ((c.y + c.z) & 1) === 0;
          if (c.cls === 'side' || c.cls === 'WH') return c.z >= 3 && c.z <= 9 && c.y >= 1 && c.y <= 6 && ((c.y + c.z) & 1) === 1;
          return false;
        }
      });
      v.box(0, 1, 0, 9, 0, 11, (x, y, z) => {
        if (y === 0) return 'belt';
        if (y === 8 && z >= 8) return 'techDG';
        if (z >= earZ[0] && z < earZ[1] && y >= earY[0] && y < earY[1]) return 'O2';
        return 'side';
      });
      /* оранжевое «ухо» выступает наружу на клетку */
      const ex = s < 0 ? -1 : 1;
      v.box(ex, ex + 1, earY[0], earY[1], earZ[0], earZ[1], 'O1');
      v.del(c => c.x === ex && (c.y === earY[0] || c.y === earY[1] - 1) && (c.z === earZ[0]));   // скругление уха
      v.build(p.bufs.plastic);
      /* техник-детали снаружи */
      const xo = s < 0 ? -5.6 - 0.011 : 5.6 + 0.011, d = [s, 0, 0];
      const earOut = s < 0 ? -6.4 : 6.4;
      socket(p, xo, 24.4 - HEAD_Y, 2.0, d, LIN.DG);            // втулки верхнего техник-кирпича
      socket(p, xo, 24.4 - HEAD_Y, 3.6, d, LIN.DG);
      pin(p, xo, 23.2 - HEAD_Y, 0.8, d, LIN.BK.clone().multiplyScalar(1.4), 0.26);   // шарнир дужки очков
      pin(p, xo, 18.4 - HEAD_Y, 3.6, d, LIN.LG, 0.24);          // светлые штифты у низа
      pin(p, xo, 19.2 - HEAD_Y, 3.6, d, LIN.LG, 0.24);
      socket(p, earOut + s * 0.011, 21.6 - HEAD_Y, -2.4, d, LIN.DG, 0.3);   // втулка на ухе
      p.finish(); head.add(p.g); parts[name] = p;
    }
    sidePlate(-1); sidePlate(1);

    /* ---- шапка-череп: белая кладка с шипами, фиолетовый пришелец сверху ---- */
    const shapka = part('shapka');
    const ALIEN = [                                          // сзади → вперёд, 8 шипов шириной
      '.A....A.',
      '.XXXXXX.',
      'XXoXXoXX',
      'XXXXXXXX',
      'XXXXXXXX',
      'XX....XX'
    ];
    const alienAt = (x, z) => {                             // x: 0..13 (шапка), z: 0..11
      const i = z - 4, j = x - 3;
      if (i < 0 || i >= 6 || j < 0 || j >= 8) return '.';
      return ALIEN[i][j];
    };
    const cap = new Vox({
      cell: [S, S, S], at: [-5.6, 7.5, -4.8], maxLen: 4, merge: 0.4, salt: 21,
      studs: (c, f) => f === 2 && c.y === 1 && c.cls !== 'BK' && alienAt(c.x, c.z) === '.'
    });
    cap.box(0, 14, 0, 2, 0, 12, (x, y, z) => {
      if (y === 1 && alienAt(x, z) === 'o') return 'BK';
      if (z === 0) return 'headBack';
      return 'head';
    });
    cap.del(c => c.y === 1 && (c.x === 0 || c.x === 13) && (c.z === 0 || c.z === 11));
    cap.build(shapka.bufs.plastic);
    const alien = new Vox({
      cell: [S, 0.48, S], at: [-3.2, 9.1, -1.6], maxLen: 3, merge: 0.5, salt: 23, gap: 0.024,
      studs: (c, f) => f === 2 && c.y === 2
    });
    ALIEN.forEach((row, i) => [...row].forEach((ch, j) => {
      if (ch === 'X') { alien.set(j, 0, i, 'purple'); alien.set(j, 1, i, 'purple'); }
      if (ch === 'o') alien.set(j, 0, i, 'BK');                       // глазницы: утопленная чёрная плитка
      if (ch === 'A') { alien.set(j, 0, i, 'PU'); alien.set(j, 1, i, 'PU'); alien.set(j, 2, i, 'PU'); }
    }));
    alien.build(shapka.bufs.plastic);
    shapka.finish(); head.add(shapka.g); parts.shapka = shapka;

    /* ---- ОЧКИ: зеркальный красный щиток с переносицей, оправа, дужки ---- */
    const ochki = new T.Group(); ochki.name = 'ochki'; head.add(ochki);
    {
      const A = 5.93, ZF = 5.22, R = 0.95, ZB = 1.9;        // полуширина, фронт, радиус угла, конец линзы
      const YT = 24.12 - HEAD_Y, YB = 20.86 - HEAD_Y, YN = 21.78 - HEAD_Y, YS = 23.45 - HEAD_Y;
      const Lf = A - R, La = R * Math.PI / 2, Ls = ZF - R - ZB, Lt = Lf + La + Ls;
      const sm = (a, b, x) => { const t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
      /* точка пути по дуговой координате u (0 — центр), со знаком стороны */
      function at(u) {
        const s = u < 0 ? -1 : 1, a = Math.abs(u);
        if (a <= Lf) return { x: s * a, z: ZF, nx: 0, nz: 1 };
        if (a <= Lf + La) {
          const t = (a - Lf) / R;                        // угол 0..π/2
          return { x: s * (Lf + R * Math.sin(t)), z: ZF - R + R * Math.cos(t), nx: s * Math.sin(t), nz: Math.cos(t) };
        }
        return { x: s * A, z: ZF - R - (a - Lf - La), nx: s, nz: 0 };
      }
      function bottom(u) {
        const a = Math.abs(u);
        let y = YB;
        y += (YN - YB) * (1 - sm(0.5, 1.35, a));               // переносица
        y += 0.38 * sm(3.2, Lf + La * 0.8, a);                  // скругление к вискам
        y += (YS - YB - 0.38) * sm(Lf + La * 0.6, Lt, a);       // бок сужается к дужке
        return y;
      }
      const top = u => YT - 0.05 * sm(Lf + La, Lt, Math.abs(u));
      const N = 150, M = 12, TH = 0.055;
      /* щиток выпуклый и по вертикали: верх смотрит чуть вверх и ловит верхний свет */
      const YM = (YT + YB) / 2 + 0.2, HH = (YT - YB) / 2, BUL = 0.24;
      const bulge = y => -BUL * Math.pow((y - YM) / HH, 2);
      const bulgeD = y => -2 * BUL * (y - YM) / (HH * HH);
      const lens = new MeshBuf(), rim = new MeshBuf();
      const col = new T.Color(1, 1, 1);
      /* внешняя и внутренняя поверхности */
      for (const side of [1, -1]) {
        const base = lens.v;
        for (let i = 0; i <= N; i++) {
          const u = -Lt + 2 * Lt * i / N, p = at(u), b = bottom(u), t = top(u);
          for (let j = 0; j <= M; j++) {
            const y = b + (t - b) * j / M, o = TH * side + bulge(y), d = -bulgeD(y), l = Math.hypot(1, d);
            lens.vert(p.x + p.nx * o, y, p.z + p.nz * o, p.nx * side / l, d * side / l, p.nz * side / l, col);
          }
        }
        for (let i = 0; i < N; i++) for (let j = 0; j < M; j++) {
          const a = base + i * (M + 1) + j, b2 = a + M + 1;
          if (side > 0) { lens.tri(a, b2, a + 1); lens.tri(b2, b2 + 1, a + 1); }
          else { lens.tri(a, a + 1, b2); lens.tri(b2, a + 1, b2 + 1); }
        }
      }
      /* нижняя кромка линзы (толщина) */
      {
        const base = lens.v;
        for (let i = 0; i <= N; i++) {
          const u = -Lt + 2 * Lt * i / N, p = at(u), b = bottom(u);
          const o = bulge(b);
          lens.vert(p.x + p.nx * (TH + o), b, p.z + p.nz * (TH + o), 0, -1, 0, col);
          lens.vert(p.x - p.nx * (TH - o), b, p.z - p.nz * (TH - o), 0, -1, 0, col);
        }
        for (let i = 0; i < N; i++) { const a = base + i * 2; lens.tri(a, a + 1, a + 2); lens.tri(a + 1, a + 3, a + 2); }
      }
      const lensGeo = lens.geometry();
      const lensMesh = new T.Mesh(lensGeo, MAT.tint);
      lensMesh.name = 'ochki_linza'; lensMesh.renderOrder = 2; ochki.add(lensMesh);
      const glossMesh = new T.Mesh(lensGeo, MAT.gloss);
      glossMesh.name = 'ochki_blik'; glossMesh.renderOrder = 3; lensMesh.add(glossMesh);
      /* верхняя оправа-бровь по всей дуге */
      const RW = 0.11, RH = 0.2;
      {
        const base = rim.v, prof = [[-RW, -RH], [RW, -RH], [RW, 0.02], [RW * 0.6, 0.06], [-RW * 0.6, 0.06], [-RW, 0.02], [-RW, -RH]];
        const nrm = [[-1, 0], [1, 0], [1, 0], [0.5, 0.86], [-0.5, 0.86], [-1, 0], [-1, 0]];
        const P = prof.length;
        for (let i = 0; i <= N; i++) {
          const u = -Lt + 2 * Lt * i / N, p = at(u), t = top(u);
          const bo = bulge(t);
          prof.forEach(([o, dy], k) => {
            rim.vert(p.x + p.nx * (o + bo), t + dy, p.z + p.nz * (o + bo), p.nx * nrm[k][0], nrm[k][1], p.nz * nrm[k][0], col);
          });
        }
        for (let i = 0; i < N; i++) for (let k = 0; k < P - 1; k++) {
          const a = base + i * P + k, b2 = a + P;
          rim.tri(a, b2, a + 1); rim.tri(b2, b2 + 1, a + 1);
        }
      }
      /* дужки: от конца линзы назад к шарниру на боковой плите, с загибом */
      [-1, 1].forEach(s => {
        const ax = s * (A + 0.02), yT = YT - 0.1, h = 0.46;
        bevelBox(rim, ax, yT - h / 2, (ZB + 0.25 + 0.1) / 2 + 0.05, 0.1, h / 2, (ZB + 0.25 - 0.1) / 2 + 0.05, 0.04, col, 63);
        bevelBox(rim, ax, yT - 0.56, 0.18, 0.1, 0.3, 0.2, 0.05, col, 63);            // загиб к шарниру
        bevelBox(rim, ax, yT - 0.2, ZB + 0.1, 0.16, 0.32, 0.26, 0.05, col, 63);      // петля у линзы
        axial(rim, CYL, ax + s * 0.15, yT - 0.2, ZB + 0.1, [s, 0, 0], 0.1, 0.05, col.clone().multiplyScalar(0.5));
      });
      const rimMesh = new T.Mesh(rim.geometry(), MAT.frame);
      rimMesh.name = 'ochki_oprava'; rimMesh.castShadow = true; ochki.add(rimMesh);
    }
    parts.ochki = { g: ochki, name: 'ochki' };

    /* ============================================================
       ШЕЯ-ШТЫРЬ: воронёный металл, воротник с винтами
       ============================================================ */
    const sheya = part('sheya', [0, 16.3, 0]);
    {
      const mb = sheya.bufs.metal, gm = LIN.GM, dk = LIN.BK;
      axial(mb, CYL_FINE, 0, -1.55, 0.1, [0, 1, 0], 0.86, 0.2, gm);          // нижний поясок
      axial(mb, CYL_FINE, 0, -1.4, 0.1, [0, 1, 0], 1.02, 1.85, gm);          // тело штыря
      axial(mb, CYL_FINE, 0, -0.35, 0.1, [0, 1, 0], 1.06, 0.06, dk);         // канавка
      axial(mb, CYL_FINE, 0, 0.42, 0.1, [0, 1, 0], 1.44, 0.6, gm);           // воротник под головой
      axial(mb, CYL_FINE, 0, 0.36, 0.1, [0, 1, 0], 1.3, 0.07, dk);
      [-0.95, 0, 0.95, 2.4, -2.4].forEach(a => {                              // винты с шестигранником
        const nx = Math.sin(a), nz = Math.cos(a);
        axial(mb, CYL, nx * 1.4, 0.72, 0.1 + nz * 1.4, [nx, 0, nz], 0.17, 0.07, gm.clone().multiplyScalar(1.5));
        axial(mb, cylTpl(0.02, 6), nx * 1.43, 0.72, 0.1 + nz * 1.43, [nx, 0, nz], 0.08, 0.05, dk.clone().multiplyScalar(0.3));
      });
    }
    sheya.finish(); root.add(sheya.g); parts.sheya = sheya;

    /* ============================================================
       КОРПУС: таз, пояс, грудь из макро-блоков с крупными шипами,
       верхний пояс с шипами, плечевые колодки
       ============================================================ */
    const korpus = part('korpus');
    {
      const P = korpus.bufs.plastic;
      /* таз: 10 × 4 × 8 */
      new Vox({ cell: [S, 0.64, S], at: [-4.0, 7.6, -3.2], maxLen: 4, merge: 0.5, salt: 31 })
        .box(0, 10, 0, 4, 0, 8, 'dark')
        .paint(v => (v.y === 3 && v.z === 7 && v.x >= 3 && v.x <= 6 ? 'darkMid' : null))
        .del(v => v.y === 0 && (v.x === 0 || v.x === 9) && (v.z === 0 || v.z === 7))
        .build(P);
      socket(korpus, -2.8, 9.0, 3.211, [0, 0, 1], LIN.DG2, 0.36);
      socket(korpus, 2.8, 9.0, 3.211, [0, 0, 1], LIN.DG2, 0.36);
      socket(korpus, -4.011, 9.0, 0, [-1, 0, 0], LIN.DG2, 0.32);
      socket(korpus, 4.011, 9.0, 0, [1, 0, 0], LIN.DG2, 0.32);
      /* пояс */
      new Vox({ cell: [S, 0.96, S], at: [-4.8, 10.16, -3.2], maxLen: 4, merge: 0.5, salt: 33 })
        .box(0, 12, 0, 1, 0, 8, 'belt').build(P);
      /* грудь: 6 × 2 × 4 макро-блока 1.6 */
      const chest = new Vox({
        cell: [1.6, 1.6, 1.6], at: [-4.8, 11.12, -3.2], maxLen: 1, gap: 0.03, bev: 0.06, salt: 35,
        studR: 0.52,
        studs: (c, f) => {
          if (c.cls === 'WH') return false;
          if (f === 4) return true;                                   // перёд: 8 крупных шипов
          if (f === 0 || f === 1) return c.z === 1 || c.z === 2;      // бока
          if (f === 5) return c.x === 1 || c.x === 4;                 // спина
          return false;
        }
      });
      chest.box(0, 6, 0, 2, 0, 4, (x, y, z) => (z === 3 && (x === 2 || x === 3) ? 'WH' : (z === 3 ? 'LG' : 'light')));
      chest.build(P);
      /* верхний пояс груди: 12 × 2 × 8, сверху шипы вокруг гнезда шеи */
      const band = new Vox({
        cell: [S, S, S], at: [-4.8, 14.32, -3.2], maxLen: 4, merge: 0.45, salt: 37,
        studs: (c, f) => {
          if (f !== 2 || c.y !== 1) return false;
          const x = -4.8 + (c.x + 0.5) * S, z = -3.2 + (c.z + 0.5) * S;
          return Math.hypot(x, z - 0.1) > 1.9;
        }
      });
      band.box(0, 12, 0, 2, 0, 8, (x, y, z) => {
        if (z === 7 && x >= 4 && x < 8 && y === 0) return 'WH';
        if (y === 1 && (x === 0 || x === 11)) return 'dark';
        if (z === 0) return 'headBack';
        return 'light';
      });
      band.del(v => v.y === 1 && (v.x === 0 || v.x === 11) && (v.z === 0 || v.z === 7));
      band.build(P);
      /* гнездо шеи: тёмная пластина */
      new Vox({ cell: [S, 0.32, S], at: [-1.6, 15.92, -1.5], maxLen: 4, salt: 39,
        studs: (c, f) => f === 2 && (c.x === 0 || c.x === 3) && (c.z === 0 || c.z === 3) })
        .box(0, 4, 0, 1, 0, 4, 'dark').build(P);
      /* плечевые колодки */
      [-1, 1].forEach(s => {
        new Vox({ cell: [S, S, S], at: [s < 0 ? -5.6 : 4.8, 12.72, -2.0], maxLen: 3, salt: 41 + s,
          studs: (c, f) => (f === (s < 0 ? 1 : 0)) && ((c.y + c.z) & 1) === 0 })
          .box(0, 1, 0, 4, 0, 5, 'dark').build(P);
      });
    }
    korpus.finish(); root.add(korpus.g); parts.korpus = korpus;

    /* ============================================================
       РУКИ: тёмное плечо + угловатый кулак из макро-блоков
       ============================================================ */
    function arm(s) {
      const name = s < 0 ? 'rukaL' : 'rukaR';
      const p = part(name, [s * 5.4, 15.3, 0]);
      const P = p.bufs.plastic;
      /* плечо: 4 × 6 × 4 */
      const up = new Vox({ cell: [S, S, S], at: [s < 0 ? -2.8 : 0.4, -2.4, -1.6], maxLen: 3, merge: 0.5, salt: 51 + s,
        studs: (c, f) => f === 2 && c.y === 3 });
      up.box(0, 3, 0, 4, 0, 4, (x, y, z) => (y === 3 && (s < 0 ? x === 0 : x === 2) ? 'darkMid' : 'dark'));
      up.del(c => (c.y === 3 && (s < 0 ? c.x === 0 : c.x === 2) && (c.z === 0 || c.z === 3)));
      up.build(P);
      bolt(p, s * 1.2, 0.8, 0, [0, 1, 0], 0.9);                       // шарнир-ось сверху плеча
      bolt(p, s * 2.8, -0.8, 0, [s, 0, 0], 0.8);                       // ось плеча наружу
      /* кулак: 4 × 4 × 4 макро-блока 1.3, углы срезаны */
      const fist = new T.Group(); fist.name = name + '_kulak';
      fist.position.set(s * 4.3, -4.55, 0.5);
      fist.rotation.set(0.32, -s * 0.52, -s * 0.2);
      const fb = new MeshBuf();
      const outF = s < 0 ? 1 : 0;
      const fv = new Vox({
        cell: [1.42, 1.42, 1.42], at: [-2.84, -2.84, -2.84], maxLen: 1, gap: 0.036, bev: 0.075, salt: 55 + s,
        studR: 0.4,
        bevFor: c => {
          const e = (c.x === 0 || c.x === 3) + (c.y === 0 || c.y === 3) + (c.z === 0 || c.z === 3);
          return e === 3 ? 0.4 : (e === 2 ? 0.14 : 0);
        },
        studs: (c, f) => {
          if (c.cls === 'dark') return false;
          if ((c.x === 0 || c.x === 3) + (c.y === 0 || c.y === 3) + (c.z === 0 || c.z === 3) === 3) return false;
          if (f === 3) return false;
          if (f === 2 || f === 4 || f === outF || f === 5) return ((c.x + c.y + c.z + (s < 0 ? 1 : 0)) & 1) === 0;
          return ((c.x + c.y + c.z) & 1) === 1 && c.y >= 2;
        }
      });
      fv.box(0, 4, 0, 4, 0, 4, (x, y) => (y === 0 ? 'dark' : 'fist'));
      fv.build(fb);
      const fm = new T.Mesh(fb.geometry(), MAT.plastic); fm.name = name + '_kulak_plastic';
      fm.castShadow = fm.receiveShadow = true; fist.add(fm);
      /* костяшки: тёмные пластины снизу-спереди и штифт запястья */
      const fbm = new MeshBuf();
      axial(fbm, CYL, 0, -2.84, 0, [0, -1, 0], 0.5, 0.3, LIN.GM);
      const fmm = new T.Mesh(fbm.geometry(), MAT.metal); fmm.name = name + '_kulak_metal'; fmm.castShadow = true; fist.add(fmm);
      p.fist = fist;
      p.finish(); p.g.add(fist); root.add(p.g); parts[name] = p;
    }
    arm(-1); arm(1);

    /* ============================================================
       НОГИ: бедро + голень под наклоном, чёрные оси в суставах
       ============================================================ */
    function leg(s) {
      const name = s < 0 ? 'nogaL' : 'nogaR';
      const p = part(name, [s * 3.2, 8.8, 0]);
      const P = p.bufs.plastic;
      /* бедро 4 × 4 × 4 — верх уходит под таз */
      new Vox({ cell: [S, S, S], at: [-1.6, -3.2, -1.6], maxLen: 4, merge: 0.5, salt: 61 + s })
        .box(0, 4, 0, 4, 0, 4, (x, y, z) => (y === 0 && z === 3 ? 'darkMid' : 'dark'))
        .del(c => c.y === 0 && (c.z === 0 || c.z === 3) && (s < 0 ? c.x === 3 : c.x === 0))
        .build(P);
      bolt(p, -s * 1.6, -1.2, 0, [-s, 0, 0], 0.9);                     // ось бедра внутрь (в таз)
      bolt(p, s * 1.6, -1.6, 0, [s, 0, 0], 0.9);                       // ось бедра наружу
      /* голень — своя группа с поворотом в колене */
      const shinG = new T.Group(); shinG.name = name + '_golen';
      shinG.position.set(s * 1.0, -2.8, -0.2);
      shinG.rotation.z = s * 0.2;
      const sb = { plastic: new MeshBuf(), metal: new MeshBuf() };
      new Vox({ cell: [S, 0.64, S], at: [-1.6, -3.2, -1.6], maxLen: 4, merge: 0.4, salt: 65 + s })
        .box(0, 4, 0, 5, 0, 4, (x, y, z) => (z === 3 && (x === 1 || x === 2) && y >= 1 && y <= 3 ? 'MG' : 'shin'))
        .build(sb.plastic);
      for (const k in sb) {
        if (sb[k].empty) continue;
        const mm = new T.Mesh(sb[k].geometry(), MAT[k]); mm.name = name + '_golen_' + k;
        mm.castShadow = mm.receiveShadow = true; shinG.add(mm);
      }
      p.finish(); p.g.add(shinG); root.add(p.g); parts[name] = p;
    }
    leg(-1); leg(1);

    /* ============================================================
       СТУПНИ: ступенчатая кладка трёх оттенков оранжевого,
       кронштейн-вилка под голень и болты-оси
       ============================================================ */
    function foot(s) {
      const name = s < 0 ? 'stupnyaL' : 'stupnyaR';
      const p = part(name);
      const v = new Vox({
        cell: [S, 0.64, S], at: [s < 0 ? -9.2 : 1.2, 0, -4.0], maxLen: 4, merge: 0.5, salt: 71 + s,
        studs: (c, f) => f === 2 && c.y >= 4 && ((c.x + c.z) & 1) === 0 && c.cls !== 'footBase'
      });
      const inner = s < 0 ? [7, 9] : [1, 3], outer = s < 0 ? [1, 3] : [7, 9];
      for (let y = 0; y < 10; y++) {
        let x0 = 0, x1 = 10, z1 = 13;
        let cls = y < 3 ? 'footBase' : (y < 5 ? 'footMid' : 'footTop');
        if (y === 3) z1 = 12;
        if (y === 4) z1 = 11;
        if (y === 5) { x0 = 1; x1 = 9; z1 = 8; }
        if (y === 6) { x0 = 1; x1 = 9; z1 = 6; }
        if (y >= 7) {
          const zz0 = y === 9 ? 2 : 1, zz1 = y === 9 ? 6 : 7;
          v.box(inner[0], inner[1], y, y + 1, zz0, zz1, y === 9 ? 'footMid' : 'footTop');
          v.box(outer[0], outer[1], y, y + 1, zz0, zz1, y === 9 ? 'footMid' : 'footTop');
          continue;
        }
        v.box(x0, x1, y, y + 1, 0, z1, cls);
        if (y === 5 || y === 6) v.del(c => c.y === y && c.x >= 3 && c.x < 7 && c.z >= 1 && c.z < 7);   // гнездо под голень
      }
      v.del(c => c.y <= 2 && (c.x === 0 || c.x === 9) && (c.z === 0 || c.z === 12));    // скруглить углы
      v.del(c => c.y === 4 && (c.x === 0 || c.x === 9) && c.z === 10);
      v.build(p.bufs.plastic);
      /* болты: ось голеностопа сквозь вилку и боковые на подошве */
      const cx = s * 5.2;
      bolt(p, cx + s * 1.6 + s * 0.01, 5.44, -0.4, [s, 0, 0]);
      bolt(p, cx - s * 1.6 - s * 0.01, 5.44, -0.4, [-s, 0, 0]);
      bolt(p, s * 9.2 + s * 0.01, 2.24, 2.4, [s, 0, 0]);
      bolt(p, s * 1.2 - s * 0.01, 2.24, 2.4, [-s, 0, 0]);
      p.finish(); root.add(p.g); parts[name] = p;
    }
    foot(-1); foot(1);

    /* ---------- разлёт (разобранный вид) — как на листе ---------- */
    /* ступни стоят на полу, всё остальное поднимается: снизу вверх как на листе */
    const EXPLODE = {
      shapka: [0, 6.4, 0], ochki: [0, 5.4, 1.5], lico: [0, 0, 0], bokL: [-5.4, 0, 0], bokR: [5.4, 0, 0],
      golova: [0, 11.5, 0], sheya: [0, 9.0, 0], korpus: [0, 6.0, 0], rukaL: [-5.2, 6.0, 0], rukaR: [5.2, 6.0, 0],
      nogaL: [-1.0, 3.6, 0], nogaR: [1.0, 3.6, 0], stupnyaL: [-0.6, 0, 0], stupnyaR: [0.6, 0, 0]
    };
    parts.golova = { g: head, name: 'golova' };
    /* «домашняя» поза каждого узла — к ней возвращают разлёт, мимика и экспорт */
    root.traverse(o => {
      o.userData.home = o.position.clone();
      o.userData.homeRot = o.rotation.clone();
      o.userData.homeScale = o.scale.clone();
      o.userData.homeVisible = o.visible;
    });

    return { root, parts, face: faceParts, MAT, EXPLODE, stats, HEAD_Y, PALETTE };
  }

  /* ======================= СТУДИЯ: свет, отражения, пол ======================= */
  /* Окружение — тёмная комната с софтбоксами: пластик получает мягкие блики
     по рёбрам, зеркальные очки — вертикальные полосы, как на листе. */
  function studioEnvironment(T, renderer) {
    const sc = new T.Scene();
    const box = new T.BoxGeometry(1, 1, 1);
    const room = new T.Mesh(box, new T.MeshBasicMaterial({ color: 0x39414b, side: T.BackSide }));
    room.scale.set(60, 40, 60); room.position.y = 10; sc.add(room);
    const floor = new T.Mesh(box, new T.MeshBasicMaterial({ color: 0x1d2228 }));
    floor.scale.set(60, 0.2, 60); floor.position.y = -9.8; sc.add(floor);
    const soft = (w, h, x, y, z, k) => {
      const m = new T.Mesh(new T.PlaneGeometry(w, h), new T.MeshBasicMaterial({ color: new T.Color(k, k, k), side: T.DoubleSide }));
      m.position.set(x, y, z); m.lookAt(0, 4, 0); sc.add(m);
    };
    soft(4, 44, -15, 4, 18, 3.2);          // главный софтбокс слева-спереди: вертикальный блик
    soft(2.2, 44, -8, 4, 22, 2.4);         // второй узкий — двойная полоса на очках
    soft(3, 40, 14, 4, 20, 1.6);           // заполняющий справа
    soft(24, 5, 0, 26, 4, 2.6);            // верхний
    soft(5, 16, 4, 8, -24, 1.8);           // контровой
    const pm = new T.PMREMGenerator(renderer);
    const tex = pm.fromScene(sc, 0.03).texture;
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
    const hemi = new T.HemisphereLight(0xffffff, 0x7d8a99, 0.34); scene.add(hemi);
    const key = new T.DirectionalLight(0xfffaf3, 0.82);
    key.position.set(-11, 23, 25); key.castShadow = true;
    const sm = o.shadowMap || 2048;
    key.shadow.mapSize.set(sm, sm);
    Object.assign(key.shadow.camera, { left: -24, right: 24, top: 36, bottom: -18, near: 1, far: 100 });
    key.shadow.bias = -0.0004; key.shadow.normalBias = 0.03;
    scene.add(key);
    const rim = new T.DirectionalLight(0xdce9ff, 0.4); rim.position.set(15, 14, -18); scene.add(rim);
    const fill = new T.DirectionalLight(0xe9f1ff, 0.16); fill.position.set(20, 3, 10); scene.add(fill);
    const floor = new T.Mesh(new T.PlaneGeometry(240, 240), new T.ShadowMaterial({ opacity: o.shadow == null ? 0.2 : o.shadow }));
    floor.rotation.x = -Math.PI / 2; floor.receiveShadow = true; floor.name = 'pol'; scene.add(floor);
    return { hemi, key, rim, fill, floor };
  }

  /* ======================= АНИМАЦИЯ СОСТОЯНИЙ ======================= */
  function makeAnimator(T, model) {
    const P = model.parts, F = model.face;
    const st = { explode: 0, explodeTarget: 0, glasses: 1, glassesTarget: 1, blinkAt: 2400, state: 'тихо', glow: 0 };
    const lerpK = (a, dtK) => 1 - Math.pow(1 - a, dtK);
    const SIDE_C = { bokL: new T.Vector3(-5.6, 0, -0.4), bokR: new T.Vector3(5.6, 0, -0.4) };
    const tmpV = new T.Vector3(), Y_AX = new T.Vector3(0, 1, 0);
    const glowLight = new T.PointLight(0x9a6bff, 0, 9, 2);
    glowLight.position.set(0, 28.6 - model.HEAD_Y, 0);
    P.golova.g.add(glowLight);
    function ease(obj, key, target, k) { obj[key] += (target - obj[key]) * k; }
    function update(t, dtK) {
      const k = lerpK(0.14, dtK), s = st.state;
      const talk = s === 'говорит', think = s === 'думает', smile = s === 'улыбка', listen = s === 'слушает';
      /* моргание */
      if (t > st.blinkAt + 150) st.blinkAt = t + 2200 + Math.random() * 3200;
      const blink = st.blinkAt - t < 150 && st.blinkAt - t > 0;
      const eyeH = blink ? 0.1 : (listen ? 1.12 : 1);
      ease(F.eyeL.scale, 'y', eyeH, blink ? 1 : k); F.eyeR.scale.y = F.eyeL.scale.y;
      const look = think ? 0.35 : 0, lookY = think ? 0.3 : 0;
      ease(F.eyeL.position, 'x', F.eyeL.userData.home.x + look, k);
      ease(F.eyeR.position, 'x', F.eyeR.userData.home.x + look, k);
      ease(F.eyeL.position, 'y', F.eyeL.userData.home.y + lookY, k); F.eyeR.position.y = F.eyeL.position.y;
      /* рот: 8-битные пиксели */
      const H = F.mouthBar.userData.home;
      const open = talk ? (Math.sin(t / 90) > -0.2 ? 2.6 + Math.sin(t / 47) * 0.6 : 1.2) : 1;
      ease(F.mouthBar.scale, 'y', open, talk ? 1 : k);
      ease(F.mouthBar.scale, 'x', think ? 0.55 : (smile ? 1 : 1), k);
      ease(F.mouthBar.position, 'x', H.x + (think ? 0.7 : 0), k);
      const legUp = smile ? 1 : 0;
      ['L', 'R'].forEach((side, i) => {
        const leg = F['mouthLeg' + side], cor = F['mouthCorner' + side];
        const lh = leg.userData.home, ch = cor.userData.home;
        ease(leg.position, 'y', lh.y + legUp * 1.02, k);
        ease(cor.position, 'y', ch.y + legUp * 0.62, k);
        ease(cor.position, 'x', ch.x + (smile ? (i ? 0.4 : -0.4) : 0), k);
        leg.visible = !talk && !think; cor.visible = !talk && !think;
      });
      F.teeth.visible = smile || (talk && open > 2);
      F.teeth.scale.x = talk ? 0.8 : 1;
      F.teeth.position.y = H.y + (smile ? 0.0 : 0.08);
      /* очки: думает — на лоб; сняты — парят перед головой */
      st.glasses += (st.glassesTarget - st.glasses) * lerpK(0.1, dtK);
      const o = P.ochki.g, oh = o.userData.home, up = think ? 1 : 0;
      const ex = st.explode * model.EXPLODE.ochki[1], ez = st.explode * model.EXPLODE.ochki[2];
      ease(o.position, 'y', oh.y + up * 2.1 * st.glasses + (1 - st.glasses) * 7.2 + ex, k);
      ease(o.position, 'z', oh.z + up * -0.5 * st.glasses + (1 - st.glasses) * 5 + ez, k);
      ease(o.rotation, 'x', -up * 0.38 * st.glasses - (1 - st.glasses) * 0.12, k);
      if (st.glassesTarget === 0) o.rotation.y = Math.sin(t / 1300) * 0.35;
      else o.rotation.y *= Math.pow(0.9, dtK);
      /* голова: кивок в речи, наклон в раздумье/слушании */
      const hg = P.golova.g;
      ease(hg.rotation, 'x', talk ? Math.sin(t / 150) * 0.035 : (listen ? 0.05 : 0), k);
      ease(hg.rotation, 'z', think ? 0.07 : (listen ? -0.06 : 0), k);
      /* пришелец светится, когда слушает */
      st.glow += ((listen ? 0.6 + 0.4 * Math.sin(t / 300) : 0) - st.glow) * k;
      glowLight.intensity = st.glow * 2.4;
      /* дыхание, покачивание рук */
      P.rukaL.g.rotation.x = Math.sin(t / 1700) * 0.04;
      P.rukaR.g.rotation.x = Math.sin(t / 1700 + 1.9) * 0.04;
      P.rukaL.g.rotation.z = (smile ? -0.08 : 0) + Math.sin(t / 2100) * 0.012;
      P.rukaR.g.rotation.z = (smile ? 0.08 : 0) - Math.sin(t / 2100) * 0.012;
      /* разлёт */
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
    /* сбросить разлёт и мимику к «домашнему» виду */
    clone.traverse(o => {
      const u = o.userData;
      if (!u || !u.home) return;
      o.position.copy(u.home); o.rotation.copy(u.homeRot); o.scale.copy(u.homeScale); o.visible = u.homeVisible;
    });
    sc.add(clone);
    return sc;
  }

  global.PixelMech = { create, studioEnvironment, stage, makeAnimator, exportScene, PALETTE };
})(typeof window !== 'undefined' ? window : globalThis);
