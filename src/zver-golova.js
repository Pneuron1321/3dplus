/* ============================================================================
   ГОЛОВА «ЗВЕРЯ» — органическая лепка по листу Александра (зелёный громила, HEAD DETAIL: FRONT, SIDE,
   MOUTH & TEETH, CREST / SPINES). Не кирпичи: кожа — неявная поверхность (поле расстояний), слитая из
   анатомических форм плавным объединением, полость пасти вырезана; сетка — «surface nets» по сетке поля,
   нормали — градиент поля. Зубы, глаза и шипы гребня — отдельная геометрия.

   Оси: голова в своих координатах (y вверх от шеи, z вперёд), подбородок ~0.4, надбровья ~6.6, макушка ~9.6.
   Пропорции (доли высоты лица «верх бровей → подбородок» = 6.8, по виду спереди): глаза 0.23, низ носа 0.39,
   верх пасти 0.45, низ пасти 0.83; ширина пасти ≈ 0.69 ширины лица; морда выдвинута вперёд (вид сбоку).

   Части (у каждой своя форма):
     череп · лицевая масса · морда (верхняя челюсть) · скулы · брыли · нижняя челюсть · подбородок ·
     надбровья со злым изломом и переносица · нос с крыльями и ноздрями · губы (валики) · уши (острые,
     с впадиной) · шея · глазницы · складки (лоб, переносица, под глазами, носогубные, подбородок) ·
     пасть (полость) · язык с бороздой · дёсны (цвет у края полости) · зубы: верхний и нижний ряды, клыки,
     нижние бивни, второй ряд глубже · глаза: оранжево-жёлтая радужка, тёмный зрачок, светятся ·
     гребень: шипы-перья (основание, объём, изгиб назад, острие), грива по бокам, бородка, грива на затылке.
   ============================================================================ */
