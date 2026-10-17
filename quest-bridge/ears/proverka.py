"""Кодовые фразы ушей — живыми голосами, до шлема.

edge-tts произносит фразы голосами ru, uk и en → 16 кГц моно → Vosk с той же
грамматикой (фразы + приманки) и тем же порогом, что в приложении
(assets/triggers.json, Triggers.kt). Каждая фраза должна дать своё действие,
похожие — ничего. Модель одна — русская (английскую убрали в 2.2.2): английская речь
(игроки в Onward) не должна давать ничего. Вторая часть: фраза после «плюс плюс» —
модель без грамматики (Triggers.bestPhrase).
Запуск на GitHub из папки quest-bridge: python ears/proverka.py
"""
import asyncio
import json
import subprocess
import sys

import edge_tts
from vosk import KaldiRecognizer, Model, SetLogLevel

SetLogLevel(-1)
T = json.load(open('app/src/main/assets/triggers.json', encoding='utf-8'))
MIN = T['minConf']
MODELS = {lang: Model('app/src/main/assets/' + m['asset']) for lang, m in T['models'].items()}

RU = {'плюс плюс': 'wake', 'зум зум': 'zoom:x2', 'зум зум зум': 'zoom:x4',
      'зум зум центр': 'zoom:centr', 'плюс тихо': 'tiho',
      'плюс плюс компакт': 'compact:prep', 'плюс плюс компакт плюс': 'compact:go',
      'плюс плюс в ухо': 'rezhim:uho', 'плюс плюс вслух': 'rezhim:vsluh',
      'чек чек': 'plenka:7', 'зум чек': 'plenka:13', 'чек чек плюс': 'wake:chek'}
RU_NO = ['плюс один', 'зум', 'два плюс два будет четыре', 'зубы болят', 'привет, как дела',
         'плюс минус', 'тихо, тихо', 'посмотри направо', 'плюс два', 'зум зум зум зум',
         'компакт', 'наша компания', 'копи', 'копы', 'копи копи',
         'си плюс плюс это язык программирования', 'это даёт плюс плюс к скорости',
         'чек', 'дай чек', 'чек пожалуйста', 'проверка связи']
UK_NO = ['плюс один', 'добрий день', 'зуби болять', 'подивись праворуч']
# английская речь вокруг (игроки, ролики) — русская модель не должна принимать её за вызов
EN_NO = ['plus one', 'zoom', 'the zoo is closed', 'hello, how are you', 'please stop', 'look to the right',
         'please be quiet', 'stop', 'compact', 'our company', 'copy', 'copy copy']

# вызов и фраза одним дыханием: (голос, текст, первое слово фразы после вызова). Слова сразу
# после «плюс плюс» не должны теряться: фраза берётся из кольца с конца слов вызова.
SLITNO = [('ru-RU-DmitryNeural', 'плюс плюс посмотри на экран', 'посмотри'),
          ('ru-RU-SvetlanaNeural', 'плюс плюс сколько патронов у меня осталось', 'сколько'),
          ('ru-RU-DmitryNeural', 'плюс плюс что там написано', 'что'),
          ('uk-UA-OstapNeural', 'плюс плюс подивись на екран', None),
          # длинные: Ава должна замолчать рано, а не после всей фразы
          ('ru-RU-DmitryNeural', 'плюс плюс посмотри на экран и скажи что там написано мелким шрифтом '
           'в левом верхнем углу рядом с картой', 'посмотри'),
          ('ru-RU-SvetlanaNeural', 'плюс плюс сколько у меня осталось патронов и где ближайший ящик '
           'с припасами на этой карте', 'сколько')]

# фраза после вызова: (голос, текст, какой язык должен победить)
PHRASES = [('ru-RU-SvetlanaNeural', 'посмотри, что написано на экране', 'ru'),
           ('ru-RU-DmitryNeural', 'сколько патронов у меня осталось', 'ru'),
           ('uk-UA-OstapNeural', 'подивись, що написано на екрані', 'ru')]

