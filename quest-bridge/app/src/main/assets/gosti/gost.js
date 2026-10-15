// Страница гостя эфира Плюса (Quest Claude Bridge 2, 2.5.0; docs/GOSTI.md).
// Одно соединение WebSocket со шлемом (через туннель Cloudflare): управление — JSON, звук — Opus
// 20 мс (работник opus-rabota.js), своя камера — H.264 (WebCodecs) или JPEG, картинка эфира — H.264
// из шлема. Ключ — в части ссылки после # (на сервер в адресе не уходит).
'use strict';
(() => {
  const RU = /^(ru|uk|be|kk)/i.test(navigator.language || '');
  const T = RU ? {
    zag: 'эфир', vhodZag: 'Вход в эфир', vhodPod: 'Тебя зовут в стрим Александра. Нужны камера и микрофон.',
    vhodZagM: 'Вход: модератор', vhodPodM: 'Ты модератор: впускаешь гостей и делишься их ссылкой. Камера и микрофон — по желанию.',
    vhodZagH: 'Пульт эфира', vhodPodH: 'Твой пульт: кто в комнате, впустить, убрать с экрана, выгнать.',
    imya: 'Имя в эфире', kamera: 'Камера', mikrofon: 'Микрофон', naushniki: '🎧 Надень наушники — иначе эфир услышит эхо.',
    razreshit: 'Включить камеру и микрофон', voyti: 'Войти', pultOtkryt: 'Открыть пульт',
    zhdiZag: 'Ждём, пока впустят…', zhdiPod: 'Модератор или Александр сейчас тебя впустят. Не закрывай страницу.',
    slyshno: '🔊 Слышно', igra: '🎮 Игра', lyudi: 'В эфире', pult: 'Пульт', priglasit: 'Пригласить гостя',
    moderSsylka: 'Ссылка модератора', novaya: 'Новая ссылка гостя', snova: 'Войти снова',
    netKlyucha: 'В ссылке нет ключа. Попроси ссылку ещё раз.',
    neumeet: 'Этот браузер не умеет звук для эфира (нужны AudioWorklet и WebAssembly). Открой ссылку в Safari или Chrome.',
    netDostupa: 'Браузер не дал камеру или микрофон. Разреши доступ в настройках сайта и обнови страницу.',
    tolkoZvuk: 'Камера недоступна — входишь только со звуком.',
    prevyuZhdi: 'Картинка эфира появится, когда начнётся эфир.', prevyuNet: 'Этот браузер не показывает картинку эфира — смотри стрим на Twitch, звук идёт отсюда.',
    prevyuVykl: 'Картинка эфира выключена (тесная сеть шлема) — смотри стрим на Twitch.',
    vKadre: 'Ты в кадре.', zaKadrom: 'Ты за кадром — слушаешь и управляешь.', skryt: 'Модератор убрал тебя с экрана — звук идёт.',
    zaglushen: 'Модератор выключил тебе микрофон.', mestNet: 'Мест в кадре нет — включить камеру или микрофон нельзя.',
    efirIdet: 'Эфир идёт.', efirNet: 'Эфир ещё не начался — можно поговорить.',
    perepodkl: 'Связь прервалась — переподключаюсь…',
    prichina: { vygnali: 'Тебя убрали из эфира.', zakryto: 'Комната закрыта — эфир с гостями закончился.', klyuch: 'Ссылка устарела или неверна. Попроси новую.',
      otkaz: 'Тебя не впустили.', ochered: 'Слишком много ждущих. Попробуй позже.', polno: 'В комнате нет мест.', mest_net: 'Мест в кадре нет.',
      bystro: 'Соединение закрыто: слишком много данных.', sam: 'Ты вышел из эфира.' },
    konecZag: 'Эфир для тебя закончился', vpustit: 'Впустить', otklonit: 'Нет', skrytK: 'Убрать', pokazatK: 'Показать',
    zaglK: 'Без звука', vklK: 'Со звуком', vygnatK: 'Выгнать', zhdet: 'ждёт', moder: 'модератор', hozyain: 'хозяин', gost: 'гость',
    vpuskA: 'Впуск: сам', vpuskR: 'Впуск: вручную', mest: 'Мест в кадре: ', skopirovano: 'Ссылка скопирована.',
    poslatModeru: 'Ссылка модератора', poslatGostyu: 'Заходи в эфир Плюса как гость:', tekstModeru: 'Ты модератор эфира Плюса:',
    netSsylki: 'Ссылка будет, когда поднимется туннель.', vy: ' (ты)', rtt: 'связь', bufer: 'буфер', ms: 'мс',
    neSoedinilos: 'Не удалось подключиться к эфиру. Проверь интернет или попроси у Александра новую ссылку.',
  } : {
    zag: 'live', vhodZag: 'Join the stream', vhodPod: 'You are invited to Alexander\'s stream. Camera and microphone needed.',
    vhodZagM: 'Join as moderator', vhodPodM: 'You are the moderator: admit guests and share their link. Camera and mic are optional.',
    vhodZagH: 'Stream control', vhodPodH: 'Your control panel: who is in, admit, hide, kick.',
    imya: 'Your name on stream', kamera: 'Camera', mikrofon: 'Microphone', naushniki: '🎧 Wear headphones — otherwise the stream hears an echo.',
    razreshit: 'Turn on camera and microphone', voyti: 'Join', pultOtkryt: 'Open control panel',
    zhdiZag: 'Waiting to be admitted…', zhdiPod: 'The moderator or Alexander will let you in. Keep this page open.',
    slyshno: '🔊 Volume', igra: '🎮 Game', lyudi: 'On stream', pult: 'Control', priglasit: 'Invite a guest',
    moderSsylka: 'Moderator link', novaya: 'New guest link', snova: 'Join again',
    netKlyucha: 'The link has no key. Ask for the link again.',
    neumeet: 'This browser cannot do stream audio (needs AudioWorklet and WebAssembly). Open the link in Safari or Chrome.',
    netDostupa: 'The browser blocked camera or microphone. Allow access in site settings and reload.',
    tolkoZvuk: 'Camera unavailable — joining with audio only.',
    prevyuZhdi: 'The stream picture appears when the stream starts.', prevyuNet: 'This browser cannot show the stream picture — watch on Twitch, audio comes from here.',
    prevyuVykl: 'Stream picture is off (tight headset network) — watch on Twitch.',
    vKadre: 'You are on screen.', zaKadrom: 'You are off screen — listening and moderating.', skryt: 'The moderator hid you from the screen — audio still on.',
    zaglushen: 'The moderator muted your microphone.', mestNet: 'No free spots on screen — camera and mic cannot be turned on.',
    efirIdet: 'Stream is live.', efirNet: 'Stream has not started yet — you can talk.',
    perepodkl: 'Connection lost — reconnecting…',
    prichina: { vygnali: 'You were removed from the stream.', zakryto: 'The room is closed — the guest session is over.', klyuch: 'This link is outdated or wrong. Ask for a new one.',
      otkaz: 'You were not admitted.', ochered: 'Too many people waiting. Try later.', polno: 'The room is full.', mest_net: 'No free spots on screen.',
      bystro: 'Connection closed: too much data.', sam: 'You left the stream.' },
    konecZag: 'The stream is over for you', vpustit: 'Admit', otklonit: 'No', skrytK: 'Hide', pokazatK: 'Show',
    zaglK: 'Mute', vklK: 'Unmute', vygnatK: 'Kick', zhdet: 'waiting', moder: 'moderator', hozyain: 'host', gost: 'guest',
    vpuskA: 'Admission: auto', vpuskR: 'Admission: manual', mest: 'Spots on screen: ', skopirovano: 'Link copied.',
    poslatModeru: 'Moderator link', poslatGostyu: 'Join Plus\'s stream as a guest:', tekstModeru: 'You are the moderator of Plus\'s stream:',
    netSsylki: 'The link appears when the tunnel is up.', vy: ' (you)', rtt: 'link', bufer: 'buffer', ms: 'ms',
    neSoedinilos: 'Could not connect to the stream. Check your internet or ask Alexander for a new link.',
  };
  const CVET = ['#ff8cc8', '#ffaa5a', '#bea0ff'];
  const $ = (id) => document.getElementById(id);
  const pokaz = (id, da) => { $(id).hidden = !da; };

  // ---------- ключ ----------
  const hash = new URLSearchParams(location.hash.slice(1));
  let klyuch = hash.get('k') || sessionStorage.getItem('plus-k') || '';
  const rolPodskazka = hash.get('r') || sessionStorage.getItem('plus-r') || '';
  if (klyuch) { sessionStorage.setItem('plus-k', klyuch); sessionStorage.setItem('plus-r', rolPodskazka); }
  const proba = /[?&]proba\b/.test(location.search);                   // проверка на столе: состояние наружу
  if (location.hash) history.replaceState(null, '', location.pathname + location.search);   // ключ — не в адресной строке

  const S = {
    ws: null, rol: '', sost: '', id: 0, zheton: sessionStorage.getItem('plus-z') || '', vnutri: false,
    kamera: true, mikrofon: true, zaglushen: false, skryt: false, cvet: -1, efir: false,
    ctx: null, rabota: null, zahvat: null, vyvod: null, usil: null, stream: null, facing: 'user',
    venc: null, vencCfg: null, nuzhenKlyuch: true, avcc: null, kadrT: null, kadrN: 0, jpegZanyat: false,
    vdec: null, vdecKodek: '', vdecAvcc: false, zhdemKlyuch: true, kodek: '',
    ushel: false, perepodkl: 0, pingT: null, rttMs: 0, buferMs: 0, ssylkaGost: '', ssylkaModer: '',
    lyudi: [], govoryat: new Set(), lock: null, mest: 2, vpusk: 'ruchnoy',
  };
  const umeet = {
    zvuk: typeof WebAssembly === 'object' && 'AudioWorkletNode' in window,
    venc: 'VideoEncoder' in window && 'VideoFrame' in window,
    vdec: 'VideoDecoder' in window && 'EncodedVideoChunk' in window,
  };

  // ---------- тексты ----------
  function teksty() {
    document.documentElement.lang = RU ? 'ru' : 'en';
    $('zagolovok').textContent = T.zag;
    const zag = rolPodskazka === 'h' ? T.vhodZagH : rolPodskazka === 'm' ? T.vhodZagM : T.vhodZag;
    const pod = rolPodskazka === 'h' ? T.vhodPodH : rolPodskazka === 'm' ? T.vhodPodM : T.vhodPod;
    $('tVhodZag').textContent = zag; $('tVhodPod').textContent = pod;
    $('tImya').textContent = T.imya; $('tKamera').textContent = T.kamera; $('tMikrofon').textContent = T.mikrofon;
    $('tNaushniki').textContent = T.naushniki; $('knRazreshit').textContent = T.razreshit;
    $('knVoyti').textContent = rolPodskazka === 'h' ? T.pultOtkryt : T.voyti;
    $('tZhdiZag').textContent = T.zhdiZag; $('tZhdiPod').textContent = T.zhdiPod;
    $('tSlyshno').textContent = T.slyshno; $('tIgra').textContent = T.igra; $('tLyudi').textContent = T.lyudi;
    $('tPult').textContent = T.pult; $('tPriglasit').textContent = T.priglasit; $('tModerSsylka').textContent = T.moderSsylka;
    $('tNovaya').textContent = T.novaya; $('knSnova').textContent = T.snova;
    document.title = 'Plus · ' + zag;
  }

  function ekran(id) {
    for (const e of ['ekranVhod', 'ekranZhdi', 'ekranEfir', 'ekranKonec']) pokaz(e, e === id);
    pokaz('pult', (id === 'ekranEfir' || id === 'ekranZhdi') && (S.rol === 'moder' || S.rol === 'hozyain') && S.vnutri);
  }

  function oshibkaVhoda(t) { const e = $('vhodOshibka'); e.textContent = t; e.hidden = !t; }

  function zametka(t) { const e = $('zametka'); e.textContent = t; e.hidden = !t; }

  // ---------- звук ----------
  async function zvukVkl() {
    const AC = window.AudioContext || window.webkitAudioContext;
    let ctx;
    try { ctx = new AC({ sampleRate: 48000, latencyHint: 'interactive' }); } catch (x) { ctx = new AC({ latencyHint: 'interactive' }); }
    S.ctx = ctx;
    await ctx.audioWorklet.addModule('zvuk-worklet.js');
    const rab = new Worker('opus-rabota.js');
    S.rabota = rab;
    const vZahvat = new MessageChannel(), vVyvod = new MessageChannel();
    S.zahvat = new AudioWorkletNode(ctx, 'zahvat', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
    S.vyvod = new AudioWorkletNode(ctx, 'vyvod', { numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [1] });
    S.zahvat.port.postMessage({ t: 'port' }, [vZahvat.port1]);
    S.vyvod.port.postMessage({ t: 'port' }, [vVyvod.port2]);
    S.vyvod.port.onmessage = (e) => { if (e.data.t === 'bufer') { S.buferMs = e.data.ms; stroka(); } };
    const tiho = ctx.createGain(); tiho.gain.value = 0;                    // захват ничего не играет, но должен считаться
    S.zahvat.connect(tiho).connect(ctx.destination);
    S.usil = ctx.createGain(); S.usil.gain.value = 1;
    S.vyvod.connect(S.usil);
    await vyvodVDinamiki(ctx, S.usil);
    await new Promise((ok, ne) => {
      rab.onmessage = (e) => {
        const m = e.data;
        if (m.t === 'gotov') ok();
        else if (m.t === 'oshibka') ne(new Error(m.tekst));
        else if (m.t === 'paket') poslatZvuk(m.d);
        else if (m.t === 'uroven') uroven(m.db);
      };
      rab.postMessage({ t: 'init', bitrate: 48000 }, [vZahvat.port2, vVyvod.port1]);
    });
    if (ctx.state === 'suspended') await ctx.resume();
  }

  // Chrome на компьютере вычитает эхо только из звука WebRTC — смесь гостя пускаем через петлю
  // из двух RTCPeerConnection. Телефоны (iOS, Android) и Firefox вычитают и так.
  async function vyvodVDinamiki(ctx, uzel) {
    const ua = navigator.userAgent;
    const chromeKomp = /Chrome\//.test(ua) && !/Mobile|Android|Edg\/|OPR\//.test(ua) && !('ontouchstart' in window) && 'RTCPeerConnection' in window;
    if (!chromeKomp) { uzel.connect(ctx.destination); return; }
    try {
      const dest = ctx.createMediaStreamDestination();
      uzel.connect(dest);
      const p1 = new RTCPeerConnection(), p2 = new RTCPeerConnection();
      p1.onicecandidate = (e) => e.candidate && p2.addIceCandidate(e.candidate);
      p2.onicecandidate = (e) => e.candidate && p1.addIceCandidate(e.candidate);
      p2.ontrack = (e) => { const a = $('petlya'); a.srcObject = e.streams[0]; a.play().catch(() => {}); };
      dest.stream.getTracks().forEach((t) => p1.addTrack(t, dest.stream));
      const luchshe = (sdp) => sdp.replace(/useinbandfec=1/g, 'useinbandfec=1;stereo=0;maxaveragebitrate=128000');
      const o = await p1.createOffer(); o.sdp = luchshe(o.sdp);
      await p1.setLocalDescription(o); await p2.setRemoteDescription(o);
      const a = await p2.createAnswer(); a.sdp = luchshe(a.sdp);
      await p2.setLocalDescription(a); await p1.setRemoteDescription(a);
      S.petlya = [p1, p2];
    } catch (x) {
      uzel.connect(ctx.destination);
    }
  }

  function uroven(db) {
    const p = Math.max(0, Math.min(100, (db + 60) * 100 / 50));
    $('uroven').style.width = p + '%'; $('uroven2').style.width = p + '%';
  }

  function poslatZvuk(d) {
    const ws = S.ws;
    if (!ws || ws.readyState !== 1 || !S.vnutri || !S.mikrofon || S.zaglushen || S.cvet < 0) return;
    if (ws.bufferedAmount > 600000) return;                             // сеть встала — не копим
    ws.send(d);
  }

  // ---------- камера и микрофон ----------
  async function mediaVkl() {
    const zvuk = { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 };
    const video = { width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 15, max: 24 }, facingMode: S.facing };
    let s = null;
    try {
      s = await navigator.mediaDevices.getUserMedia({ audio: zvuk, video: S.kamera ? video : false });
    } catch (x) {
      try { s = await navigator.mediaDevices.getUserMedia({ audio: zvuk }); S.kamera = false; oshibkaVhoda(T.tolkoZvuk); }
      catch (x2) { oshibkaVhoda(T.netDostupa); throw x2; }
    }
    S.stream = s;
    const src = S.ctx.createMediaStreamSource(new MediaStream(s.getAudioTracks()));
    src.connect(S.zahvat);
    S.src = src;
    pokazatSebya();
    const kamer = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === 'videoinput').length;
    pokaz('knPovernut', kamer > 1); pokaz('knPov2', kamer > 1);
  }

  function pokazatSebya() {
    const vt = S.stream && S.stream.getVideoTracks()[0];
    for (const [v, n] of [['ya', 'yaNet'], ['ya2', 'ya2Net']]) {
      $(v).srcObject = vt ? new MediaStream([vt]) : null;
      $(v).classList.toggle('zad', S.facing === 'environment');
      $(n).classList.toggle('vidno', !vt || !S.kamera);
    }
    knopki();
  }

  async function povernut() {
    S.facing = S.facing === 'user' ? 'environment' : 'user';
    const stary = S.stream && S.stream.getVideoTracks()[0];
    try {
      const n = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 15 }, facingMode: S.facing } });
      if (stary) { S.stream.removeTrack(stary); stary.stop(); }
      S.stream.addTrack(n.getVideoTracks()[0]);
      S.nuzhenKlyuch = true;
      pokazatSebya();
    } catch (x) { S.facing = S.facing === 'user' ? 'environment' : 'user'; }
  }

  function knopki() {
    for (const id of ['knKamera', 'knKam2']) $(id).classList.toggle('vkl', S.kamera);
    for (const id of ['knMikrofon', 'knMik2']) $(id).classList.toggle('vkl', S.mikrofon && !S.zaglushen);
  }

  function kameraTumbler() {
    S.kamera = !S.kamera;
    const vt = S.stream && S.stream.getVideoTracks()[0];
    if (vt) vt.enabled = S.kamera;
    if (S.kamera) S.nuzhenKlyuch = true;
    pokazatSebya();
    otpravitSam();
  }

  function mikrofonTumbler() {
    S.mikrofon = !S.mikrofon;
    const at = S.stream && S.stream.getAudioTracks()[0];
    if (at) at.enabled = S.mikrofon;
    if (S.zahvat) S.zahvat.port.postMessage({ t: 'vkl', vkl: S.mikrofon });
    knopki();
    otpravitSam();
  }

  function otpravitSam(dop) {
    if (S.ws && S.ws.readyState === 1 && S.vnutri)
      S.ws.send(JSON.stringify(Object.assign({ t: 'sam', kamera: S.kamera && !!(S.stream && S.stream.getVideoTracks()[0]), mikrofon: S.mikrofon }, dop || {})));
  }

  // ---------- своя камера → шлем: H.264 (WebCodecs) или JPEG ----------
  const holst = document.createElement('canvas');
  let holstCtx = null;
  async function kameraVKodirovshik() {
    if (!umeet.venc) return;
    for (const codec of ['avc1.42E01F', 'avc1.42001F', 'avc1.4D401F', 'vp8']) {   // H.264 — шлему легче; нет — VP8
      const cfg = { codec, width: 360, height: 360, bitrate: codec === 'vp8' ? 350000 : 300000, framerate: 15, latencyMode: 'realtime' };
      if (codec !== 'vp8') cfg.avc = { format: 'annexb' };
      try { if ((await VideoEncoder.isConfigSupported(cfg)).supported) { S.vencCfg = cfg; break; } } catch (x) { }
    }
  }

  function vencNovyy() {
    S.venc = new VideoEncoder({
      output: (chunk, meta) => {
        if (meta && meta.decoderConfig && meta.decoderConfig.description) S.avcc = razborAvcC(new Uint8Array(meta.decoderConfig.description));
        const d = new Uint8Array(chunk.byteLength);
        chunk.copyTo(d);
        const key = chunk.type === 'key';
        const vp8 = S.vencCfg.codec === 'vp8';
        const ab = vp8 ? d : annexB(d, key);
        if (!ab) return;
        const out = new Uint8Array(6 + ab.length);
        out[0] = vp8 ? 4 : 2; out[1] = key ? 1 : 0;
        const ms = Math.floor(chunk.timestamp / 1000) >>> 0;
        out[2] = ms >>> 24; out[3] = (ms >>> 16) & 255; out[4] = (ms >>> 8) & 255; out[5] = ms & 255;
        out.set(ab, 6);
        if (S.ws && S.ws.readyState === 1) S.ws.send(out);
      },
      error: () => { S.venc = null; S.nuzhenKlyuch = true; },
    });
    S.venc.configure(S.vencCfg);
  }

  function kadrKamery() {
    const ws = S.ws;
    const v = $('ya2');
    if (!ws || ws.readyState !== 1 || !S.vnutri || !S.kamera || S.cvet < 0 || S.skryt) return;
    if (!v.videoWidth || v.readyState < 2) return;
    if (ws.bufferedAmount > 150000) { S.nuzhenKlyuch = true; return; }        // сеть тесная — кадр мимо
    const sw = v.videoWidth, sh = v.videoHeight, st = Math.min(sw, sh);
    const sz = S.vencCfg ? 360 : 240;
    if (holst.width !== sz) { holst.width = sz; holst.height = sz; }
    const c = holstCtx || (holstCtx = holst.getContext('2d'));
    c.drawImage(v, (sw - st) / 2, (sh - st) / 2, st, st, 0, 0, sz, sz);
    const t = performance.now();
    if (S.vencCfg) {
      if (!S.venc || S.venc.state === 'closed') vencNovyy();
      if (S.venc.encodeQueueSize > 2) return;
      const f = new VideoFrame(holst, { timestamp: Math.round(t * 1000) });
      const key = S.nuzhenKlyuch || S.kadrN % 30 === 0;
      try { S.venc.encode(f, { keyFrame: key }); S.nuzhenKlyuch = false; S.kadrN++; } catch (x) { S.venc = null; }
      f.close();
    } else if (!S.jpegZanyat && S.kadrN++ % 2 === 0) {                   // JPEG — 7–8 к/с
      S.jpegZanyat = true;
      holst.toBlob((b) => {
        S.jpegZanyat = false;
        if (!b || !S.ws || S.ws.readyState !== 1) return;
        b.arrayBuffer().then((buf) => {
          const out = new Uint8Array(5 + buf.byteLength);
          const ms = Math.floor(t) >>> 0;
          out[0] = 3; out[1] = ms >>> 24; out[2] = (ms >>> 16) & 255; out[3] = (ms >>> 8) & 255; out[4] = ms & 255;
          out.set(new Uint8Array(buf), 5);
          S.ws.send(out);
        });
      }, 'image/jpeg', 0.7);
    }
  }

  // Annex B ⇄ AVC: кодировщик мог отдать длины вместо стартовых кодов (часть Safari) — переделываем.
  function razborAvcC(d) {
    try {
      let i = 5; const sps = [], pps = [];
      const nS = d[i++] & 31;
      for (let j = 0; j < nS; j++) { const n = (d[i] << 8) | d[i + 1]; sps.push(d.subarray(i + 2, i + 2 + n)); i += 2 + n; }
      const nP = d[i++];
      for (let j = 0; j < nP; j++) { const n = (d[i] << 8) | d[i + 1]; pps.push(d.subarray(i + 2, i + 2 + n)); i += 2 + n; }
      return { sps, pps, len: (d[4] & 3) + 1 };
    } catch (x) { return null; }
  }

  // Описания (avcC) нет — кодировщик уже отдаёт Annex B; есть — длины: по первым байтам не угадать
  // (длина 256–511 выглядит как стартовый код 00 00 01), поэтому решает описание.
  function annexB(d, key) {
    const a = S.avcc; if (!a) return d;
    const kuski = [];
    const sk = new Uint8Array([0, 0, 0, 1]);
    if (key) for (const x of a.sps.concat(a.pps)) kuski.push(sk, x);
    let i = 0;
    while (i + a.len <= d.length) {
      let n = 0; for (let j = 0; j < a.len; j++) n = (n << 8) | d[i + j];
      i += a.len;
      if (n <= 0 || i + n > d.length) break;
      kuski.push(sk, d.subarray(i, i + n)); i += n;
    }
    const vsego = kuski.reduce((s, x) => s + x.length, 0);
    const out = new Uint8Array(vsego); let p = 0;
    for (const x of kuski) { out.set(x, p); p += x.length; }
    return out;
  }

  // ---------- картинка эфира из шлема ----------
  async function videoNastroit(kodek) {
    S.kodek = kodek;
    if (!umeet.vdec) return;
    const cfg = { codec: kodek, optimizeForLatency: true };
    try { if (!(await VideoDecoder.isConfigSupported(cfg)).supported) { prevyuTekst(T.prevyuNet); return; } } catch (x) { prevyuTekst(T.prevyuNet); return; }
    vdecNovyy(false);
  }

  function vdecNovyy(avcc) {
    try { if (S.vdec && S.vdec.state !== 'closed') S.vdec.close(); } catch (x) { }
    const c = $('prevyu'), g = c.getContext('2d');
    S.vdecAvcc = avcc;
    S.vdecSps = null;
    S.vdec = new VideoDecoder({
      output: (f) => {
        if (c.width !== f.displayWidth) { c.width = f.displayWidth; c.height = f.displayHeight; }
        g.drawImage(f, 0, 0, c.width, c.height);
        f.close();
        prevyuTekst('');
      },
      error: () => {
        S.oshibokVdec = (S.oshibokVdec || 0) + 1;
        S.vdec = null; S.zhdemKlyuch = true;                              // со следующего ключевого — заново
      },
    });
    if (!avcc) S.vdec.configure({ codec: S.kodek, optimizeForLatency: true });
    S.zhdemKlyuch = true;
    prositKlyuch();
  }

  function prositKlyuch() { if (S.ws && S.ws.readyState === 1) S.ws.send('{"t":"kluch"}'); }

  function kadrEfira(d) {
    if (!S.kodek || !umeet.vdec) return;
    const key = (d[1] & 1) === 1;
    const ms = ((d[2] << 24) >>> 0) + (d[3] << 16) + (d[4] << 8) + d[5];
    let tel = d.subarray(6);
    if (!S.vdec || S.vdec.state === 'closed') { if (!key) return; vdecNovyy((S.oshibokVdec || 0) >= 2); }
    if (S.zhdemKlyuch && !key) return;
    if (S.vdecAvcc) {                                                     // запасной путь: длины + описание
      const r = vAvcc(tel, key);
      if (!r) return;
      if (S.vdec.state === 'unconfigured') S.vdec.configure({ codec: S.kodek, optimizeForLatency: true, description: r.opisanie });
      tel = r.d;
    }
    try {
      S.vdec.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: ms * 1000, data: tel }));
      S.zhdemKlyuch = false;
    } catch (x) { S.vdec = null; S.zhdemKlyuch = true; prositKlyuch(); }
  }

  function vAvcc(d, key) {
    const nal = [];
    let i = 0, nach = -1;
    while (i + 3 <= d.length) {
      if (d[i] === 0 && d[i + 1] === 0 && (d[i + 2] === 1 || (d[i + 2] === 0 && i + 3 < d.length && d[i + 3] === 1))) {
        if (nach >= 0) nal.push(d.subarray(nach, i));
        i += d[i + 2] === 1 ? 3 : 4; nach = i;
      } else i++;
    }
    if (nach >= 0) nal.push(d.subarray(nach));
    let sps = null, pps = null;
    const ost = [];
    for (const x of nal) { const t = x[0] & 31; if (t === 7) sps = x; else if (t === 8) pps = x; else ost.push(x); }
    let opisanie = null;
    if (key && sps && pps) {
      opisanie = new Uint8Array(11 + sps.length + pps.length);
      opisanie.set([1, sps[1], sps[2], sps[3], 0xff, 0xe1, sps.length >> 8, sps.length & 255]);
      opisanie.set(sps, 8);
      const p = 8 + sps.length;
      opisanie.set([1, pps.length >> 8, pps.length & 255], p);
      opisanie.set(pps, p + 3);
    }
    const n = ost.reduce((s, x) => s + 4 + x.length, 0);
    const out = new Uint8Array(n); let p = 0;
    for (const x of ost) { out[p] = x.length >>> 24; out[p + 1] = (x.length >>> 16) & 255; out[p + 2] = (x.length >>> 8) & 255; out[p + 3] = x.length & 255; out.set(x, p + 4); p += 4 + x.length; }
    return { d: out, opisanie };
  }

  function prevyuTekst(t) { const e = $('prevyuNet'); e.textContent = t; e.hidden = !t; }

  // ---------- соединение ----------
  function soedinit() {
    const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
    ws.binaryType = 'arraybuffer';
    S.ws = ws;
    ws.onopen = () => {
      S.perepodkl = 0;
      ws.send(JSON.stringify({ t: 'vhod', k: klyuch, imya: $('imya').value.trim(), zheton: S.zheton,
        kamera: S.kamera && !!(S.stream && S.stream.getVideoTracks()[0]), mikrofon: S.mikrofon && !!S.stream, h264: umeet.vdec }));
      if (S.pingT) clearInterval(S.pingT);
      S.pingT = setInterval(() => { if (ws.readyState === 1) ws.send(JSON.stringify({ t: 'ping', c: Date.now() })); }, 4000);
    };
    ws.onmessage = (e) => {
      if (typeof e.data === 'string') { soobshchenie(JSON.parse(e.data)); return; }
      const d = new Uint8Array(e.data);
      if (d[0] === 11 && S.rabota) { const b = e.data.slice(3); S.rabota.postMessage({ t: 'dekod', d: b }, [b]); }
      else if (d[0] === 12) kadrEfira(d);
    };
    ws.onclose = () => {
      clearInterval(S.pingT);
      if (S.ws !== ws) return;
      S.ws = null;
      if (S.ushel) return;
      if (S.sost === 'vnutri' || S.sost === 'zhdet') {                   // оборвалось — назад сами
        zametka(T.perepodkl);
        S.perepodkl++;
        if (S.perepodkl > 12) { konec(T.perepodkl); return; }
        setTimeout(soedinit, Math.min(15000, 1000 * Math.pow(1.6, S.perepodkl)));
      } else if (!S.sost) {                                                 // ещё не вошли: пару раз ещё, потом — сказать
        S.perepodkl++;
        if (S.perepodkl > 3) { konec(T.neSoedinilos); return; }
        setTimeout(soedinit, 1500 * S.perepodkl);
      }
    };
  }

  function soobshchenie(m) {
    switch (m.t) {
      case 'privet':
        S.rol = m.rol; S.sost = m.sost; S.id = m.id; S.cvet = m.cvet; S.efir = !!m.efir; S.mest = m.mest;
        S.zheton = m.zheton || ''; sessionStorage.setItem('plus-z', S.zheton);
        S.vnutri = m.sost === 'vnutri';
        if (!m.kamera && S.kamera && S.rol !== 'hozyain') { S.kamera = false; pokazatSebya(); }
        if (!m.mikrofon && S.mikrofon && S.rol !== 'hozyain') { S.mikrofon = false; knopki(); }
        zametka('');
        if (S.vnutri) { ekran('ekranEfir'); sostoyanie(); S.nuzhenKlyuch = true; if (S.kodek) prositKlyuch(); budilnik(); }
        else ekran('ekranZhdi');
        pokaz('paneli', S.rol !== 'hozyain'); pokaz('prevyuBlok', S.rol !== 'hozyain');
        $('tochka').classList.toggle('efir', S.efir);
        if (!umeet.vdec) prevyuTekst(T.prevyuNet); else if (!S.kodek) prevyuTekst(S.efir ? '' : T.prevyuZhdi);
        break;
      case 'ssylki':
        S.ssylkaGost = m.gost || ''; S.ssylkaModer = m.moder || '';
        pokaz('knModer', !!m.moder);
        break;
      case 'sostav':
        S.lyudi = m.lyudi || []; S.mest = m.mest || S.mest; if (m.vpusk) S.vpusk = m.vpusk;
        { const ya = S.lyudi.find((x) => x.id === S.id); if (ya) { S.cvet = ya.cvet; S.skryt = ya.skryt; S.zaglushen = ya.zaglushen; } }
        risovatLyudey(); risovatPult(); sostoyanie(); knopki();
        break;
      case 'govoryat':
        S.govoryat = new Set(m.id || []); risovatLyudey();
        break;
      case 'video': videoNastroit(m.kodek); break;
      case 'kluch': S.nuzhenKlyuch = true; break;
      case 'zaglushen': S.zaglushen = !!m.da; knopki(); sostoyanie(); break;
      case 'skryt': S.skryt = !!m.da; sostoyanie(); break;
      case 'mest_net': zametka(T.mestNet); break;
      case 'efir': S.efir = !!m.idyot; $('tochka').classList.toggle('efir', S.efir); sostoyanie();
        if (!S.efir) { S.kodek = ''; prevyuTekst(T.prevyuZhdi); } break;
      case 'pong': S.rttMs = Date.now() - m.c; stroka(); break;
      case 'proch': S.ushel = true; konec(T.prichina[m.prichina] || m.prichina); break;
      case 'upr_otvet':
        $('pultOtvet').textContent = m.otvet && m.otvet.ok === false ? (m.otvet.oshibka || '') : '';
        break;
    }
  }

  function sostoyanie() {
    if (!S.vnutri) return;
    const t = [];
    t.push(S.efir ? T.efirIdet : T.efirNet);
    if (S.rol !== 'hozyain') t.push(S.cvet >= 0 ? (S.skryt ? T.skryt : T.vKadre) : T.zaKadrom);
    if (S.zaglushen) t.push(T.zaglushen);
    zametka(t.join(' '));
  }

  function stroka() {
    const e = $('set'); e.hidden = false;
    e.textContent = T.rtt + ' ' + S.rttMs + ' ' + T.ms + (S.rol === 'hozyain' ? '' : ' · ' + T.bufer + ' ' + S.buferMs + ' ' + T.ms);
  }

  function konec(t) {
    S.ushel = true;
    try { if (S.ws) S.ws.close(); } catch (x) { }
    if (S.stream) S.stream.getTracks().forEach((x) => x.stop());
    if (S.ctx) S.ctx.close().catch(() => {});
    if (S.lock) S.lock.release().catch(() => {});
    $('konecZag').textContent = T.konecZag; $('konecPod').textContent = t || '';
    ekran('ekranKonec');
  }

  async function budilnik() {
    try { if ('wakeLock' in navigator && !S.lock) S.lock = await navigator.wakeLock.request('screen'); } catch (x) { }
  }

  // ---------- люди и пульт ----------
  function risovatLyudey() {
    const ul = $('lyudi'); ul.textContent = '';
    for (const x of S.lyudi) {
      if (x.sost !== 'vnutri' || x.rol === 'hozyain') continue;
      const li = document.createElement('li');
      li.classList.toggle('govorit', S.govoryat.has(x.id));
      const c = document.createElement('span'); c.className = 'cvet';
      c.style.background = x.cvet >= 0 ? CVET[x.cvet] : '#555'; c.style.color = c.style.background;
      const n = document.createElement('span'); n.className = 'imya'; n.textContent = x.imya + (x.id === S.id ? T.vy : '');
      const r = document.createElement('span'); r.className = 'rol';
      r.textContent = (x.rol === 'moder' ? T.moder + ' · ' : '') + (x.mikrofon && !x.zaglushen ? '🎤' : '🔇') + (x.kamera ? ' 📷' : '');
      li.append(c, n, r); ul.append(li);
    }
  }

  function knopka(tekst, cls, f) {
    const b = document.createElement('button'); b.type = 'button'; b.textContent = tekst; if (cls) b.className = cls;
    b.onclick = f; return b;
  }

  function upr(chto, id, dop) {
    if (S.ws && S.ws.readyState === 1) S.ws.send(JSON.stringify({ t: 'upr', chto, id, dop }));
  }

  function risovatPult() {
    if (!(S.rol === 'moder' || S.rol === 'hozyain') || !S.vnutri) return;
    const zh = $('zhdut'); zh.textContent = '';
    for (const x of S.lyudi.filter((y) => y.sost === 'zhdet')) {
      const d = document.createElement('div'); d.className = 'kto';
      const b = document.createElement('b'); b.textContent = '⏳ ' + x.imya;
      d.append(b, knopka(T.vpustit, 'da', () => upr('vpustit', x.id)), knopka(T.otklonit, 'kr', () => upr('otklonit', x.id)));
      zh.append(d);
    }
    const pl = $('pultLyudi'); pl.textContent = '';
    for (const x of S.lyudi.filter((y) => y.sost === 'vnutri' && y.rol !== 'hozyain' && y.id !== S.id)) {
      const d = document.createElement('div'); d.className = 'pultKto';
      const b = document.createElement('b'); b.textContent = x.imya + ' · ' + (T[x.rol] || x.rol);
      if (x.cvet >= 0) b.style.color = CVET[x.cvet];
      d.append(b,
        knopka(x.skryt ? T.pokazatK : T.skrytK, '', () => upr(x.skryt ? 'pokazat' : 'skryt', x.id)),
        knopka(x.zaglushen ? T.vklK : T.zaglK, '', () => upr(x.zaglushen ? 'vklyuchit' : 'zaglushit', x.id)));
      if (!(S.rol === 'moder' && x.rol === 'moder')) d.append(knopka(T.vygnatK, 'kr', () => upr('vygnat', x.id)));
      pl.append(d);
    }
    pokaz('hozyain', S.rol === 'hozyain');
    $('knVpusk').textContent = S.vpusk === 'avto' ? T.vpuskA : T.vpuskR;
    $('knMest').textContent = T.mest + S.mest;
  }

  async function podelitsya(ssylka, tekst) {
    if (!ssylka) { $('pultOtvet').textContent = T.netSsylki; return; }
    if (navigator.share) { try { await navigator.share({ title: 'Plus', text: tekst, url: ssylka }); return; } catch (x) { if (x && x.name === 'AbortError') return; } }
    try { await navigator.clipboard.writeText(ssylka); $('pultOtvet').textContent = T.skopirovano; }
    catch (x) { prompt('', ssylka); }
  }

  // ---------- кнопки ----------
  function knopkiVhoda() {
    $('imya').value = localStorage.getItem('plus-imya') || '';
    $('knKamera').onclick = kameraTumbler;
    $('knMikrofon').onclick = mikrofonTumbler;
    $('knPovernut').onclick = povernut; $('knPov2').onclick = povernut;
    $('knRazreshit').onclick = async () => {
      oshibkaVhoda('');
      $('knRazreshit').disabled = true;
      try {
        await zvukVkl();
        await mediaVkl();
        await kameraVKodirovshik();
        pokaz('knRazreshit', false); pokaz('knVoyti', true);
      } catch (x) {
        $('knRazreshit').disabled = false;
        if (!$('vhodOshibka').textContent) oshibkaVhoda(String(x && x.message || x));
      }
    };
    $('knVoyti').onclick = async () => {
      localStorage.setItem('plus-imya', $('imya').value.trim());
      if (S.ctx && S.ctx.state === 'suspended') await S.ctx.resume();
      $('knVoyti').disabled = true;
      soedinit();
      setInterval(kadrKamery, 66);
    };
    $('knKam2').onclick = kameraTumbler;
    $('knMik2').onclick = mikrofonTumbler;
    $('knVyyti').onclick = () => { S.ushel = true; try { S.ws && S.ws.send('{"t":"uhozhu"}'); } catch (x) { } sessionStorage.removeItem('plus-z'); konec(T.prichina.sam); };
    $('knSnova').onclick = () => location.reload();
    $('gromkost').oninput = (e) => { if (S.usil) S.usil.gain.value = e.target.value / 100; };
    $('igra').oninput = (e) => otpravitSam({ igra: e.target.value / 100 });
    $('knPriglasit').onclick = () => podelitsya(S.ssylkaGost, T.poslatGostyu);
    $('knModer').onclick = () => podelitsya(S.ssylkaModer, T.tekstModeru);
    $('knNovaya').onclick = () => upr('novaya');
    $('knVpusk').onclick = () => upr('vpusk', undefined, S.vpusk === 'avto' ? 'ruchnoy' : 'avto');
    $('knMest').onclick = () => upr('mest', undefined, String(S.mest % 3 + 1));
    document.addEventListener('visibilitychange', () => { if (!document.hidden && S.vnutri) { S.lock = null; budilnik(); if (S.ctx && S.ctx.state === 'suspended') S.ctx.resume(); } });
  }

  // для проверки на столе (?proba): состояние страницы
  if (proba) window.__plus = { S, umeet };

  // ---------- начало ----------
  teksty();
  knopkiVhoda();
  if (!klyuch) { oshibkaVhoda(T.netKlyucha); $('knRazreshit').disabled = true; return; }
  if (!umeet.zvuk) { oshibkaVhoda(T.neumeet); $('knRazreshit').disabled = true; return; }
  if (rolPodskazka === 'h') {                                             // пульт Александра: без камеры и микрофона
    S.kamera = false; S.mikrofon = false;
    for (const id of ['knKamera', 'knMikrofon', 'tNaushniki', 'knRazreshit']) pokaz(id, false);
    document.querySelector('#ekranVhod .ya').hidden = true;
    pokaz('knVoyti', true);
    $('knVoyti').onclick = async () => {
      localStorage.setItem('plus-imya', $('imya').value.trim());
      $('knVoyti').disabled = true;
      soedinit();                                                         // пульт: без звука и картинки эфира
    };
  }
})();
