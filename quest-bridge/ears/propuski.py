"""Проба: пропуски «плюс плюс» — когда перед словом есть звук (KOZYRI-OPYTY, прогон 10, А12).

CI меряет почти только ложные вызовы. Здесь — пропуски: перед «плюс плюс» стоит «э»,
«ну», короткий шум или шум игры всё время. Четыре варианта распознавания:
  A — как в приложении сейчас (все приманки);
  B — без коротких приманок («и», «а», «ну», «вот», «это», «си»…);
  C — без одной «си» (то, что Александр не скажет никогда);
  D — все приманки, но «[unk]» в начале итога пропускается (правка логики, не словаря);
  E — все приманки, но перед «плюс плюс» можно «ну», «так», «э» (обращение).
Для каждого — доля вызовов и ложные вызовы на «плюс плюс» посреди фразы.
Логика вызова та же, что в приложении (proverka.slushat). Голос синтезированный — предел.
Запуск из quest-bridge: python ears/propuski.py
"""
import asyncio
import copy
import os
import random
import struct

import proverka as P

GOLOSA = ['ru-RU-DmitryNeural', 'ru-RU-SvetlanaNeural', 'uk-UA-OstapNeural', 'uk-UA-PolinaNeural']
# (текст, приставка перед речью): шум — короткий звук прямо перед словом; фон — шум всё время
ZOV = [('плюс плюс', None), ('плюс плюс', 'шум 0,15 с'), ('плюс плюс', 'шум 0,4 с'), ('плюс плюс', 'фон'),
       ('плюс плюс посмотри на экран', None), ('плюс плюс посмотри на экран', 'шум 0,15 с'),
       ('плюс плюс посмотри на экран', 'шум 0,4 с'), ('плюс плюс посмотри на экран', 'фон'),
       ('Э-э, плюс плюс, посмотри на экран', None),
       ('Ну, плюс плюс, сколько патронов', None),
       ('Так, плюс плюс, что там написано', None)]
SEREDINA = ['когда я говорю плюс плюс, он слушает', 'это даёт плюс плюс к скорости',
            'у него плюс плюс к броне', 'си плюс плюс это язык программирования',
            'а плюс плюс тут не работает']
KOROTKIE = {'ru': {'и', 'а', 'но', 'вот', 'что', 'как', 'так', 'ну', 'бы', 'в', 'это', 'си', 'даёт'},
            'en': {'c', 'see', 'sea', 'it', 'in', 'year', 'gives'}}
SI = {'ru': {'си'}, 'en': {'c', 'see', 'sea'}}
OBRASHCHENIE = {'ну', 'так', 'э'}
ISHODNIK = copy.deepcopy(P.T)
VARIANTY = os.environ.get('PROPUSKI', 'ABCDE')
EXACT, START = P.exact, P.wake_at_start


def shum(n, uroven, seed):
    rnd = random.Random(seed)
    return b''.join(struct.pack('<h', max(-32767, min(32767, int(rnd.gauss(0, uroven))))) for _ in range(n))


def s_pristavkoy(pcm, kak, seed):
    """pcm из speak: 0,5 с тишины + речь + 1,5 с тишины. Шум — вплотную перед речью."""
    if kak is None:
        return pcm
    tishina, rech = pcm[:16000], pcm[16000:]
    if kak == 'фон':                                       # шум игры всё время, ~ −30 дБ
        sh = shum(len(pcm) // 2, 1000, seed)
        a = struct.unpack('<%dh' % (len(pcm) // 2), pcm)
        b = struct.unpack('<%dh' % (len(sh) // 2), sh)
        return struct.pack('<%dh' % len(a), *(max(-32767, min(32767, x + y)) for x, y in zip(a, b)))
    sek = 0.15 if '0,15' in kak else 0.4
    n = int(16000 * sek)
    return tishina[:len(tishina) - n * 2] + shum(n, 3000, seed) + rech


def variant(v):
    P.T.clear()
    P.T.update(copy.deepcopy(ISHODNIK))
    P.exact, P.wake_at_start = EXACT, START
    ubrat = {'B': KOROTKIE, 'C': SI}.get(v)
    if ubrat:
        for lang, m in P.T['models'].items():
            m['decoys'] = [d for d in m.get('decoys', []) if d not in ubrat.get(lang, set())]
    if v in 'DE':
        propusk = {'[unk]'} if v == 'D' else OBRASHCHENIE

        def bez_unk(words):
            i = 0
            while i < len(words) and words[i].get('word') in propusk:
                i += 1
            return words[i:]

        def exact_bez_unk(table, text, confs):
            w = text.split()
            i = 0
            while i < len(w) and w[i] in propusk:
                i += 1
            return EXACT(table, ' '.join(w[i:]), confs[i:] if len(confs) == len(w) else confs)

        P.wake_at_start = lambda lang, words, need: START(lang, bez_unk(words), need)
        P.exact = exact_bez_unk


async def main():
    zvuki = {}
    for g in GOLOSA:
        for t in {t for t, _ in ZOV} | set(SEREDINA):
            zvuki[g, t] = await P.speak(g, t)
    itog = {}
    for v in VARIANTY:
        variant(v)
        print('\n== Вариант %s ==' % v, flush=True)
        ok, vsego, po = 0, 0, {}
        for g in GOLOSA:
            for i, (t, kak) in enumerate(ZOV):
                pcm = zvuki[g, t]
                if pcm is None:
                    continue
                a, rest, how, sec = P.slushat(s_pristavkoy(pcm, kak, i))
                hit = a is not None and a.startswith(('wake', 'zoom', 'compact', 'rezhim'))
                vsego += 1
                ok += hit
                k = kak or ('«%s»' % t.split(',')[0] if t[0].isupper() else 'чисто')
                po.setdefault(k, [0, 0])
                po[k][0] += hit
                po[k][1] += 1
                if not hit:
                    print('  пропуск %-20s %-36s %-11s | %s' % (g.split('-')[2], t[:36], kak or '',
                                                               ' · '.join(P.ITOGI)[:120]), flush=True)
        lozh = 0
        for g in GOLOSA:
            for t in SEREDINA:
                pcm = zvuki[g, t]
                if pcm is None:
                    continue
                a, rest, how, sec = P.slushat(pcm)
                if a is not None:
                    lozh += 1
                    print('  ЛОЖНЫЙ  %-20s %-40s → %s' % (g.split('-')[2], t, a), flush=True)
        itog[v] = (ok, vsego, po, lozh)
    print('\nВызовов из сказанных (больше — лучше) и ложных посреди фразы (меньше — лучше):')
    kl = list(itog['A'][2])
    print('%-3s %8s %8s  %s' % ('', 'вызовы', 'ложные', '  '.join('%-12s' % k for k in kl)))
    for v, (ok, vsego, po, lozh) in itog.items():
        print('%-3s %3d/%-4d %4d/%-3d  %s' % (v, ok, vsego, lozh, len(GOLOSA) * len(SEREDINA),
                                             '  '.join('%-12s' % ('%d/%d' % tuple(po[k])) for k in kl)))
    print('A — как сейчас; B — без коротких приманок; C — без «си»; D — «[unk]» в начале пропускается; '
          'E — перед «плюс плюс» можно «ну», «так», «э»')


asyncio.run(main())