# «копи копи» в конце фразы после вызова — фраза уходит сразу: (голос, текст, должно ли сработать)
STOP = [('ru-RU-SvetlanaNeural', 'посмотри на экран и скажи, что там написано. Копи́ копи́.', True),
        ('ru-RU-DmitryNeural', 'сколько у меня осталось патронов. Копи копи.', True),
        ('ru-RU-DmitryNeural', 'посмотри на карту. Капи́ капи́.', True),
        ('uk-UA-OstapNeural', 'подивись на екран. Копі́ копі́.', True),
        ('uk-UA-PolinaNeural', 'що там написано. Копі копі.', True),
        # «копик» так пишет Whisper (срезает ushi); Vosk синтезированное «копик» слышит «кубик» — не берём
        ('ru-RU-SvetlanaNeural', 'проверка связи. Копейку.', True),
        ('ru-RU-SvetlanaNeural', 'посмотри на экран. Копи́.', False),
        ('ru-RU-DmitryNeural', 'копы приехали на место', False),
        ('ru-RU-SvetlanaNeural', 'капитан, вперёд, к кораблю', False),
        ('ru-RU-DmitryNeural', 'я копил деньги на машину', False),
        ('ru-RU-SvetlanaNeural', 'сделай копию экрана', False)]

CASES = []
for v in ('ru-RU-DmitryNeural', 'ru-RU-SvetlanaNeural', 'uk-UA-OstapNeural', 'uk-UA-PolinaNeural'):
    CASES += [(v, t, a) for t, a in RU.items() if v.startswith('ru') or not a.startswith('rezhim')]
    CASES += [(v, t, None) for t in (RU_NO if v.startswith('ru') else UK_NO)]
for v in ('en-US-GuyNeural', 'en-US-JennyNeural', 'en-GB-RyanNeural'):
    CASES += [(v, t, None) for t in EN_NO]


async def speak(voice, text):
    mp3 = b''
    for attempt in range(6):
        try:
            mp3 = b''.join([c['data'] async for c in edge_tts.Communicate(text, voice).stream() if c['type'] == 'audio'])
            if mp3:
                break
        except Exception:
            pass
        await asyncio.sleep(3 * (attempt + 1))
    if not mp3:
        return None
    pcm = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', 'pipe:0', '-ar', '16000', '-ac', '1',
                          '-f', 's16le', 'pipe:1'], input=mp3, capture_output=True).stdout
    return b'\0' * 16000 + pcm + b'\0' * 48000          # 0,5 с тишины до и 1,5 с после


def hits(pcm):
    """Как EarService: каждая модель со своей грамматикой, порог на каждое слово."""
    out = []
    for lang, model in MODELS.items():
        phrases = T['models'][lang]['phrases']
        r = grammar_rec(lang)
        results = []
        for i in range(0, len(pcm), 3200):
            if r.AcceptWaveform(pcm[i:i + 3200]):
                results.append(json.loads(r.Result()))
        results.append(json.loads(r.FinalResult()))
        for res in results:
            text = res.get('text', '').strip()
            confs = [w.get('conf', 0) for w in res.get('result', [])]
            if text in phrases and len(confs) == len(text.split()) and min(confs) >= MIN:
                out.append((phrases[text], lang, min(confs), text))
            elif text:
                out.append((None, lang, min(confs) if confs else 0, text))
    return out


def grammar_rec(lang):
    m = T['models'][lang]
    words = list(dict.fromkeys(list(m['phrases']) + list(m.get('commands', {})) + m.get('decoys', []) + ['[unk]']))
    r = KaldiRecognizer(MODELS[lang], 16000, json.dumps(words, ensure_ascii=False))
    r.SetWords(True)
    r.SetPartialWords(True)
    return r


def exact(table, text, confs):
    """Triggers.match / command: фраза целиком, каждое слово уверено."""
    a = table.get(text)
    if a and len(confs) == len(text.split()) and min(confs) >= MIN:
        return a
    return None


def wake_at_start(lang, words, need_conf):
    """Triggers.wakeAtStart: «плюс плюс» в начале и дальше ещё слова → конец слов вызова, с."""
    for p, a in T['models'][lang]['phrases'].items():
        if a != 'wake':
            continue
        w = p.split()
        if len(words) > len(w) and [x.get('word') for x in words[:len(w)]] == w and \
                (not need_conf or all(x.get('conf', 0) >= MIN for x in words[:len(w)])):
            return words[len(w) - 1].get('end', 0.0)
    return None


