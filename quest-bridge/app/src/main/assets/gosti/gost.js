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
    imya: 'Имя в эфире', kamera: 'Камера', mikrofon: 'Микрофон',
    naushniki: '🎧 Лучше в наушниках. Без них в эфире включится «🔇 Только голоса»: игра не зазвучит из динамика, и эфир не услышит эхо.',
    golosa: 'Только голоса', golosaVkl: 'Игра не звучит — слышно Александра, Плюса и гостей, эфир не услышит эхо. Надел наушники — нажми, игра вернётся.',
    golosaSam: 'Наушников не видно — включила «Только голоса», чтобы эфир не услышал эхо. Надел наушники — нажми, игра вернётся.',
    igraSprosit: 'Вернуть звук игры? Без наушников он пойдёт из динамика, микрофон его поймает — и эфир услышит эхо. Ты в наушниках?',
    igraDa: '🎧 Да, я в наушниках — вернуть игру', igraNet: 'Нет — оставить только голоса',
    twitch: '📺 Twitch держи без звука: звук эфира уже идёт здесь, а Twitch отстаёт на несколько секунд — микрофон поймает его, и эфир услышит тебя эхом.',
    twitch2: '📺 Twitch — без звука: звук эфира идёт отсюда.',
    raciyaT: 'Рация: тебя слышно, только пока держишь кнопку', govori: '📻 Держи и говори', govorish: '🔴 Говоришь…',
    raciyaVkl: '📻 Рация: тебя слышно, только пока держишь большую кнопку (на компьютере — пробел).',
    razreshit: 'Включить камеру и микрофон', voyti: 'Войти', pultOtkryt: 'Открыть пульт',
    zhdiZag: 'Ждём, пока впустят…', zhdiPod: 'Модератор или Александр сейчас тебя впустят. Не закрывай страницу.',
    slyshno: '🔊 Слышно', igra: '🎮 Игра', lyudi: 'В эфире', pult: 'Пульт', priglasit: 'Пригласить гостя',
    moderSsylka: 'Ссылка модератора', novaya: 'Новая ссылка гостя', snova: 'Войти снова',
    netKlyucha: 'В ссылке нет ключа. Скопируй ссылку целиком (из сообщения, где её прислали) и вставь в Safari или Chrome — или попроси ссылку ещё раз.',
    neumeet: 'Этот браузер не умеет звук для эфира (нужны AudioWorklet и WebAssembly). Открой ссылку в Safari или Chrome.',
    netDostupa: 'Браузер не дал камеру или микрофон. Разреши доступ в настройках сайта и обнови страницу.',
    vBrauzere: 'Здесь нет доступа к камере и микрофону (встроенный браузер приложения). Открой ссылку в Safari (iPhone) или Chrome (Android): ⋯ → «Открыть в браузере».',
    kameraEfir: 'Моя камера в эфире: вкл/выкл', kameraNet: 'Камера не включилась — разреши доступ к ней в настройках сайта.',
    kameraZapret: 'Модератор выключил тебе камеру в эфире.', kameraRazresh: 'Модератор снова разрешил камеру — включи 📷, если хочешь.',
    kamVyklK: '📷 выкл', kamVklK: '📷 вкл',
    mikPropal: 'Микрофон пропал (звонок? другое приложение?) — поднимаю…', mikNet: 'Микрофон не вернулся: проверь, не занят ли он звонком, и обнови страницу.',
    mikZanyat: 'Микрофон занят (звонок?) — вернётся сам, когда освободится.',
    polnyyT: 'Во весь экран', nazadT: 'Обратно в окно',
    izFona: 'Пока Safari был свёрнут, iPhone выключал камеру и звук — тебя не было слышно. Вернула всё. Не сворачивай во время эфира.',
    kamPropala: 'Камера пропала — поднимаю…', kamNet2: 'Камера не вернулась: не занята ли она другим приложением? Голос в эфире идёт.',
    naushnikiSmena: '🎧 Наушники отключились — включила «🔇 Только голоса», чтобы эфир не услышал эхо твоего динамика.',
    tolkoZvuk: 'Камера недоступна — входишь только со звуком.',
    prevyuZhdi: 'Картинка эфира появится, когда начнётся эфир.', prevyuNet: 'Этот браузер не показывает картинку эфира — смотри стрим на Twitch, звук идёт отсюда.',
    prevyuVykl: 'Картинка эфира выключена (тесная сеть шлема) — смотри стрим на Twitch.',
    vKadre: 'Ты в кадре.', zaKadrom: 'Ты за кадром — слушаешь и управляешь.', skryt: 'Модератор убрал тебя с экрана — звук идёт.',
    zaglushen: 'Модератор выключил тебе микрофон.', mestNet: 'Мест в кадре нет — включить камеру или микрофон нельзя.',
    efirIdet: 'Эфир идёт.', efirNet: 'Эфир ещё не начался — можно поговорить.',
    perepodkl: 'Связь прервалась — переподключаюсь…',
    prichina: { zamena: 'Ты подключился заново из другого окна или сети.', vygnali: 'Тебя убрали из эфира.', zakryto: 'Комната закрыта — эфир с гостями закончился.', klyuch: 'Ссылка устарела или неверна. Попроси новую.',
      otkaz: 'Тебя не впустили.', ochered: 'Слишком много ждущих. Попробуй позже.', polno: 'В комнате нет мест.', mest_net: 'Мест в кадре нет.',
      bystro: 'Соединение закрыто: слишком много данных.', sam: 'Ты вышел из эфира.' },
    konecZag: 'Эфир для тебя закончился', vpustit: 'Впустить', otklonit: 'Нет', skrytK: 'Убрать', pokazatK: 'Показать',
    zaglK: '🎤 выкл', vklK: '🎤 вкл', vygnatK: 'Выгнать', zhdet: 'ждёт', moder: 'модератор', hozyain: 'хозяин', gost: 'гость',
    vpuskA: 'Впуск: сам', vpuskR: 'Впуск: вручную', mest: 'Мест в кадре: ', skopirovano: 'Ссылка скопирована.',
    poslatModeru: 'Ссылка модератора', poslatGostyu: 'Заходи в эфир Плюса как гость:', tekstModeru: 'Ты модератор эфира Плюса:',
    netSsylki: 'Ссылка будет, когда поднимется туннель.', vy: ' (ты)', rtt: 'связь', bufer: 'буфер', ms: 'мс',
    neSoedinilos: 'Не удалось подключиться к эфиру. Проверь интернет или попроси у Александра новую ссылку.',
  } : {
    zag: 'live', vhodZag: 'Join the stream', vhodPod: 'You are invited to Alexander\'s stream. Camera and microphone needed.',
    vhodZagM: 'Join as moderator', vhodPodM: 'You are the moderator: admit guests and share their link. Camera and mic are optional.',
    vhodZagH: 'Stream control', vhodPodH: 'Your control panel: who is in, admit, hide, kick.',
    imya: 'Your name on stream', kamera: 'Camera', mikrofon: 'Microphone',
    naushniki: '🎧 Headphones are best. Without them “🔇 Voices only” turns on: the game won’t play from your speaker and the stream won’t hear an echo.',
    golosa: 'Voices only', golosaVkl: 'No game sound — you hear Alexander, Plus and the guests, and the stream hears no echo. Put on headphones and tap to bring the game back.',
    golosaSam: 'No headphones detected — “Voices only” is on so the stream hears no echo. Put on headphones and tap to bring the game back.',
    igraSprosit: 'Bring the game sound back? Without headphones it plays from your speaker, your mic picks it up — and the stream hears an echo. Are you wearing headphones?',
    igraDa: '🎧 Yes, I’m wearing headphones — bring it back', igraNet: 'No — keep voices only',
    twitch: '📺 Keep Twitch muted: the stream sound already plays here, and Twitch lags a few seconds behind — your mic picks it up and the stream hears you as an echo.',
    twitch2: '📺 Twitch — muted: the stream sound plays from here.',
    raciyaT: 'Walkie-talkie: you are heard only while holding the button', govori: '📻 Hold to talk', govorish: '🔴 Talking…',
    raciyaVkl: '📻 Walkie-talkie: you are heard only while holding the big button (Space on a computer).',
    razreshit: 'Turn on camera and microphone', voyti: 'Join', pultOtkryt: 'Open control panel',
    zhdiZag: 'Waiting to be admitted…', zhdiPod: 'The moderator or Alexander will let you in. Keep this page open.',
    slyshno: '🔊 Volume', igra: '🎮 Game', lyudi: 'On stream', pult: 'Control', priglasit: 'Invite a guest',
    moderSsylka: 'Moderator link', novaya: 'New guest link', snova: 'Join again',
    netKlyucha: 'The link has no key. Copy the whole link from the message it came in and paste it into Safari or Chrome — or ask for the link again.',
    neumeet: 'This browser cannot do stream audio (needs AudioWorklet and WebAssembly). Open the link in Safari or Chrome.',
    netDostupa: 'The browser blocked camera or microphone. Allow access in site settings and reload.',
    vBrauzere: 'No camera or microphone here (an app\'s built-in browser). Open the link in Safari (iPhone) or Chrome (Android): ⋯ → “Open in browser”.',
    kameraEfir: 'My camera on stream: on/off', kameraNet: 'The camera did not start — allow it in site settings.',
    kameraZapret: 'The moderator turned off your camera on stream.', kameraRazresh: 'The moderator allowed your camera again — turn on 📷 if you like.',
    kamVyklK: '📷 off', kamVklK: '📷 on',
    mikPropal: 'Microphone lost (a call? another app?) — restoring…', mikNet: 'The microphone did not come back: is a call using it? Reload the page.',
    mikZanyat: 'Microphone busy (a call?) — it comes back by itself.',
    polnyyT: 'Full screen', nazadT: 'Back to window',
    izFona: 'While Safari was in the background, the iPhone paused your camera and sound — nobody heard you. Restored. Keep the page open during the stream.',
    kamPropala: 'Camera lost — restoring…', kamNet2: 'The camera did not come back: is another app using it? Your voice is still on stream.',
    naushnikiSmena: '🎧 Headphones unplugged — “🔇 Voices only” is on so the stream won’t hear your speaker as an echo.',
    tolkoZvuk: 'Camera unavailable — joining with audio only.',
    prevyuZhdi: 'The stream picture appears when the stream starts.', prevyuNet: 'This browser cannot show the stream picture — watch on Twitch, audio comes from here.',
    prevyuVykl: 'Stream picture is off (tight headset network) — watch on Twitch.',
    vKadre: 'You are on screen.', zaKadrom: 'You are off screen — listening and moderating.', skryt: 'The moderator hid you from the screen — audio still on.',
    zaglushen: 'The moderator muted your microphone.', mestNet: 'No free spots on screen — camera and mic cannot be turned on.',
    efirIdet: 'Stream is live.', efirNet: 'Stream has not started yet — you can talk.',
    perepodkl: 'Connection lost — reconnecting…',
    prichina: { zamena: 'You reconnected from another window or network.', vygnali: 'You were removed from the stream.', zakryto: 'The room is closed — the guest session is over.', klyuch: 'This link is outdated or wrong. Ask for a new one.',
      otkaz: 'You were not admitted.', ochered: 'Too many people waiting. Try later.', polno: 'The room is full.', mest_net: 'No free spots on screen.',
      bystro: 'Connection closed: too much data.', sam: 'You left the stream.' },
    konecZag: 'The stream is over for you', vpustit: 'Admit', otklonit: 'No', skrytK: 'Hide', pokazatK: 'Show',
    zaglK: '🎤 off', vklK: '🎤 on', vygnatK: 'Kick', zhdet: 'waiting', moder: 'moderator', hozyain: 'host', gost: 'guest',
    vpuskA: 'Admission: auto', vpuskR: 'Admission: manual', mest: 'Spots on screen: ', skopirovano: 'Link copied.',
    poslatModeru: 'Moderator link', poslatGostyu: 'Join Plus\'s stream as a guest:', tekstModeru: 'You are the moderator of Plus\'s stream:',
    netSsylki: 'The link appears when the tunnel is up.', vy: ' (you)', rtt: 'link', bufer: 'buffer', ms: 'ms',
    neSoedinilos: 'Could not connect to the stream. Check your internet or ask Alexander for a new link.',
  };
  const CVET = ['#8b5cf6', '#12c24a', '#ffd21f'];                    // запасные; цвет рамки шлем присылает сам (ramka)
  const $ = (id) => document.getElementById(id);
  const pokaz = (id, da) => { $(id).hidden = !da; };

  // ---------- ключ ----------
  // 2.7.6 (25.10): ссылку открыли в Инстаграме, потом «Открыть в Safari» — Safari получал адрес уже без ключа
  // (мы стирали его из адресной строки) и писал «нет ключа». Во встроенных браузерах ключ теперь остаётся в адресе;
  // ключ можно передать и как ?k= (некоторые мессенджеры теряют часть после #).
  const VSTROENNYY = /Instagram|FBAN|FBAV|FB_IAB|FBIOS|Line\/|MicroMessenger|Snapchat|musical_ly|BytedanceWebview|TikTok/i
    .test(navigator.userAgent || '');
  const hash = new URLSearchParams(location.hash.slice(1));
  const poisk = new URLSearchParams(location.search);
  let klyuch = hash.get('k') || poisk.get('k') || sessionStorage.getItem('plus-k') || '';
  const rolPodskazka = hash.get('r') || poisk.get('r') || sessionStorage.getItem('plus-r') || '';
  if (klyuch) { sessionStorage.setItem('plus-k', klyuch); sessionStorage.setItem('plus-r', rolPodskazka); }
  const proba = /[?&]proba\b/.test(location.search);                   // проверка на столе: состояние наружу
  if (!VSTROENNYY && (location.hash || poisk.has('k'))) {               // ключ — не в адресной строке
    poisk.delete('k'); poisk.delete('r');
    const q = poisk.toString();
    history.replaceState(null, '', location.pathname + (q ? '?' + q : ''));
  }

  const S = {
    ws: null, rol: '', sost: '', id: 0, zheton: sessionStorage.getItem('plus-z') || '', vnutri: false,
    kamera: true, mikrofon: true, zaglushen: false, skryt: false, cvet: -1, efir: false,
    kameraZapret: false, kameraDoZapreta: false, pongMs: 0, skrytoMs: 0,
    ctx: null, rabota: null, zahvat: null, vyvod: null, usil: null, stream: null, facing: 'user',
    venc: null, vencCfg: null, nuzhenKlyuch: true, avcc: null, kadrT: null, kadrN: 0, jpegZanyat: false,
    vdec: null, vdecKodek: '', vdecAvcc: false, zhdemKlyuch: true, kodek: '',
    ushel: false, perepodkl: 0, pingT: null, rttMs: 0, buferMs: 0, ssylkaGost: '', ssylkaModer: '',
    lyudi: [], govoryat: new Set(), lock: null, mest: 2, vpusk: 'ruchnoy',
    tolkoGolosa: false, igraDo: 50, golosaReshili: false, sprashivaem: false,
    raciya: false, zhmu: false,
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
    $('knKamera').title = T.kameraEfir; $('knKam2').title = T.kameraEfir;
    $('tNaushniki').textContent = T.naushniki; $('knRazreshit').textContent = T.razreshit;
    $('knVoyti').textContent = rolPodskazka === 'h' ? T.pultOtkryt : T.voyti;
    $('tZhdiZag').textContent = T.zhdiZag; $('tZhdiPod').textContent = T.zhdiPod;
    $('tSlyshno').textContent = T.slyshno; $('tIgra').textContent = T.igra; $('tLyudi').textContent = T.lyudi;
    $('tGolosa').textContent = T.golosa; $('tTwitch').textContent = T.twitch; $('tTwitch2').textContent = T.twitch2;
    $('knPolnyy').title = T.polnyyT; $('knNazad').title = T.nazadT; $('knKamP').title = T.kameraEfir;
    $('knRaciya').title = T.raciyaT; $('knGovori').textContent = T.govori;
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
  const ZVUK = { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 };
  const videoTreb = () => ({ width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 15, max: 24 }, facingMode: S.facing });

  // Источник кадров для кодировщика — свой, всегда живой: не display:none (iOS заморозил бы декодер на первом
  // кадре — урок студии), а крошечный и почти прозрачный; окошки «я» на экранах — только чтобы видеть себя.
  const istochnik = document.createElement('video');
  istochnik.muted = true; istochnik.autoplay = true; istochnik.playsInline = true; istochnik.setAttribute('playsinline', '');
  istochnik.style.cssText = 'position:fixed;left:0;top:0;width:2px;height:2px;opacity:0.01;pointer-events:none;z-index:-1';
  document.body.appendChild(istochnik);
  function budit() {                                                     // жест или возврат из фона — оживить видео
    for (const v of [istochnik, $('ya'), $('ya2')]) if (v.srcObject && v.paused) v.play().catch(() => {});
  }

  // Страж дорожек (урок студии): на iPhone дорожка умирает молча — входящий звонок, сон, другое приложение.
  // Умерла — поднимаем сами (три попытки, пауза растёт) и говорим гостю прямо; не вышло — говорим, что делать.
  const popytki = { audio: 0, video: 0 };
  function strazh() {
    if (!S.stream) return;
    for (const t of S.stream.getTracks()) {
      if (t.strazh) continue;
      t.strazh = true;
      t.onended = () => { if (S.stream && S.stream.getTracks().includes(t) && !S.ushel) podnyat(t.kind); };
      if (t.kind === 'audio') { t.onmute = () => { if (S.vnutri) zametka(T.mikZanyat); }; t.onunmute = () => sostoyanie(); }
    }
  }
  async function podnyat(kind) {
    const n = popytki[kind]++;
    zametka(kind === 'audio' ? T.mikPropal : T.kamPropala);
    await new Promise((ok) => setTimeout(ok, [400, 1500, 4000][Math.min(n, 2)]));
    try {
      const star = kind === 'audio' ? S.stream.getAudioTracks()[0] : S.stream.getVideoTracks()[0];
      if (star) S.stream.removeTrack(star);
      if (kind === 'audio') {
        const t = (await navigator.mediaDevices.getUserMedia({ audio: ZVUK })).getAudioTracks()[0];
        t.enabled = mikVkl(); S.stream.addTrack(t);
        try { if (S.src) S.src.disconnect(); } catch (x) { }
        S.src = S.ctx.createMediaStreamSource(new MediaStream([t])); S.src.connect(S.zahvat);
      } else {
        if (!S.kamera) { popytki.video = 0; pokazatSebya(); return; }
        S.stream.addTrack((await navigator.mediaDevices.getUserMedia({ video: videoTreb() })).getVideoTracks()[0]);
        S.nuzhenKlyuch = true;
      }
      popytki[kind] = 0;
      pokazatSebya(); otpravitSam(); sostoyanie();
    } catch (x) {
      if (popytki[kind] < 3) podnyat(kind);
      else { zametka(kind === 'audio' ? T.mikNet : T.kamNet2); if (kind === 'video') { S.kamera = false; pokazatSebya(); otpravitSam(); } }
    }
  }

  // 2.7.7 (эфир 25.10: Максим свернул Safari — после возврата звук был, а камеры нет, «M» вместо картинки, помогло
  // только открыть ссылку заново). На возврате проверяем дорожки сами: умерла или онемела — поднимаем заново,
  // кодировщик камеры — с ключевого кадра, картинку эфира — просим ключевой; шлему — что камера снова есть.
  function izFona() {
    if (document.hidden || !S.stream || S.ushel || !S.vnutri) return;
    const dolgo = S.skrytoMs && Date.now() - S.skrytoMs > 1500;
    const at = S.stream.getAudioTracks()[0];
    let chinim = false;
    if (!at || at.readyState === 'ended' || at.muted) { chinim = true; podnyat('audio'); }
    const vt = S.stream.getVideoTracks()[0];
    if (S.kamera && !S.kameraZapret && (!vt || vt.readyState === 'ended' || vt.muted)) { chinim = true; podnyat('video'); }
    if (S.venc && S.venc.state === 'closed') S.venc = null;
    S.nuzhenKlyuch = true;
    if (S.kodek) prositKlyuch();
    pokazatSebya();
    otpravitSam();
    if (dolgo && /iPhone|iPad|iPod/.test(navigator.userAgent || '')) zametka(T.izFona);
    else if (!chinim) sostoyanie();
  }

  async function mediaVkl() {
    const zvuk = ZVUK;
    const video = videoTreb();
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
    istochnik.srcObject = vt ? new MediaStream([vt]) : null;
    if (vt) istochnik.play().catch(() => {});
    strazh();
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
    if (!stary) { pokazatSebya(); return; }                              // камера выключена — включится уже этой стороной
    try {
      const n = await navigator.mediaDevices.getUserMedia({ video: videoTreb() });
      if (stary) { S.stream.removeTrack(stary); stary.stop(); }
      S.stream.addTrack(n.getVideoTracks()[0]);
      S.nuzhenKlyuch = true;
      pokazatSebya();
    } catch (x) { S.facing = S.facing === 'user' ? 'environment' : 'user'; }
  }

  // 2.7.8 (Санёк 25.10): во весь экран — эфир на весь экран телефона, поверх три кнопки: микрофон, камера, обратно.
  // Где браузер умеет полный экран (Android, iPad) — настоящий; на iPhone — вся страница под картинку (Safari не даёт).
  function polnyy(da) {
    S.polnyy = da;
    document.body.classList.toggle('polnyy', da);
    const el = document.documentElement;
    try {
      if (da) { const f = el.requestFullscreen || el.webkitRequestFullscreen; if (f) { const p = f.call(el); if (p && p.catch) p.catch(() => {}); } }
      else if (document.fullscreenElement || document.webkitFullscreenElement) {
        const f = document.exitFullscreen || document.webkitExitFullscreen; if (f) { const p = f.call(document); if (p && p.catch) p.catch(() => {}); }
      }
    } catch (x) { }
    knopki();
  }

  function knopki() {
    for (const id of ['knKamera', 'knKam2', 'knKamP']) { $(id).classList.toggle('vkl', S.kamera && !S.kameraZapret); $(id).classList.toggle('zapret', S.kameraZapret); }
    for (const id of ['knMikrofon', 'knMik2', 'knMikP']) $(id).classList.toggle('vkl', S.mikrofon && !S.zaglushen);
    $('knRaciya').classList.toggle('vkl', S.raciya);
  }

  // Камера в эфире вкл/выкл: выключенная гаснет совсем (огонёк камеры тоже), включённая — заново.
  async function kameraTumbler() {
    if (S.kameraZapret) { zametka(T.kameraZapret); return; }             // выключил модератор — включит он же
    await kameraStavit(!S.kamera);
    otpravitSam();
  }

  async function kameraStavit(vkl) {
    S.kamera = vkl;
    const vt = S.stream && S.stream.getVideoTracks()[0];
    if (!S.kamera && vt) { S.stream.removeTrack(vt); vt.stop(); }
    if (S.kamera && S.stream && !vt) {
      try {
        S.stream.addTrack((await navigator.mediaDevices.getUserMedia({ video: videoTreb() })).getVideoTracks()[0]);
        zametka('');
      } catch (x) { S.kamera = false; zametka(T.kameraNet); }
    }
    if (S.kamera) S.nuzhenKlyuch = true;
    pokazatSebya();
  }

  // Модератор выключил / снова разрешил камеру: выключил — гаснет, как своей кнопкой; разрешил — возвращается,
  // только если была включена до запрета (иначе — просто сказать, что можно).
  async function primenitZapret(da) {
    if (da === S.kameraZapret) return;
    S.kameraZapret = da;
    if (da) { S.kameraDoZapreta = S.kamera; if (S.kamera) await kameraStavit(false); }
    else if (S.kameraDoZapreta) { S.kameraDoZapreta = false; await kameraStavit(true); }
    else zametka(T.kameraRazresh);
    knopki(); otpravitSam(); sostoyanie();
  }

  function mikrofonTumbler() {
    S.mikrofon = !S.mikrofon;
    mikPrimenit();
    knopki();
    otpravitSam();
  }

  // Слышно ли сейчас: микрофон включён и (без рации — всегда; в рации — пока держит кнопку)
  const mikVkl = () => S.mikrofon && (!S.raciya || S.zhmu);
  function mikPrimenit() {
    const at = S.stream && S.stream.getAudioTracks()[0];
    if (at) at.enabled = mikVkl();
    if (S.zahvat) S.zahvat.port.postMessage({ t: 'vkl', vkl: mikVkl() });
  }

  // ---------- 📻 Рация (2.5.3, Александр): слышно, только пока держишь кнопку — модератор сразу видит, кто говорит ----------
  function raciyaTumbler() {
    S.raciya = !S.raciya; S.zhmu = false;
    pokaz('knGovori', S.raciya); govoritPokazat();
    mikPrimenit(); knopki(); otpravitSam();
    zametka(S.raciya ? T.raciyaVkl : '');
  }
  function govoritPokazat() {
    const b = $('knGovori'); b.classList.toggle('zhmu', S.zhmu); b.textContent = S.zhmu ? T.govorish : T.govori;
  }
  function govorit(vkl) {
    if (!S.raciya || S.zhmu === vkl) return;
    S.zhmu = vkl; govoritPokazat(); mikPrimenit();
  }

  function otpravitSam(dop) {
    if (S.ws && S.ws.readyState === 1 && S.vnutri)
      S.ws.send(JSON.stringify(Object.assign({ t: 'sam', kamera: S.kamera && !!(S.stream && S.stream.getVideoTracks()[0]), mikrofon: S.mikrofon,
        igra: Number($('igra').value) / 100, raciya: S.raciya }, dop || {})));
  }

  // Вопрос с двумя ясными ответами (confirm на телефоне — «OK / Отмена», непонятно, что чем)
  function sprosit(tekst, da, net) {
    return new Promise((ok) => {
      $('dialogTekst').textContent = tekst; $('dialogDa').textContent = da; $('dialogNet').textContent = net;
      const otvet = (v) => { $('dialog').hidden = true; ok(v); };
      $('dialogDa').onclick = () => otvet(true); $('dialogNet').onclick = () => otvet(false);
      $('dialog').hidden = false;
    });
  }
  // Вернуть звук игры (Александр 16.10): по умолчанию без наушников он выключен; включить — только после
  // предупреждения об эхе и ответа «я в наушниках»
  async function igruVernut(uroven) {
    if (S.sprashivaem) return;
    S.sprashivaem = true;
    try {
      if (await sprosit(T.igraSprosit, T.igraDa, T.igraNet)) { if (uroven > 0) S.igraDo = uroven; golosaStavit(false); }
      else { $('igra').value = 0; golosaPokazat(); }
    } finally { S.sprashivaem = false; }
  }

  // ---------- «🔇 Только голоса» (2.5.3, Александр): без наушников игра из динамика — главное эхо эфира.
  // Гасит гостю игру в его смеси (сервер), голос Плюса и людей остаётся. Наушников не видно — включается сама.
  function golosaPokazat() {
    const b = $('knGolosa');
    b.classList.toggle('vkl', S.tolkoGolosa); b.setAttribute('aria-pressed', String(S.tolkoGolosa));
    const p = $('golosaPodskazka');
    p.textContent = S.tolkoGolosa ? (S.golosaSami ? T.golosaSam : T.golosaVkl) : ''; p.hidden = !S.tolkoGolosa;
  }
  function golosaStavit(vkl, sami) {
    const pl = $('igra');
    if (vkl && !S.tolkoGolosa && Number(pl.value) > 0) S.igraDo = Number(pl.value);
    S.tolkoGolosa = vkl; S.golosaSami = !!sami;
    pl.value = vkl ? 0 : (S.igraDo || 50);
    golosaPokazat(); otpravitSam();
  }
  // Наушники видно по имени выхода звука (Chrome на компьютере и Android); iPhone выходов не показывает — «не видно»
  async function estNaushniki() {
    try {
      const v = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === 'audiooutput');
      return v.some((d) => /head|наушн|гарнит|airpods|buds|earphone|bluetooth|hands-?free/i.test(d.label || ''));
    } catch (x) { return false; }
  }
  async function golosaReshit() {                                       // один раз, при входе в эфир
    if (S.golosaReshili || S.rol === 'hozyain') return;
    S.golosaReshili = true;
    if (!(await estNaushniki())) golosaStavit(true, true);
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
        if (S.ws && S.ws.readyState === 1) { S.ws.send(out); S.vidOtpr = (S.vidOtpr || 0) + 1; }
      },
      error: () => { S.venc = null; S.nuzhenKlyuch = true; },
    });
    S.venc.configure(S.vencCfg);
  }

  function kadrKamery() {
    const ws = S.ws;
    const v = istochnik;
    if (!ws || ws.readyState !== 1 || !S.vnutri || !S.kamera || S.kameraZapret || S.cvet < 0 || S.skryt) return;
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
          S.ws.send(out); S.vidOtpr = (S.vidOtpr || 0) + 1;
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
      S.perepodkl = 0; S.pongMs = Date.now();
      ws.send(JSON.stringify({ t: 'vhod', k: klyuch, imya: $('imya').value.trim(), zheton: S.zheton,
        kamera: S.kamera && !!(S.stream && S.stream.getVideoTracks()[0]), mikrofon: S.mikrofon && !!S.stream, h264: umeet.vdec }));
      if (S.pingT) clearInterval(S.pingT);
      S.pingT = setInterval(() => {
        if (ws.readyState !== 1) return;
        if (Date.now() - S.pongMs > 15000) { mertvyy(ws); return; }       // сервер молчит 15 с — сокет мёртв
        ws.send(JSON.stringify({ t: 'ping', c: Date.now() }));
      }, 4000);
    };
    ws.onmessage = (e) => {
      if (S.ws !== ws) return;                                              // старое (заменённое) соединение — молчит
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

  // Сокет умер молча (урок студии: iOS после фона держит его «открытым»): не ждём — звоним заново с жетоном,
  // сервер отдаст новому соединению место старого.
  function mertvyy(ws) {
    if (S.ws !== ws) return;
    clearInterval(S.pingT);
    S.ws = null;
    try { ws.onclose = null; ws.close(); } catch (x) { }
    if (S.ushel) return;
    zametka(T.perepodkl);
    setTimeout(soedinit, 300);
  }

  function soobshchenie(m) {
    switch (m.t) {
      case 'privet':
        S.rol = m.rol; S.sost = m.sost; S.id = m.id; S.cvet = m.cvet; S.efir = !!m.efir; S.mest = m.mest;
        S.zheton = m.zheton || ''; sessionStorage.setItem('plus-z', S.zheton);
        S.zaglushen = !!m.zaglushen; S.skryt = !!m.skryt; primenitZapret(!!m.kamera_zapret);
        S.vnutri = m.sost === 'vnutri';
        if (!m.kamera && S.kamera && S.rol !== 'hozyain') { S.kamera = false; pokazatSebya(); }
        if (!m.mikrofon && S.mikrofon && S.rol !== 'hozyain') { S.mikrofon = false; knopki(); }
        zametka('');
        if (S.vnutri) { ekran('ekranEfir'); sostoyanie(); S.nuzhenKlyuch = true; if (S.kodek) prositKlyuch(); budilnik(); golosaReshit(); }
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
        { const ya = S.lyudi.find((x) => x.id === S.id); if (ya) { S.cvet = ya.cvet; S.skryt = ya.skryt; S.zaglushen = ya.zaglushen; primenitZapret(!!ya.kamera_zapret); } }
        risovatLyudey(); risovatPult(); sostoyanie(); knopki();
        break;
      case 'govoryat':
        S.govoryat = new Set(m.id || []); risovatLyudey();
        // пульт не перерисовываем (съели бы нажатие модератора) — только рамка «говорит» на строках
        for (const d of $('pultLyudi').children) d.classList.toggle('govorit', S.govoryat.has(Number(d.dataset.id)));
        break;
      case 'video': videoNastroit(m.kodek); break;
      case 'kluch': S.nuzhenKlyuch = true; break;
      case 'zaglushen': S.zaglushen = !!m.da; knopki(); sostoyanie(); break;
      case 'kamera_zapret': primenitZapret(!!m.da); break;
      case 'skryt': S.skryt = !!m.da; sostoyanie(); break;
      case 'mest_net': zametka(T.mestNet); break;
      case 'efir': S.efir = !!m.idyot; $('tochka').classList.toggle('efir', S.efir); sostoyanie();
        if (!S.efir) { S.kodek = ''; prevyuTekst(T.prevyuZhdi); } break;
      case 'pong': S.pongMs = Date.now(); S.rttMs = Date.now() - m.c; stroka(); break;
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
    if (S.kameraZapret) t.push(T.kameraZapret);
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
      c.style.background = x.ramka || (x.cvet >= 0 ? CVET[x.cvet] : '#555'); c.style.color = c.style.background;
      const n = document.createElement('span'); n.className = 'imya'; n.textContent = x.imya + (x.id === S.id ? T.vy : '');
      const r = document.createElement('span'); r.className = 'rol';
      r.textContent = (x.rol === 'moder' ? T.moder + ' · ' : '') + (x.mikrofon && !x.zaglushen ? '🎤' : '🔇') + (x.kamera && !x.kamera_zapret ? ' 📷' : '') +
        (x.raciya ? ' 📻' : '');
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
      const d = document.createElement('div'); d.className = 'pultKto'; d.dataset.id = x.id;
      d.classList.toggle('govorit', S.govoryat.has(x.id));                      // говорит сейчас — зелёная рамка: кого глушить
      const b = document.createElement('b'); b.textContent = x.imya + ' · ' + (T[x.rol] || x.rol) + (x.raciya ? ' · 📻' : '');
      if (x.ramka || x.cvet >= 0) b.style.color = x.ramka || CVET[x.cvet];
      d.append(b,
        knopka(x.skryt ? T.pokazatK : T.skrytK, '', () => upr(x.skryt ? 'pokazat' : 'skryt', x.id)),
        knopka(x.zaglushen ? T.vklK : T.zaglK, x.zaglushen ? 'vyk' : '', () => upr(x.zaglushen ? 'vklyuchit' : 'zaglushit', x.id)),
        knopka(x.kamera_zapret ? T.kamVklK : T.kamVyklK, x.kamera_zapret ? 'vyk' : '', () => upr(x.kamera_zapret ? 'kamera_vkl' : 'kamera_vykl', x.id)));
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
    $('knKamP').onclick = kameraTumbler;
    $('knMikP').onclick = mikrofonTumbler;
    $('knPolnyy').onclick = () => polnyy(true);
    $('knNazad').onclick = () => polnyy(false);
    document.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement && S.polnyy) polnyy(false); });
    document.addEventListener('webkitfullscreenchange', () => { if (!document.webkitFullscreenElement && S.polnyy) polnyy(false); });
    $('knVyyti').onclick = () => { S.ushel = true; try { S.ws && S.ws.send('{"t":"uhozhu"}'); } catch (x) { } sessionStorage.removeItem('plus-z'); konec(T.prichina.sam); };
    $('knSnova').onclick = () => location.reload();
    $('gromkost').oninput = (e) => { if (S.usil) S.usil.gain.value = e.target.value / 100; };
    $('igra').oninput = (e) => {
      const v = Number(e.target.value);
      if (S.tolkoGolosa && v > 0) { e.target.value = 0; igruVernut(v); return; }   // из «только голоса» — через вопрос
      S.tolkoGolosa = v === 0; S.golosaSami = false; if (v > 0) S.igraDo = v;
      golosaPokazat(); otpravitSam();
    };
    $('knGolosa').onclick = () => (S.tolkoGolosa ? igruVernut() : golosaStavit(true, false));
    // 📻 рация: держишь — слышно; отпустил, увёл палец, свернул страницу — нет
    $('knRaciya').onclick = raciyaTumbler;
    const gv = $('knGovori');
    gv.addEventListener('pointerdown', (e) => { e.preventDefault(); try { gv.setPointerCapture(e.pointerId); } catch (x) { } govorit(true); });
    for (const t of ['pointerup', 'pointercancel', 'lostpointercapture']) gv.addEventListener(t, () => govorit(false));
    gv.addEventListener('contextmenu', (e) => e.preventDefault());
    document.addEventListener('keydown', (e) => {
      if (e.code === 'Space' && S.raciya && !e.repeat && !/input|textarea/i.test((e.target && e.target.tagName) || '')) { e.preventDefault(); govorit(true); }
    });
    document.addEventListener('keyup', (e) => { if (e.code === 'Space') govorit(false); });
    window.addEventListener('blur', () => govorit(false));
    $('knPriglasit').onclick = () => podelitsya(S.ssylkaGost, T.poslatGostyu);
    $('knModer').onclick = () => podelitsya(S.ssylkaModer, T.tekstModeru);
    $('knNovaya').onclick = () => upr('novaya');
    $('knVpusk').onclick = () => upr('vpusk', undefined, S.vpusk === 'avto' ? 'ruchnoy' : 'avto');
    $('knMest').onclick = () => upr('mest', undefined, String(S.mest % 3 + 1));
    // Уроки студии: iOS усыпляет звук и роняет Wake Lock в фоне, а сокет может умереть молча — на возврате
    // будим всё; был в фоне дольше 8 с — пинг, и если сервер не ответил за 2,5 с — переподключаемся сами.
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        S.skrytoMs = Date.now(); govorit(false);
        // 2.7.7 (эфир 25.10): в фоне iPhone останавливает камеру и звук — плитка не должна делать вид, что гость тут
        if (S.vnutri) otpravitSam({ kamera: false });
        return;
      }
      budit();
      if (S.ctx && S.ctx.state === 'suspended') S.ctx.resume();
      if (!S.vnutri) return;
      S.lock = null; budilnik();
      setTimeout(izFona, 700);
      const ws = S.ws;
      if (ws && ws.readyState === 1 && S.skrytoMs && Date.now() - S.skrytoMs > 8000) {
        const t0 = Date.now();
        try { ws.send(JSON.stringify({ t: 'ping', c: t0 })); } catch (x) { }
        setTimeout(() => { if (S.pongMs < t0) mertvyy(ws); }, 2500);
      }
    });
    document.addEventListener('pointerdown', () => { if (S.ctx && S.ctx.state === 'suspended') S.ctx.resume().catch(() => {}); budit(); });
    // Страж наушников (урок студии): выход звука сменился посреди эфира — предупредить про эхо (не везде видно)
    if (navigator.mediaDevices && navigator.mediaDevices.addEventListener) {
      const podpis = async () => { try { return (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === 'audiooutput').map((d) => d.deviceId).join('|'); } catch (x) { return ''; } };
      let bylo = null;
      podpis().then((p) => { bylo = p; });
      navigator.mediaDevices.addEventListener('devicechange', async () => {
        const p = await podpis();
        // наушники сняли посреди эфира — сами «только голоса»; надели — решает гость (кнопкой)
        if (bylo !== null && p !== bylo && S.vnutri && S.rol !== 'hozyain' && !S.tolkoGolosa && !(await estNaushniki())) {
          golosaStavit(true, true); zametka(T.naushnikiSmena);
        }
        bylo = p;
      });
    }
  }

  // для проверки на столе (?proba): состояние страницы
  if (proba) window.__plus = { S, umeet };

  // ---------- начало ----------
  teksty();
  knopkiVhoda();
  if (!klyuch) { oshibkaVhoda(T.netKlyucha); $('knRazreshit').disabled = true; return; }
  if (!umeet.zvuk) { oshibkaVhoda(T.neumeet); $('knRazreshit').disabled = true; return; }
  // встроенный браузер приложения (Instagram, Facebook…) часто без камеры и микрофона — сразу сказать, куда открыть
  if (rolPodskazka !== 'h' && (VSTROENNYY || !(navigator.mediaDevices && navigator.mediaDevices.getUserMedia))) {
    oshibkaVhoda(T.vBrauzere); $('knRazreshit').disabled = true; return;
  }
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
