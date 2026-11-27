// Звук гостя в AudioWorklet (Quest Claude Bridge 2, 2.5.0).
// «zahvat» — микрофон → 48 кГц → работнику Opus (по своему порту), пока не выключен.
// «vyvod» — смесь из шлема (PCM 48 кГц от работника) → динамики, с запасом, который подстраивается
// сам: опустел посреди речи — запас +20 мс (до 250), 10 с ровно — −10 мс (до 50); накопилось
// больше запаса + 60 мс — читаем на 2 % быстрее, больше + 300 мс — лишнее прочь (как в шлеме, Bufer.kt).
'use strict';

class Zahvat extends AudioWorkletProcessor {
  constructor() {
    super();
    this.port2 = null;
    this.vkl = true;
    this.shag = 48000 / sampleRate;          // во сколько раз 48 кГц чаще частоты устройства
    this.faza = 0;
    this.prev = 0;
    this.buf = new Float32Array(960);
    this.n = 0;
    this.port.onmessage = (e) => {
      if (e.data && e.data.t === 'port') this.port2 = e.ports[0];
      else if (e.data && e.data.t === 'vkl') this.vkl = !!e.data.vkl;
    };
  }

  process(inputs) {
    const vh = inputs[0];
    if (!vh || !vh.length || !this.port2 || !this.vkl) return true;
    const ch = vh[0];
    const n = ch.length;
    if (this.shag === 1) {
      for (let i = 0; i < n; i++) this.dob(vh.length > 1 ? (ch[i] + vh[1][i]) / 2 : ch[i]);
    } else {
      for (let i = 0; i < n; i++) {                // линейно на 48 кГц
        const x = vh.length > 1 ? (ch[i] + vh[1][i]) / 2 : ch[i];
        while (this.faza < 1) { this.dob(this.prev + (x - this.prev) * this.faza); this.faza += 1 / this.shag; }
        this.faza -= 1;
        this.prev = x;
      }
    }
    return true;
  }

  dob(v) {
    this.buf[this.n++] = v;
    if (this.n === this.buf.length) {
      this.port2.postMessage(this.buf, [this.buf.buffer]);
      this.buf = new Float32Array(960);
      this.n = 0;
    }
  }
}

class Vyvod extends AudioWorkletProcessor {
  constructor() {
    super();
    this.cap = 48000 * 2;
    this.a = new Float32Array(this.cap);
    this.r = 0; this.w = 0; this.size = 0;
    this.frac = 0;
    this.igraet = false;
    this.ms = (m) => Math.round(48 * m);
    this.tsel = this.ms(80);
    this.spokoyno = 0;
    this.propuski = 0;
    this.sbrosy = 0;
    this.shag0 = 48000 / sampleRate;
    this.otchet = 0;
    this.port.onmessage = (e) => {
      if (e.data && e.data.t === 'port') e.ports[0].onmessage = (ev) => this.dobavit(ev.data);
      else if (e.data && e.data.t === 'sbros') { this.r = this.w = this.size = 0; this.igraet = false; }
    };
  }

  dobavit(pcm) {
    for (let i = 0; i < pcm.length; i++) {
      this.a[this.w] = pcm[i];
      this.w = (this.w + 1) % this.cap;
      if (this.size < this.cap) this.size++; else this.r = (this.r + 1) % this.cap;
    }
  }

  process(inputs, outputs) {
    const out = outputs[0][0];
    const n = out.length;
    if (this.size > this.tsel + this.ms(300)) {          // сильно отстали — догоняем сразу
      const drop = this.size - this.tsel;
      this.r = (this.r + drop) % this.cap; this.size -= drop; this.sbrosy++;
    }
    if (!this.igraet) {
      if (this.size < Math.max(2, this.tsel)) { out.fill(0); return this.otch(n); }
      this.igraet = true;
    }
    const shag = this.size > this.tsel + this.ms(60) ? this.shag0 * 1.02 : this.shag0;
    for (let j = 0; j < n; j++) {
      if (this.size < 2) {                               // опустел посреди — тишина, запас больше
        this.igraet = false; this.propuski++;
        this.tsel = Math.min(this.ms(250), this.tsel + this.ms(20));
        this.spokoyno = 0;
        out.fill(0, j);
        return this.otch(n);
      }
      const s0 = this.a[this.r], s1 = this.a[(this.r + 1) % this.cap];
      out[j] = s0 + (s1 - s0) * this.frac;
      this.frac += shag;
      while (this.frac >= 1 && this.size >= 2) { this.frac -= 1; this.r = (this.r + 1) % this.cap; this.size--; }
    }
    this.spokoyno += n;
    if (this.spokoyno > sampleRate * 10) { this.spokoyno = 0; this.tsel = Math.max(this.ms(50), this.tsel - this.ms(10)); }
    return this.otch(n);
  }

  otch(n) {
    this.otchet += n;
    if (this.otchet > sampleRate) {                     // раз в секунду — для строки состояния
      this.otchet = 0;
      this.port.postMessage({ t: 'bufer', ms: Math.round(this.size / 48), tsel: Math.round(this.tsel / 48),
        propuski: this.propuski, sbrosy: this.sbrosy });
    }
    return true;
  }
}

registerProcessor('zahvat', Zahvat);
registerProcessor('vyvod', Vyvod);