def slushat(pcm):
    """Как EarService целиком: вызов — точная фраза, «плюс плюс» в начале итога или рано
    (промежуточный текст, earlyChunks кусков подряд); после вызова — фраза с конца слов
    вызова. Вернёт (действие, текст фразы, как сработало, секунда срабатывания)."""
    early_n = T.get('earlyChunks', 3)
    recs = {lang: grammar_rec(lang) for lang in MODELS}
    ITOGI[:] = []
    seen = {lang: 0 for lang in MODELS}
    pending = None                                       # голый вызов ждёт до 3 кусков
    for i in range(0, len(pcm), 3200):
        chunk = pcm[i:i + 3200]
        hits = []
        for lang, r in recs.items():
            if r.AcceptWaveform(chunk):
                seen[lang] = 0
                res = json.loads(r.Result())
                text, words = res.get('text', '').strip(), res.get('result', [])
                if text:
                    ITOGI.append('%.1f %s:%s' % ((i + len(chunk)) / 32000, lang, text))
                confs = [w.get('conf', 0) for w in words]
                a = exact(T['models'][lang]['phrases'], text, confs)
                if a and a != 'stop':
                    hits.append((min(confs), a, words[-1]['end'] if a == 'wake' else None, 'точно',
                                 words[0]['start'] if a == 'wake' else None))
                elif not a:
                    end = wake_at_start(lang, words, True)
                    if end is not None:
                        hits.append((MIN, 'wake', end, 'начало итога', words[0]['start']))
            else:
                pw = json.loads(r.PartialResult()).get('partial_result', [])
                end = wake_at_start(lang, pw, False)
                seen[lang] = seen[lang] + 1 if end is not None else 0
                if early_n and seen[lang] >= early_n:
                    hits.append((MIN, 'wake', end, 'рано', pw[0]['start']))
        specific = [h for h in hits if h[1] != 'wake']       # точная команда сильнее голого вызова
        other = [h for h in hits if h[1] == 'wake' and h[3] != 'точно']
        bare = [h for h in hits if h[1] == 'wake' and h[3] == 'точно']
        pick = None
        if specific:
            pick, pending = max(specific, key=lambda h: h[0]), None
        elif other:
            pick, pending = max(other, key=lambda h: h[0]), None
        elif bare and pending is None:
            pending = [max(bare, key=lambda h: h[0]), T.get('bareWaitChunks', 5)]
        if pick is None and pending is not None:
            pending[1] -= 1
            if pending[1] <= 0:
                pick, pending = pending[0], None
        if pick is None:
            continue
        conf, a, end, how, start = pick
        now = i + len(chunk)
        if a != 'wake':
            return a, '', how, now / 32000
        frm = min(int(end * 16000) * 2, now) if end is not None else now
        # приложение 2: Whisper слышит звук с начала слов вызова (запас 0,25 с)
        lead = max(0, int((start - 0.25) * 16000) * 2, frm - 3 * 32000) if start is not None else frm
        KRAY[:] = [frm, now, min(lead, frm)]
        a2, text = fraza(pcm, frm, now)
        return a2, text, how, now / 32000
    return None, '', '', None


def fraza(pcm, frm, now):
    """Фраза после вызова, как EarService: грамматика — с конца слов вызова (короткие
    команды), свободные модели — последние 3 с кольца и дальше живьём; лучшая — по
    средней уверенности (bestPhrase)."""
    gr = {lang: grammar_rec(lang) for lang in MODELS}
    free = {}
    for lang, model in MODELS.items():
        r = KaldiRecognizer(model, 16000)
        r.SetWords(True)
        free[lang] = r
    pre = pcm[frm:now]
    first = {lang: True for lang in MODELS}             # своя «первая часть» у каждой модели

    def part(lang, r):
        res = json.loads(r.Result())
        text = res.get('text', '').strip()
        if first[lang] and any(w != '[unk]' for w in text.split()):
            first[lang] = False
            return exact(T['models'][lang].get('commands', {}), text,
                         [w.get('conf', 0) for w in res.get('result', [])])
        return None

    cmd = None
    for i in range(0, len(pre), 3200):                   # итоги внутри кольца — не теряем
        for lang, r in gr.items():
            if r.AcceptWaveform(pre[i:i + 3200]):
                cmd = cmd or part(lang, r)
    if cmd:
        return cmd, ''
    tail = pre[max(0, len(pre) - 3 * 32000):]
    for i in range(0, len(tail), 3200):
        for r in free.values():
            r.AcceptWaveform(tail[i:i + 3200])
    for i in range(now, len(pcm), 3200):
        chunk = pcm[i:i + 3200]
        for r in free.values():
            r.AcceptWaveform(chunk)
        for lang, r in gr.items():
            if r.AcceptWaveform(chunk):
                cmd = cmd or part(lang, r)
        if cmd:
            return cmd, ''
    heard = []
    for lang, r in free.items():
        res = json.loads(r.FinalResult())
        confs = [w['conf'] for w in res.get('result', [])]
        if res.get('text') and confs:
            heard.append((sum(confs) / len(confs) + T.get('phraseBonus', {}).get(lang, 0), res['text']))
    FRAZA_VSE[:] = [t for _, t in heard]
    return 'wake', max(heard)[1] if heard else ''