(function (global) {
  'use strict';

  const clamp = (x, a, b) => (x < a ? a : x > b ? b : x);
  const smin = (a, b, k) => { const h = clamp(0.5 + 0.5 * (b - a) / k, 0, 1); return b + (a - b) * h - k * h * (1 - h); };
  const smax = (a, b, k) => -smin(-a, -b, k);
  const sph = (x, y, z, cx, cy, cz, r) => Math.hypot(x - cx, y - cy, z - cz) - r;
  function ell(x, y, z, cx, cy, cz, rx, ry, rz) {                  // эллипсоид (приближение Квилеза)
    const a = (x - cx) / rx, b = (y - cy) / ry, c = (z - cz) / rz;
    const k0 = Math.hypot(a, b, c), k1 = Math.hypot(a / rx, b / ry, c / rz);
    return k1 < 1e-9 ? -Math.min(rx, ry, rz) : k0 * (k0 - 1) / k1;
  }
  function ellR(x, y, z, cx, cy, cz, rx, ry, rz, an) {              // эллипсоид, повёрнутый в плоскости xy
    const dx = x - cx, dy = y - cy, c = Math.cos(an), s = Math.sin(an);
    return ell(cx + dx * c + dy * s, cy - dx * s + dy * c, z, cx, cy, cz, rx, ry, rz);
  }
  function cap(x, y, z, ax, ay, az, bx, by, bz, r) {               // капсула
    const px = x - ax, py = y - ay, pz = z - az, ex = bx - ax, ey = by - ay, ez = bz - az;
    const h = clamp((px * ex + py * ey + pz * ez) / (ex * ex + ey * ey + ez * ez), 0, 1);
    return Math.hypot(px - ex * h, py - ey * h, pz - ez * h) - r;
  }
  function rcone(x, y, z, ax, ay, az, bx, by, bz, r1, r2) {        // скруглённый конус между двумя точками
    const bax = bx - ax, bay = by - ay, baz = bz - az, l2 = bax * bax + bay * bay + baz * baz;
    const rr = r1 - r2, a2 = l2 - rr * rr, il2 = 1 / l2;
    const pax = x - ax, pay = y - ay, paz = z - az, yy = pax * bax + pay * bay + paz * baz, zz = yy - l2;
    const xvx = pax * l2 - bax * yy, xvy = pay * l2 - bay * yy, xvz = paz * l2 - baz * yy;
    const x2 = xvx * xvx + xvy * xvy + xvz * xvz, y2 = yy * yy * l2, z2 = zz * zz * l2;
    const k = Math.sign(rr) * rr * rr * x2;
    if (Math.sign(zz) * a2 * z2 > k) return Math.sqrt(x2 + z2) * il2 - r2;
    if (Math.sign(yy) * a2 * y2 < k) return Math.sqrt(x2 + y2) * il2 - r1;
    return (Math.sqrt(x2 * a2 * il2) + yy * rr) * il2 - r1;
  }
  /* шум для фактуры кожи (детерминированный) */
  const hash3 = (i, j, k) => { let h = (i * 374761393 + j * 668265263 + k * 1274126177) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return ((h ^ (h >>> 16)) >>> 0) / 4294967296; };
  function noise(x, y, z) {
    const i = Math.floor(x), j = Math.floor(y), k = Math.floor(z), fx = x - i, fy = y - j, fz = z - k;
    const u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy), w = fz * fz * (3 - 2 * fz);
    const L = (a, b, t) => a + (b - a) * t;
    return L(L(L(hash3(i, j, k), hash3(i + 1, j, k), u), L(hash3(i, j + 1, k), hash3(i + 1, j + 1, k), u), v),
      L(L(hash3(i, j, k + 1), hash3(i + 1, j, k + 1), u), L(hash3(i, j + 1, k + 1), hash3(i + 1, j + 1, k + 1), u), v), w) * 2 - 1;
  }

  /* ---------------- пасть ---------------- */
  const PAST = { cy: 2.6, rx: 2.3, ry: 1.55, cz: 3.25, rz: 2.6 };
  const polost = (x, y, z) => ell(x, y, z, 0, PAST.cy, PAST.cz, PAST.rx, PAST.ry, PAST.rz);
  const yazyk = (x, y, z) => smax(ell(x, y, z, 0, 1.45, 2.9, 1.6, 0.5, 1.9), -cap(x, y, z, 0, 1.98, 1.9, 0, 1.98, 4.5, 0.12), 0.1);

  /* губы: валики-дуги из капсул (верхняя тоньше, нижняя толще и вывернута) */
  function guby(x, y, z) {
    let d = 1e9;
    for (let i = 0; i < 6; i++) {
      const t0 = -1 + i / 3, t1 = -1 + (i + 1) / 3;
      const U = t => [t * 2.4, 4.22 - 0.45 * t * t, 5.0 - 0.8 * t * t];
      const D = t => [t * 2.45, 0.98 + 0.5 * t * t, 4.75 - 0.85 * t * t];
      const a = U(t0), b = U(t1), c = D(t0), e = D(t1);
      d = Math.min(d, cap(x, y, z, a[0], a[1], a[2], b[0], b[1], b[2], 0.36), cap(x, y, z, c[0], c[1], c[2], e[0], e[1], e[2], 0.44));
    }
    return d;
  }
  /* ухо: острое, назад-вверх, сплющенное, с впадиной */
  function ukho(x, y, z) {
    const ax = Math.abs(x), nx = 0.72, ny = -0.1, nz = 0.69;         // нормаль плоскости уха (наружу-вперёд)
    const t = (ax - 3.3) * nx + (y - 5.25) * ny + (z + 0.1) * nz;
    const fx = ax + nx * t * 1.7, fy = y + ny * t * 1.7, fz = z + nz * t * 1.7;   // сжать поперёк плоскости
    const d = rcone(fx, fy, fz, 3.3, 5.25, -0.1, 6.0, 7.35, -0.9, 1.1, 0.05) / 2.2;
    const inner = rcone(fx - nx * 0.55, fy - ny * 0.55, fz - nz * 0.55, 3.65, 5.45, -0.1, 5.65, 7.0, -0.75, 0.68, 0.03) / 2.2;
    return smax(d, -inner, 0.08);
  }
  /* основа кожи без складок и шума */
  function osnova(x, y, z) {
    const ax = Math.abs(x);
    let d = ell(x, y, z, 0, 6.6, -1.4, 3.7, 3.5, 4.2);                    // череп — лоб скошен назад
    d = smin(d, ell(x, y, z, 0, 4.6, 1.0, 3.6, 2.7, 3.4), 0.8);            // лицевая масса
    d = smin(d, ell(x, y, z, 0, 3.4, 3.0, 2.6, 1.9, 2.5), 0.7);            // морда (верхняя челюсть)
    d = smin(d, ell(ax, y, z, 2.55, 4.95, 2.8, 1.3, 0.85, 1.2), 0.6);      // скулы
    d = smin(d, ell(ax, y, z, 2.75, 2.2, 1.6, 1.3, 1.7, 1.6), 0.8);        // брыли
    d = smin(d, ell(x, y, z, 0, 1.25, 2.1, 3.0, 1.35, 2.8), 0.8);          // нижняя челюсть
    d = smin(d, ell(x, y, z, 0, 0.45, 3.35, 2.0, 0.9, 1.35), 0.6);         // подбородок
    d = smin(d, rcone(ax, y, z, 0.42, 5.92, 4.3, 2.6, 6.5, 3.45, 0.8, 0.5), 0.85); // надбровья: у переносицы ниже и толще — злой излом
    d = smin(d, ell(ax, y, z, 1.55, 6.12, 3.85, 1.2, 0.55, 0.85), 0.7);    // нависают над глазами
    d = smin(d, sph(x, y, z, 0, 5.9, 4.2, 0.48), 0.45);                    // переносица между бровей
    d = smin(d, cap(x, y, z, 0, 5.9, 4.3, 0, 5.0, 5.05, 0.5), 0.4);        // спинка носа
    d = smin(d, ell(x, y, z, 0, 4.82, 5.05, 1.6, 0.8, 0.95), 0.4);        // нос — широкий, приплюснутый
    d = smin(d, sph(ax, y, z, 1.15, 4.58, 4.7, 0.66), 0.3);                // крылья носа
    d = smin(d, guby(x, y, z), 0.3);                                        // губы
    d = smin(d, ukho(x, y, z), 0.3);                                        // уши
    d = smin(d, cap(x, y, z, 0, 2.6, -1.7, 0, 0.2, -1.3, 2.05), 1.0);     // шея
    d = smax(d, -ellR(ax, y, z, 1.6, 5.45, 3.95, 1.15, 0.5, 0.8, 0.36), 0.15); // глазницы — узкая злая щель
    d = smax(d, -sph(ax, y, z, 0.62, 4.5, 5.35, 0.3), 0.12);               // ноздри
    return d;
  }
  /* складки — полилинии (x, y), глубина по поверхности считается при запуске */
  const SKLADKI = [
    [[-0.32, 6.15], [-0.4, 6.95]], [[0.32, 6.15], [0.4, 6.95]],                       // борозды между бровей
    [[-0.65, 5.62], [0, 5.55], [0.65, 5.62]], [[-0.5, 5.33], [0, 5.28], [0.5, 5.33]], // морщины переносицы
    [[-2.0, 7.55], [-0.8, 7.35], [0, 7.45], [0.8, 7.35], [2.0, 7.55]],              // лоб
    [[-1.6, 7.9], [0, 7.8], [1.6, 7.9]],
    [[-1.15, 5.15], [-1.9, 5.0], [-2.6, 5.15]], [[1.15, 5.15], [1.9, 5.0], [2.6, 5.15]], // мешки под глазами
    [[-1.2, 4.5], [-2.15, 3.75], [-2.75, 2.75]], [[1.2, 4.5], [2.15, 3.75], [2.75, 2.75]], // носогубные
    [[-1.1, 0.95], [0, 0.85], [1.1, 0.95]],                                          // подбородок
    [[-3.1, 4.2], [-3.5, 3.2]], [[3.1, 4.2], [3.5, 3.2]]                            // щёки
  ];

  function build(T, o) {
    o = o || {};
    const lite = !!o.lite;
    /* поверхность вдоль +z на (x, y) — для складок и зубов */
    const surfZ = (f, x, y, z0, z1) => {
      let a = z1, b = z0;
      if (f(x, y, a) < 0) return a;
      if (f(x, y, b) > 0) return null;
      for (let i = 0; i < 40; i++) { const m = (a + b) / 2; if (f(x, y, m) > 0) a = m; else b = m; }
      return (a + b) / 2;
    };
    const skl = [];
    for (const line of SKLADKI) {
      const pts = line.map(([x, y]) => { const z = surfZ(osnova, x, y, -2, 7); return z == null ? null : [x, y, z]; });
      for (let i = 0; i + 1 < pts.length; i++) if (pts[i] && pts[i + 1]) skl.push([...pts[i], ...pts[i + 1]]);
    }
    function kozha(x, y, z) {
      let d = osnova(x, y, z);
      if (d < 0.4) {
        for (const s of skl) d = smax(d, -cap(x, y, z, s[0], s[1], s[2] + 0.04, s[3], s[4], s[5] + 0.04, 0.07), 0.08);
        d += noise(x * 2.3, y * 2.3, z * 2.3) * 0.045 + noise(x * 6, y * 6, z * 6) * 0.012;
      }
      d = smax(d, -polost(x, y, z), 0.22);                                  // пасть открыта
      d = smin(d, yazyk(x, y, z), 0.25);                                    // язык со дна пасти
      return d;
    }
    const ZONE = (x, y, z) => ({ pol: polost(x, y, z), yaz: yazyk(x, y, z) });

    /* ---------- сетка: поле на решётке (грубо → точно у поверхности), surface nets ---------- */
    const h = lite ? 0.16 : 0.085;
    const X0 = -6.4, Y0 = -2.0, Z0 = -5.8, X1 = 6.4, Y1 = 10.6, Z1 = 6.8;
    const NX = Math.ceil((X1 - X0) / h) + 1, NY = Math.ceil((Y1 - Y0) / h) + 1, NZ = Math.ceil((Z1 - Z0) / h) + 1;
    const F = new Float32Array(NX * NY * NZ), I = (i, j, k) => (k * NY + j) * NX + i;
    const ST = 4, CX = Math.ceil(NX / ST) + 1, CY = Math.ceil(NY / ST) + 1, CZ = Math.ceil(NZ / ST) + 1;
    const G = new Float32Array(CX * CY * CZ), IG = (i, j, k) => (k * CY + j) * CX + i;
    for (let k = 0; k < CZ; k++) for (let j = 0; j < CY; j++) for (let i = 0; i < CX; i++)
      G[IG(i, j, k)] = kozha(X0 + i * ST * h, Y0 + j * ST * h, Z0 + k * ST * h);
    const BAND = ST * h * 1.8;
    for (let k = 0; k < NZ; k++) for (let j = 0; j < NY; j++) for (let i = 0; i < NX; i++) {
      const gi = Math.floor(i / ST), gj = Math.floor(j / ST), gk = Math.floor(k / ST);
      let near = false, sgn = 0;
      for (let c = 0; c < 8 && !near; c++) {
        const v = G[IG(gi + (c & 1), gj + ((c >> 1) & 1), gk + (c >> 2))];
        if (Math.abs(v) < BAND) near = true; sgn += v;
      }
      F[I(i, j, k)] = near ? kozha(X0 + i * h, Y0 + j * h, Z0 + k * h) : (sgn > 0 ? BAND : -BAND);
    }
    const vid = new Int32Array(NX * NY * NZ).fill(-1);
    const pos = [], idx = [];
    const EDGES = [[0, 1], [2, 3], [4, 5], [6, 7], [0, 2], [1, 3], [4, 6], [5, 7], [0, 4], [1, 5], [2, 6], [3, 7]];
    const corner = c => [c & 1, (c >> 1) & 1, c >> 2];
    for (let k = 0; k < NZ - 1; k++) for (let j = 0; j < NY - 1; j++) for (let i = 0; i < NX - 1; i++) {
      const v = [];
      let inside = 0;
      for (let c = 0; c < 8; c++) { const [a, b, e] = corner(c); const f = F[I(i + a, j + b, k + e)]; v.push(f); if (f < 0) inside++; }
      if (inside === 0 || inside === 8) continue;
      let sx = 0, sy = 0, sz = 0, n = 0;
      for (const [a, b] of EDGES) {
        if ((v[a] < 0) === (v[b] < 0)) continue;
        const t = v[a] / (v[a] - v[b]), ca = corner(a), cb = corner(b);
        sx += ca[0] + (cb[0] - ca[0]) * t; sy += ca[1] + (cb[1] - ca[1]) * t; sz += ca[2] + (cb[2] - ca[2]) * t; n++;
      }
      vid[I(i, j, k)] = pos.length / 3;
      pos.push(X0 + (i + sx / n) * h, Y0 + (j + sy / n) * h, Z0 + (k + sz / n) * h);
    }
    for (let k = 1; k < NZ - 1; k++) for (let j = 1; j < NY - 1; j++) for (let i = 1; i < NX - 1; i++) {
      const f0 = F[I(i, j, k)];
      for (let ax = 0; ax < 3; ax++) {
        const f1 = ax === 0 ? F[I(i + 1, j, k)] : ax === 1 ? F[I(i, j + 1, k)] : F[I(i, j, k + 1)];
        if ((f0 < 0) === (f1 < 0)) continue;
        let q;
        if (ax === 0) q = [vid[I(i, j - 1, k - 1)], vid[I(i, j, k - 1)], vid[I(i, j, k)], vid[I(i, j - 1, k)]];
        else if (ax === 1) q = [vid[I(i - 1, j, k - 1)], vid[I(i - 1, j, k)], vid[I(i, j, k)], vid[I(i, j, k - 1)]];
        else q = [vid[I(i - 1, j - 1, k)], vid[I(i, j - 1, k)], vid[I(i, j, k)], vid[I(i - 1, j, k)]];
        if (q.some(x => x < 0)) continue;
        if (f0 < 0) idx.push(q[0], q[1], q[2], q[0], q[2], q[3]);
        else idx.push(q[0], q[2], q[1], q[0], q[3], q[2]);
      }
    }
    /* нормали по градиенту поля, цвет по зоне + затенение складок (AO по полю) */
    const nv = pos.length / 3, nor = new Float32Array(nv * 3), col = new Float32Array(nv * 3);
    const e = 0.03;
    const C = hex => { const c = new T.Color(hex); c.convertSRGBToLinear(); return [c.r, c.g, c.b]; };
    const KOZHA = C('#4f8c2b'), KOZHA_T = C('#2c5416'), KOZHA_S = C('#8cc650'), GUBA = C('#4f8a2c'),
      PAST_C = C('#5a1414'), DESNA = C('#8e2f2a'), YAZ = C('#8f3a33'), YAZ_S = C('#b8665a'), UHO_V = C('#3f6a22');
    const mix = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
    for (let v = 0; v < nv; v++) {
      const x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
      let gx = kozha(x + e, y, z) - kozha(x - e, y, z), gy = kozha(x, y + e, z) - kozha(x, y - e, z), gz = kozha(x, y, z + e) - kozha(x, y, z - e);
      const gl = Math.hypot(gx, gy, gz) || 1; gx /= gl; gy /= gl; gz /= gl;
      nor.set([gx, gy, gz], v * 3);
      let occ = 0;
      for (let s = 1; s <= 4; s++) { const dd = s * 0.22; occ += (dd - kozha(x + gx * dd, y + gy * dd, z + gz * dd)) / Math.pow(2, s); }
      const ao = clamp(1 - occ * 1.6, 0.25, 1);
      const zn = ZONE(x, y, z);
      let c;
      if (zn.yaz < 0.06 && zn.pol < 0.1) c = mix(YAZ, YAZ_S, clamp((gy - 0.3) * 1.5, 0, 1));            // язык, блик сверху
      else if (zn.pol < 0.06) {                                                                            // полость: дёсны у края, глубже тёмная
        const edge = clamp((z - 3.6) / 1.3, 0, 1);
        c = mix(PAST_C, DESNA, edge);
      } else {
        const u = noise(x * 1.7 + 11, y * 1.7, z * 1.7) * 0.5 + 0.5;
        c = mix(KOZHA_T, KOZHA, 0.4 + 0.6 * u);
        if (gy > 0.35 && y > 4.6 && z > 2.0) c = mix(c, KOZHA_S, clamp((gy - 0.35) * 1.4, 0, 0.55));    // свет на надбровьях и скулах
        if (guby(x, y, z) < 0.15) c = mix(c, GUBA, 0.6);
        if (ukho(x, y, z) < 0.05 && (Math.abs(x) * 0.72 - 0.1 * y + 0.69 * z) < 3.1) c = mix(c, UHO_V, 0.5);
        c = mix(c, KOZHA_T, 0.3 * clamp((6.4 - z * 0.6 - (9.5 - y) * 0.5) / 2, 0, 1));                       // под гребнем темнее, плавно
      }
      col.set([c[0] * ao, c[1] * ao, c[2] * ao], v * 3);
    }
    const geo = new T.BufferGeometry();
    geo.setAttribute('position', new T.Float32BufferAttribute(pos, 3));
    geo.setAttribute('normal', new T.BufferAttribute(nor, 3));
    geo.setAttribute('color', new T.BufferAttribute(col, 3));
    geo.setIndex(idx);
    const group = new T.Group(); group.name = 'zver_golova';
    const matKozha = new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.62, metalness: 0, envMapIntensity: 0.5 });
    matKozha.name = 'zver_kozha';
    const kozhaM = new T.Mesh(geo, matKozha); kozhaM.name = 'zver_kozha'; kozhaM.castShadow = kozhaM.receiveShadow = true;
    group.add(kozhaM);
    /* контур как на рисунке: та же кожа, раздутая по нормали, тёмная, видна только изнанка */
    const kontur = (g, d, name) => {
      const p = g.attributes.position, nn = g.attributes.normal, q = new Float32Array(p.count * 3);
      for (let i = 0; i < p.count; i++) q.set([p.getX(i) + nn.getX(i) * d, p.getY(i) + nn.getY(i) * d, p.getZ(i) + nn.getZ(i) * d], i * 3);
      const g2 = new T.BufferGeometry(); g2.setAttribute('position', new T.BufferAttribute(q, 3)); g2.setIndex(g.index);
      const m = new T.Mesh(g2, matKontur); m.name = name; return m;
    };
    const matKontur = new T.MeshBasicMaterial({ color: new T.Color('#16260c').convertSRGBToLinear(), side: T.BackSide });
    matKontur.name = 'zver_kontur';
    if (!lite) group.add(kontur(geo, 0.045, 'zver_kontur'));

    /* ---------- изогнутый конус (зуб, шип): кольца вдоль кривой, сечение — эллипс ---------- */
    function blade(B, base, dir, bend, side, L, w, th, seg, rings, colorAt) {
      const P = [], N = [], Cc = [], Ix = [];
      const pt = t => [base[0] + dir[0] * L * t + bend[0] * L * t * t, base[1] + dir[1] * L * t + bend[1] * L * t * t, base[2] + dir[2] * L * t + bend[2] * L * t * t];
      const norm = a => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
      const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
      for (let r = 0; r <= rings; r++) {
        const t = r / rings, c = pt(t), c2 = pt(Math.min(1, t + 0.02)), c1 = pt(Math.max(0, t - 0.02));
        const tg = norm([c2[0] - c1[0], c2[1] - c1[1], c2[2] - c1[2]]);
        const s = norm(cross(tg, side)), m = norm(cross(s, tg));
        const k = Math.pow(1 - t, 0.85), a = w * k, b = th * k;
        for (let q = 0; q < seg; q++) {
          const an = q / seg * Math.PI * 2, ca = Math.cos(an), sa = Math.sin(an);
          P.push(c[0] + s[0] * a * ca + m[0] * b * sa, c[1] + s[1] * a * ca + m[1] * b * sa, c[2] + s[2] * a * ca + m[2] * b * sa);
          const nn = norm([s[0] * ca * b + m[0] * sa * a, s[1] * ca * b + m[1] * sa * a, s[2] * ca * b + m[2] * sa * a]);
          N.push(...nn); Cc.push(...colorAt(t, ca, sa));
        }
      }
      const tip = pt(1); P.push(...tip); N.push(...norm(dir)); Cc.push(...colorAt(1, 0, 1));
      for (let r = 0; r < rings; r++) for (let q = 0; q < seg; q++) {
        const a = r * seg + q, b2 = r * seg + (q + 1) % seg;
        Ix.push(a, a + seg, b2, b2, a + seg, b2 + seg);
      }
      const last = rings * seg, tipI = (rings + 1) * seg;
      for (let q = 0; q < seg; q++) Ix.push(last + q, tipI, last + (q + 1) % seg);
      const o0 = B.p.length / 3;
      B.p.push(...P); B.n.push(...N); B.c.push(...Cc); for (const i of Ix) B.i.push(i + o0);
    }
    const meshOf = (B, mat, name) => {
      const g = new T.BufferGeometry();
      g.setAttribute('position', new T.Float32BufferAttribute(B.p, 3));
      g.setAttribute('normal', new T.Float32BufferAttribute(B.n, 3));
      g.setAttribute('color', new T.Float32BufferAttribute(B.c, 3));
      g.setIndex(B.i); g.computeVertexNormals();
      const m = new T.Mesh(g, mat); m.name = name; m.castShadow = m.receiveShadow = true; return m;
    };
    let sd = 4242;
    const rnd = () => { sd = (sd * 16807) % 2147483647; return (sd - 1) / 2147483646; };

    /* ---------- зубы ---------- */
    const ZB = { p: [], n: [], c: [], i: [] };
    const IV = C('#efe3c0'), IV_T = C('#b89f6c');
    const zubCol = (t, ca, sa) => mix(IV_T, IV, clamp(t * 1.6, 0, 1)).map(v => v * (0.9 + 0.1 * sa));
    function ryad(verh, xs, depth, scale) {
      for (const [xn, big] of xs) {
        const x = xn * PAST.rx;
        /* край пасти у губы: идём от края полости к центру, пока точка чуть под кожей не окажется в полости */
        let yEdge = PAST.cy, zf = 4.5;
        for (let k = 0; k <= 90; k++) {
          const y = PAST.cy + (verh ? 1 : -1) * PAST.ry * (1.1 - k / 90 * 1.1);
          const zz = surfZ(osnova, x, y, -1, 7);
          if (zz != null && polost(x, y, zz - 0.3 - depth) < 0) { yEdge = y; zf = zz; break; }
        }
        const yb = yEdge + (verh ? 0.14 : -0.14);
        const base = [x, yb, zf - 0.32 - depth];
        const L = (big ? (verh ? 1.25 : 1.45) : 0.62 + 0.15 * (1 - Math.abs(xn))) * scale;
        const w = (big ? 0.27 : 0.17 + 0.05 * (1 - Math.abs(xn))) * scale;
        const dir = [-x * 0.05, verh ? -1 : 1, 0.08];
        const bend = [-x * 0.02, 0, verh ? -0.16 : -0.22];
        blade(ZB, base, dir, bend, [0, 0, 1], L, w, w * 0.78, lite ? 6 : 10, lite ? 4 : 7, zubCol);
      }
    }
    ryad(true, [[-0.86, 0], [-0.62, 1], [-0.42, 0], [-0.25, 0], [-0.08, 0], [0.08, 0], [0.25, 0], [0.42, 0], [0.62, 1], [0.86, 0]], 0, 1);
    ryad(false, [[-0.84, 1], [-0.62, 0], [-0.4, 0], [-0.2, 0], [0, 0], [0.2, 0], [0.4, 0], [0.62, 0], [0.84, 1]], 0, 1);
    ryad(true, [[-0.7, 0], [-0.45, 0], [0.45, 0], [0.7, 0]], 0.75, 0.75);
    ryad(false, [[-0.72, 0], [-0.48, 0], [0.48, 0], [0.72, 0]], 0.75, 0.75);
    const matZub = new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.32, metalness: 0, envMapIntensity: 0.9 }); matZub.name = 'zver_zuby';
    group.add(meshOf(ZB, matZub, 'zver_zuby'));

    /* ---------- глаза: светящиеся, оранжево-жёлтая радужка, тёмный зрачок ---------- */
    const glaza = {};
    [-1, 1].forEach(sx => {
      const gg = new T.SphereGeometry(0.7, lite ? 16 : 28, lite ? 12 : 20);
      const cc = new Float32Array(gg.attributes.position.count * 3), p = gg.attributes.position;
      const f = new T.Vector3(sx * 0.12, -0.05, 1).normalize(), v = new T.Vector3();
      const ZR = C('#fff36a'), OR = C('#ff9a1c'), RD = C('#c2360c'), BK = C('#1a0a04');
      for (let i = 0; i < p.count; i++) {
        v.set(p.getX(i), p.getY(i), p.getZ(i)).normalize();
        const a = Math.acos(clamp(v.dot(f), -1, 1));
        let c = a < 0.13 ? BK : a < 0.55 ? mix(ZR, OR, (a - 0.13) / 0.42) : a < 0.75 ? mix(OR, RD, (a - 0.55) / 0.2) : mix(RD, BK, clamp((a - 0.75) / 0.6, 0, 1));
        cc.set(c, i * 3);
      }
      gg.setAttribute('color', new T.BufferAttribute(cc, 3));
      const m = new T.Mesh(gg, new T.MeshBasicMaterial({ vertexColors: true }));
      m.material.name = 'zver_glaz';
      m.name = sx < 0 ? 'eyeL' : 'eyeR';
      m.position.set(sx * 1.6, 5.43, 3.32);
      group.add(m); glaza[m.name] = m;
    });

    /* ---------- гребень и грива: шипы по поверхности черепа ---------- */
    const SP = { p: [], n: [], c: [], i: [] };
    const SG = [C('#2c5a17'), C('#4a8a27'), C('#6aa83a'), C('#9fd15f')];
    const CEN = [0, 5.2, -0.6];
    const surfAlong = (dx, dy, dz) => {                         // точка кожи по лучу из центра
      let a = 0, b = 9;
      for (let i = 0; i < 40; i++) { const m = (a + b) / 2; if (osnova(CEN[0] + dx * m, CEN[1] + dy * m, CEN[2] + dz * m) < 0) a = m; else b = m; }
      return [CEN[0] + dx * a, CEN[1] + dy * a, CEN[2] + dz * a];
    };
    const gradN = p => { const q = 0.03; const g = [osnova(p[0] + q, p[1], p[2]) - osnova(p[0] - q, p[1], p[2]), osnova(p[0], p[1] + q, p[2]) - osnova(p[0], p[1] - q, p[2]), osnova(p[0], p[1], p[2] + q) - osnova(p[0], p[1], p[2] - q)]; const l = Math.hypot(...g) || 1; return g.map(v => v / l); };
    const NS = lite ? 900 : 2400, golden = Math.PI * (3 - Math.sqrt(5)), taken = [];
    for (let s = 0; s < NS; s++) {
      const yv = 1 - 2 * (s + 0.5) / NS, rr = Math.sqrt(1 - yv * yv), th = golden * s;
      const p = surfAlong(Math.cos(th) * rr, yv, Math.sin(th) * rr), [x, y, z] = p, ax = Math.abs(x);
      let kind = null;
      const face = (z > 2.0 && ax < 3.1 && y < 6.45 && y > 0.3) || (z > 1.7 && ax < 2.6 && y < 7.6);   // лицо и лоб над бровями — голые
      if (ukho(x, y, z) < 1.0) continue;
      if (!face && (y > 6.45 || (y > 5.6 && z < 1.4))) kind = 'greben';
      else if (!face && ax > 2.7 && y > 0.9 && z < 2.7) kind = 'griva';
      else if (y < 0.7 && z > 1.8 && ax < 1.9 && rnd() < 0.5) kind = 'boroda';
      else if (!face && z < -1.2 && y > 0.5) kind = 'zatylok';
      if (!kind) continue;
      const gap = kind === 'greben' ? 0.88 : kind === 'boroda' ? 0.7 : 0.78;
      if (taken.some(q => Math.hypot(q[0] - x, q[1] - y, q[2] - z) < gap)) continue;
      taken.push(p);
      const n = gradN(p);
      let dir, bend, L, w;
      if (kind === 'greben') {
        const top = clamp((y - 6) / 3.2, 0, 1), back = clamp((1.5 - z) / 5, 0, 1);
        dir = [n[0] * 0.8, n[1] * 0.8 + 0.75, n[2] * 0.8 - 0.35 - 0.5 * back];
        bend = [n[0] * 0.15, -0.1, -0.55];
        L = (2.2 + 2.2 * top + 1.6 * back) * (0.8 + rnd() * 0.4); w = 0.55 + 0.3 * top + rnd() * 0.2;
      } else if (kind === 'griva') {
        dir = [n[0] * 1.0, n[1] * 0.6 - 0.25, n[2] * 0.6 - 0.45]; bend = [n[0] * 0.1, -0.35, -0.3];
        L = (1.6 + 1.3 * clamp((y - 1) / 5, 0, 1)) * (0.8 + rnd() * 0.45); w = 0.48 + rnd() * 0.22;
      } else if (kind === 'boroda') {
        dir = [n[0] * 0.9 + (rnd() - 0.5) * 0.6, -1, n[2] * 0.8]; bend = [0, -0.1, -0.25]; L = 0.6 + rnd() * 0.5; w = 0.45 + rnd() * 0.15;
      } else {
        dir = [n[0] * 0.7, n[1] * 0.4 - 0.2, -1]; bend = [0, -0.45, -0.2]; L = (2.0 + 1.5 * rnd()); w = 0.55 + rnd() * 0.22;
      }
      const dl = Math.hypot(...dir); dir = dir.map(v => v / dl);
      const base = [x - dir[0] * 0.3, y - dir[1] * 0.3, z - dir[2] * 0.3];
      const tone = rnd();
      blade(SP, base, dir, bend, n, L, w, w * 0.22, lite ? 5 : 8, lite ? 4 : 7, (t, ca, sa) => {
        const ridge = sa > 0 ? Math.max(0, 1 - Math.abs(ca) * 2.2) : 0;           // светлая жилка по середине пера
        let c = mix(SG[0], SG[1 + Math.floor(tone * 2)], clamp(t * 2.2, 0, 1));
        c = mix(c, SG[3], ridge * 0.55 * (1 - t * 0.5));
        c = mix(c, SG[0], clamp((Math.abs(ca) - 0.55) * 1.6, 0, 0.7));          // тёмные края пера
        return c;
      });
    }
    const matSp = new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.58, metalness: 0, envMapIntensity: 0.5, side: T.DoubleSide }); matSp.name = 'zver_greben';
    const spM = meshOf(SP, matSp, 'zver_greben');
    if (!o.bezShipov) { group.add(spM); if (!lite) group.add(kontur(spM.geometry, 0.03, 'zver_greben_kontur')); }
    return { group, glaza, stats: { vershin: nv + SP.p.length / 3 + ZB.p.length / 3, shipov: taken.length } };
  }

  /* ======================================================================================================
     ФОРМА ИЗ РИСУНКА (по умолчанию, если подключён src/zver-risunok.js): объём строится из самого рисунка —
     силуэт спереди и силуэт сбоку (поле расстояний до контура), в каждом сечении — суперэллипс по ширине
     спереди и глубине сбоку; тёмное на рисунке (пасть, складки, глазницы) утоплено внутрь; поверх —
     проекция рисунка спереди и с боков (профиль зеркалится на левую сторону). Так голова совпадает с листом
     в обоих видах контур в контур, а штрихи, тени и блики — его собственные.
     ====================================================================================================== */
  function dekod(b64) {
    const s = (typeof atob === 'function') ? atob(b64) : Buffer.from(b64, 'base64').toString('binary');
    const u = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i);
    return u;
  }
  function vid(v) {
    const rgba = dekod(v.rgba), sd = new Int8Array(dekod(v.sd).buffer), w = v.w, h = v.h;
    /* крайние точки силуэта по строкам: пропуски — ближайшей строкой, сглажено по высоте (без ступенек) */
    let A = new Float32Array(h).fill(NaN), B = new Float32Array(h).fill(NaN);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (sd[y * w + x] < 0) { if (isNaN(A[y])) A[y] = x; B[y] = x; }
    const est = new Uint8Array(h).map((_, y) => isNaN(A[y]) ? 0 : 1);
    for (let y = 0; y < h; y++) if (!est[y]) {
      let d = 1; while (d < h && !((y - d >= 0 && est[y - d]) || (y + d < h && est[y + d]))) d++;
      const yy = (y - d >= 0 && est[y - d]) ? y - d : y + d; if (yy >= 0 && yy < h) { A[y] = A[yy]; B[y] = B[yy]; }
    }
    const gladit = (M, r) => { const o = new Float32Array(h); for (let y = 0; y < h; y++) { let s = 0, n = 0; for (let k = -r; k <= r; k++) { const yy = y + k; if (yy >= 0 && yy < h && !isNaN(M[yy])) { const wk = Math.exp(-(k * k) / (r * r / 2)); s += M[yy] * wk; n += wk; } } o[y] = n ? s / n : NaN; } return o; };
    A = gladit(A, 4); B = gladit(B, 4);
    const ryad = py => {                                          // дробная строка → [лево, право] или null
      const y = clamp(py, 0, h - 1), y0 = Math.floor(y), y1 = Math.min(y0 + 1, h - 1), t = y - y0;
      if (!est[Math.round(y)]) return null;
      return [A[y0] * (1 - t) + A[y1] * t, B[y0] * (1 - t) + B[y1] * t];
    };
    const sdAt = (px, py) => {                                    // билинейно; за листом — далеко снаружи
      if (px < 0 || py < 0 || px > w - 1 || py > h - 1) {
        const dx = Math.max(-px, 0, px - (w - 1)), dy = Math.max(-py, 0, py - (h - 1));
        return 60 + Math.hypot(dx, dy);
      }
      const x0 = Math.floor(px), y0 = Math.floor(py), x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1), fx = px - x0, fy = py - y0;
      const A = sd[y0 * w + x0], B = sd[y0 * w + x1], C = sd[y1 * w + x0], D = sd[y1 * w + x1];
      return (A * (1 - fx) + B * fx) * (1 - fy) + (C * (1 - fx) + D * fx) * fy;
    };
    const cvet = (px, py) => {                                    // [r,g,b,a] 0..1, билинейно
      px = clamp(px, 0, w - 1); py = clamp(py, 0, h - 1);
      const x0 = Math.floor(px), y0 = Math.floor(py), x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1), fx = px - x0, fy = py - y0;
      const o = [0, 0, 0, 0];
      for (const [x, y, k] of [[x0, y0, (1 - fx) * (1 - fy)], [x1, y0, fx * (1 - fy)], [x0, y1, (1 - fx) * fy], [x1, y1, fx * fy]]) {
        const i = (y * w + x) * 4;
        for (let c = 0; c < 4; c++) o[c] += rgba[i + c] / 255 * k;
      }
      return o;
    };
    return { w, h, ryad, sdAt, cvet };
  }
  /* поверхность поля f на решётке (грубо → точно у поверхности), «surface nets» */
  function poverhnost(f, X0, Y0, Z0, X1, Y1, Z1, h) {
    const NX = Math.ceil((X1 - X0) / h) + 1, NY = Math.ceil((Y1 - Y0) / h) + 1, NZ = Math.ceil((Z1 - Z0) / h) + 1;
    const F = new Float32Array(NX * NY * NZ), I = (i, j, k) => (k * NY + j) * NX + i;
    const ST = 4, CX = Math.ceil(NX / ST) + 1, CY = Math.ceil(NY / ST) + 1, CZ = Math.ceil(NZ / ST) + 1;
    const G = new Float32Array(CX * CY * CZ), IG = (i, j, k) => (k * CY + j) * CX + i;
    for (let k = 0; k < CZ; k++) for (let j = 0; j < CY; j++) for (let i = 0; i < CX; i++) G[IG(i, j, k)] = f(X0 + i * ST * h, Y0 + j * ST * h, Z0 + k * ST * h);
    const BAND = ST * h * 1.8;
    for (let k = 0; k < NZ; k++) for (let j = 0; j < NY; j++) for (let i = 0; i < NX; i++) {
      const gi = Math.floor(i / ST), gj = Math.floor(j / ST), gk = Math.floor(k / ST);
      let near = false, sgn = 0;
      for (let c = 0; c < 8 && !near; c++) { const v = G[IG(gi + (c & 1), gj + ((c >> 1) & 1), gk + (c >> 2))]; if (Math.abs(v) < BAND) near = true; sgn += v; }
      F[I(i, j, k)] = near ? f(X0 + i * h, Y0 + j * h, Z0 + k * h) : (sgn > 0 ? BAND : -BAND);
    }
    const vid = new Int32Array(NX * NY * NZ).fill(-1), pos = [], idx = [];
    const EDGES = [[0, 1], [2, 3], [4, 5], [6, 7], [0, 2], [1, 3], [4, 6], [5, 7], [0, 4], [1, 5], [2, 6], [3, 7]];
    const cr = c => [c & 1, (c >> 1) & 1, c >> 2];
    const v = new Float32Array(8);
    for (let k = 0; k < NZ - 1; k++) for (let j = 0; j < NY - 1; j++) for (let i = 0; i < NX - 1; i++) {
      let inside = 0;
      for (let c = 0; c < 8; c++) { const [a, b, e] = cr(c); v[c] = F[I(i + a, j + b, k + e)]; if (v[c] < 0) inside++; }
      if (inside === 0 || inside === 8) continue;
      let sx = 0, sy = 0, sz = 0, n = 0;
      for (const [a, b] of EDGES) {
        if ((v[a] < 0) === (v[b] < 0)) continue;
        const t = v[a] / (v[a] - v[b]), ca = cr(a), cb = cr(b);
        sx += ca[0] + (cb[0] - ca[0]) * t; sy += ca[1] + (cb[1] - ca[1]) * t; sz += ca[2] + (cb[2] - ca[2]) * t; n++;
      }
      vid[I(i, j, k)] = pos.length / 3;
      pos.push(X0 + (i + sx / n) * h, Y0 + (j + sy / n) * h, Z0 + (k + sz / n) * h);
    }
    for (let k = 1; k < NZ - 1; k++) for (let j = 1; j < NY - 1; j++) for (let i = 1; i < NX - 1; i++) {
      const f0 = F[I(i, j, k)];
      for (let ax = 0; ax < 3; ax++) {
        const f1 = ax === 0 ? F[I(i + 1, j, k)] : ax === 1 ? F[I(i, j + 1, k)] : F[I(i, j, k + 1)];
        if ((f0 < 0) === (f1 < 0)) continue;
        let q;
        if (ax === 0) q = [vid[I(i, j - 1, k - 1)], vid[I(i, j, k - 1)], vid[I(i, j, k)], vid[I(i, j - 1, k)]];
        else if (ax === 1) q = [vid[I(i - 1, j, k - 1)], vid[I(i - 1, j, k)], vid[I(i, j, k)], vid[I(i, j, k - 1)]];
        else q = [vid[I(i - 1, j - 1, k)], vid[I(i, j - 1, k)], vid[I(i, j, k)], vid[I(i - 1, j, k)]];
        if (q.some(x => x < 0)) continue;
        if (f0 < 0) idx.push(q[0], q[1], q[2], q[0], q[2], q[3]); else idx.push(q[0], q[2], q[1], q[0], q[3], q[2]);
      }
    }
    return { pos, idx };
  }
  function buildRisunok(T, o, R) {
    const lite = !!o.lite;
    const FR = vid(R.front), SB = vid(R.side);
    const U = R.U || 0.062, KF = R.B.k, ZN = 5.6, YC = -0.3, P = 2.6;
    const fX = x => R.F.cx + x / U, fY = y => R.F.chin - (y - YC) / U;
    const sX = z => R.B.nos + (ZN - z) / (U * KF), sY = y => R.B.chin - (y - YC) / (U * KF);
    const lum = c => 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2];
    function pole(x, y, z) {
      const fy = fY(y), sy = sY(y);
      const dF = FR.sdAt(fX(x), fy) * U;
      const greben = clamp((88 - fy) / 22, 0, 1);                   // гребень: шипы — по силуэту спереди, глубина — по огибающей профиля
      const dS = SB.sdAt(sX(z), sy) * U * KF - greben * 4;
      let d = Math.max(dF, dS);
      const rf = FR.ryad(fy), rs = SB.ryad(sy);
      if (rf && rs) {                              // сечение — суперэллипс: ширина спереди × глубина сбоку
        const xl = (rf[0] - R.F.cx) * U, xr = (rf[1] - R.F.cx) * U;
        const zf = ZN - (rs[0] - R.B.nos) * U * KF, zb = ZN - (rs[1] - R.B.nos) * U * KF;
        const cx = (xl + xr) / 2, hw = Math.max((xr - xl) / 2, 0.05), cz = (zf + zb) / 2, hd = Math.max((zf - zb) / 2, 0.05);
        const e = Math.pow(Math.pow(Math.abs(x - cx) / hw, P) + Math.pow(Math.abs(z - cz) / hd, P), 1 / P);
        d = Math.max(d, (e - 1) * Math.min(hw, hd));
        if (d < 0.6) {                                             // тёмное на рисунке — вглубь (пасть, складки, глазницы)
          const wf = clamp((z - cz) / hd, 0, 1);
          if (wf > 0) {
            const L = lum(FR.cvet(fX(x), fy));
            d += wf * wf * (L < 0.3 ? 0.4 * Math.pow((0.3 - L) / 0.3, 0.7) + 0.04 : 0.1 * (0.5 - L));
          }
        }
      }
      return d;
    }
    const h = lite ? 0.14 : 0.07;
    const XL = -R.F.cx * U - 0.4, XR = (FR.w - R.F.cx) * U + 0.4, YT = YC + R.F.chin * U + 0.4, YB = YC - (FR.h - R.F.chin) * U - 0.4;
    const { pos, idx } = poverhnost(pole, XL, YB, -7.6, XR, YT, 6.2, h);
    const nv = pos.length / 3, nor = new Float32Array(nv * 3), col = new Float32Array(nv * 3), e = 0.04;
    const ZEL = [0.29, 0.5, 0.17];
    const lin = c => c.map(v => Math.pow(v, 2.2));                // цвета рисунка — в линейное пространство
    for (let i = 0; i < nv; i++) {
      const x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
      let gx = pole(x + e, y, z) - pole(x - e, y, z), gy = pole(x, y + e, z) - pole(x, y - e, z), gz = pole(x, y, z + e) - pole(x, y, z - e);
      const gl = Math.hypot(gx, gy, gz) || 1; gx /= gl; gy /= gl; gz /= gl;
      nor.set([gx, gy, gz], i * 3);
      const cF = FR.cvet(fX(x), fY(y)), cS = SB.cvet(sX(z), sY(y));
      const lico = clamp((z - 1.3) / 1.2, 0, 1);                      // у лица профиль только на чистом боку — глаз профиля ушёл бы на щёку
      const bok = Math.pow(Math.abs(gx), 4), pered = Math.pow(Math.max(0, gz), 4);
      const mordaBok = clamp((78 - sX(z)) / 18, 0, 1) * clamp((sY(y) - 70) / 10, 0, 1);   // морда в профиле — не на боковину головы
      let wf = (pered * (1 + 2 * lico) + 0.06 * mordaBok) * cF[3];
      let ws = (bok * (1 - lico * (1 - Math.pow(Math.abs(gx), 12))) + 0.3 * Math.max(0, -gz) + 0.2 * Math.max(0, gy)) * cS[3] * (1 - 0.95 * mordaBok);
      if (wf + ws < 1e-4) { wf = cF[3]; ws = cS[3]; }
      let c = wf + ws > 1e-4 ? [0, 1, 2].map(k => (cF[k] * wf + cS[k] * ws) / (wf + ws)) : ZEL;
      c = lin(c);
      let occ = 0;                                                   // мягкое затенение впадин
      for (let s = 1; s <= 3; s++) { const dd = s * 0.25; occ += (dd - pole(x + gx * dd, y + gy * dd, z + gz * dd)) / Math.pow(2, s); }
      const ao = clamp(1 - occ * 0.9, 0.55, 1);
      col.set([c[0] * ao, c[1] * ao, c[2] * ao], i * 3);
    }
    const geo = new T.BufferGeometry();
    geo.setAttribute('position', new T.Float32BufferAttribute(pos, 3));
    geo.setAttribute('normal', new T.BufferAttribute(nor, 3));
    geo.setAttribute('color', new T.BufferAttribute(col, 3));
    geo.setIndex(idx);
    const group = new T.Group(); group.name = 'zver_golova';
    const mat = new T.MeshStandardMaterial({ vertexColors: true, roughness: 0.72, metalness: 0, envMapIntensity: 0.35 }); mat.name = 'zver_risunok';
    const m = new T.Mesh(geo, mat); m.name = 'zver_kozha'; m.castShadow = m.receiveShadow = true; group.add(m);
    if (!lite) {                                                    // тонкий контур — как обводка рисунка
      const p = geo.attributes.position, q = new Float32Array(p.count * 3);
      for (let i = 0; i < p.count; i++) q.set([p.getX(i) + nor[i * 3] * 0.03, p.getY(i) + nor[i * 3 + 1] * 0.03, p.getZ(i) + nor[i * 3 + 2] * 0.03], i * 3);
      const g2 = new T.BufferGeometry(); g2.setAttribute('position', new T.BufferAttribute(q, 3)); g2.setIndex(geo.index);
      const mk = new T.MeshBasicMaterial({ color: new T.Color('#141f0b').convertSRGBToLinear(), side: T.BackSide }); mk.name = 'zver_kontur';
      const k = new T.Mesh(g2, mk); k.name = 'zver_kontur'; group.add(k);
    }
    const glaza = {};                                               // глаза нарисованы; для мимики — пустышки
    for (const n of ['eyeL', 'eyeR']) { const ob = new T.Object3D(); ob.name = n; group.add(ob); glaza[n] = ob; }
    return { group, glaza, stats: { vershin: nv } };
  }

  global.PixelMechZver = {
    build: (T, o) => ((global.PixelMechZverRisunok && !(o && o.lepka)) ? buildRisunok(T, o || {}, global.PixelMechZverRisunok) : build(T, o)),
    buildLepka: build
  };
})(typeof window !== 'undefined' ? window : globalThis);