FRAZA_VSE = []          # тексты обеих моделей последней фразы — для проверки «одним дыханием»
KRAY = []               # [байт начала фразы, байт срабатывания, байт начала звука для Whisper] — для опытов
ITOGI = []              # итоги грамматики по времени — для разбора ошибок
PLOHO = []              # сводка ошибок — в конце журнала


def best_phrase(pcm):
    """Как EarService после «плюс плюс»: обе модели без грамматики, лучшая — по средней уверенности."""
    heard = []
    for lang, model in MODELS.items():
        r = KaldiRecognizer(model, 16000)
        r.SetWords(True)
        texts, confs = [], []
        for i in range(0, len(pcm), 3200):
            if r.AcceptWaveform(pcm[i:i + 3200]):
                res = json.loads(r.Result())
                texts.append(res.get('text', '')); confs += [w['conf'] for w in res.get('result', [])]
        res = json.loads(r.FinalResult())
        texts.append(res.get('text', '')); confs += [w['conf'] for w in res.get('result', [])]
        text = ' '.join(t for t in texts if t)
        if text and confs:
            heard.append((sum(confs) / len(confs) + T.get('phraseBonus', {}).get(lang, 0), lang, text))
    return max(heard) if heard else None


def grammar_stop(lang, model, pcm):
    """Как EarService: грамматика (фразы + приманки) слушает фразу; «копи копи» в конце
    её итога с уверенностью каждого слова >= minConf → секунда срабатывания, иначе None.
    Промежуточный текст грамматики не берём: обрывал на полуслове."""
    m = T['models'][lang]
    stops = [p for p, a in m['phrases'].items() if a == 'stop']
    words = list(dict.fromkeys(list(m['phrases']) + m.get('decoys', []) + ['[unk]']))
    r = KaldiRecognizer(model, 16000, json.dumps(words, ensure_ascii=False))
    r.SetWords(True)
    for i in range(0, len(pcm), 3200):
        if r.AcceptWaveform(pcm[i:i + 3200]):
            res = json.loads(r.Result())
            w = res.get('text', '').split()
            c = [x.get('conf', 0) for x in res.get('result', [])]
            for p in stops:
                k = len(p.split())
                if len(w) == len(c) and len(w) >= k and ' '.join(w[-k:]) == p and min(c[-k:]) >= MIN:
                    return i / 32000
    return None


def stop_word(pcm, chunks=2, count=2):
    """Как EarService (Triggers.StopWord): обе модели без грамматики, куски по 0,1 с;
    count стоп-слов подряд в конце промежуточного текста chunks кусков подряд → сработало.
    Плюс грамматика (grammar_stop) — срабатывает любое из двух."""
    out = []
    for lang, model in MODELS.items():
        words = set(T['models'][lang].get('stop', []))
        one = set(T['models'][lang].get('stopOne', []))
        r = KaldiRecognizer(model, 16000)
        try:
            r.SetEndpointerDelays(5.0, 2.0, 45.0)
        except AttributeError:
            pass
        n, fired, tail = 0, None, ''
        for i in range(0, len(pcm), 3200):
            if r.AcceptWaveform(pcm[i:i + 3200]):
                tail = json.loads(r.Result()).get('text', '') or tail
                n = 0
                continue
            part = json.loads(r.PartialResult()).get('partial', '').strip()
            w = part.lower().split()
            hit = (w and w[-1] in one) or (len(w) >= count and all(x in words for x in w[-count:]))
            n = n + 1 if hit else 0
            if n >= chunks and fired is None:
                fired = i / 32000          # секунда, когда сработало
            tail = part or tail
        tail = json.loads(r.FinalResult()).get('text', '') or tail
        g = grammar_stop(lang, model, pcm)
        how = 'текст' if fired is not None else ''
        if g is not None and (fired is None or g < fired):
            fired, how = g, 'грамматика'
        out.append((lang, fired, tail + (' [%s]' % how if how else '')))
    return out


async def main():
    bad = 0
    silent = 0
    sure_ok, sure_bad = [], []        # уверенность верных вызовов и ложных — для порога minConf
    for voice, text, want in CASES:
        pcm = await speak(voice, text)
        if pcm is None:
            silent += 1
            print('НЕТ ЗВУКА %-22s %s — edge-tts не ответил, пропуск' % (voice, text), flush=True)
            continue
        h = hits(pcm)
        for x in h:                    # без порога: что дала бы каждая модель
            phrases = T['models'][x[1]]['phrases']
            if x[3] in phrases:
                (sure_ok if phrases[x[3]] == want else sure_bad).append(x[2])
        got, rest, how, sec = slushat(pcm)
        ok = got == want
        bad += not ok
        heard = '; '.join('%s:%s %.2f' % (x[1], x[3], x[2]) for x in h) or '—'
        print('%s %-22s %-28s ждали %-12s получили %-12s %s | %s' % (
            'OK ' if ok else 'ПЛОХО', voice, text, want, got,
            ('(%s, %.1f с%s)' % (how, sec, ', «%s»' % rest if rest else '')) if got else '', heard), flush=True)
        if not ok:
            PLOHO.append('%s «%s»: ждали %s, получили %s; итоги по времени: %s'
                         % (voice, text, want, got, ' · '.join(ITOGI)))
    print('\nВызов и фраза одним дыханием — начало фразы не теряется:')
    for voice, text, first in SLITNO:
        pcm = await speak(voice, text)
        if pcm is None:
            silent += 1
            print('НЕТ ЗВУКА %-22s %s' % (voice, text), flush=True)
            continue
        FRAZA_VSE[:] = []                  # иначе без вызова печатались тексты прошлой фразы
        got, rest, how, sec = slushat(pcm)
        # язык фразы выбирает уже Whisper; здесь важно, что звук фразы начат вовремя:
        # первое слово есть у той модели, чей это язык, и «плюс» не попал в начало
        w = rest.split()
        nachalo = [t.split()[:2] for t in FRAZA_VSE]
        ok = got == 'wake' and w and w[0] not in ('плюс', 'plus') and (
            first is None or any(first in x for x in nachalo))
        bad += not ok
        dlina = (len(pcm) - 16000 - 48000) / 32000          # речь без тишины до и после
        print('%s %-22s %-44s %s %s через %.1f с от начала речи (речь %.1f с) → «%s» %s' % (
              'OK ' if ok else 'ПЛОХО', voice, text[:44], got, how, (sec or 0) - 0.5, dlina, rest,
              FRAZA_VSE), flush=True)
        if not ok:
            PLOHO.append('%s «%s» (одним дыханием): получили %s; итоги по времени: %s'
                         % (voice, text, got, ' · '.join(ITOGI)))
    print('\nФраза после «плюс плюс» — какой язык выбран:')
    for voice, text, want in PHRASES:
        pcm = await speak(voice, text)
        if pcm is None:
            silent += 1
            print('НЕТ ЗВУКА %-22s %s' % (voice, text), flush=True)
            continue
        b = best_phrase(pcm)
        ok = b is not None and b[1] == want
        bad += not ok
        print('%s %-22s %-36s ждали %s получили %s' % ('OK ' if ok else 'ПЛОХО', voice, text, want,
              '%s «%s» %.2f' % (b[1], b[2], b[0]) if b else '—'), flush=True)
    print('\n«Копи копи» в конце фразы — сразу отправить:')
    for voice, text, want in STOP:
        pcm = await speak(voice, text)
        if pcm is None:
            silent += 1
            print('НЕТ ЗВУКА %-22s %s' % (voice, text), flush=True)
            continue
        res = stop_word(pcm)
        got = any(f is not None for _, f, _ in res)
        early = ''
        if want:                     # та же фраза без «копи копи» не должна обрываться — иначе рано
            main_text = text.rsplit('. ', 1)[0]
            pm = await speak(voice, main_text)
            if pm is not None and any(f is not None for _, f, _ in stop_word(pm)):
                early = ' РАНО: сработало и без «копи копи»'
        ok = got == want and not early
        bad += not ok
        print('%s %-22s %-52s ждали %-5s | %s%s' % ('OK ' if ok else 'ПЛОХО', voice, text, want,
              '; '.join('%s:%s «%s»' % (l, '%.1f с' % f if f is not None else '—', t) for l, f, t in res), early),
              flush=True)
    print('\nПорог minConf=%.2f: верные вызовы — от %.2f, ложные кандидаты — до %.2f' % (
        MIN, min(sure_ok, default=1), max(sure_bad, default=0)))
    total = len(CASES) + len(SLITNO) + len(PHRASES) + len(STOP)
    if PLOHO:
        print('\nОшибки вызова — разбор:')
        for x in PLOHO:
            print('  ' + x)
        for x in PLOHO[:10]:            # журналы CI из облачной сессии не читаются — в аннотации
            print('::error title=proverka::' + x[:900].replace('\n', ' '))
    print('\nИтог: ошибок %d, без звука %d, всего %d' % (bad, silent, total))
    if bad or silent:
        print('::error title=proverka::Итог: ошибок %d, без звука %d, всего %d' % (bad, silent, total))
    sys.exit(1 if bad or silent > total // 10 else 0)


if __name__ == '__main__':
    asyncio.run(main())
