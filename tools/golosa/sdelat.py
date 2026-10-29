#!/usr/bin/env python3
"""Образцы мужских голосов для Плюса (29.10, Александр: «найди других мужских голосов, самые качественные, шесть,
пришли аудиофайлы»). edge-tts — тот же синтез, что у Плюса (golos): голос меняется одной строкой. Многоязычные
голоса говорят и по-русски, и по-английски одним голосом. Результат — docs/golosa/*.mp3 (+ andrew — нынешний, для
сравнения)."""
import asyncio
import os
import sys

import edge_tts

TEKST = ('Привет, Саша! Я Плюс, твой соведущий. Слева полиция — сворачивай во двор, я прикрою. '
         'Hey chat, welcome to the stream! Who do you think wins this round?')
GOLOSA = [
    ('0-andrew-seychas', 'en-US-AndrewMultilingualNeural'),
    ('1-brian', 'en-US-BrianMultilingualNeural'),
    ('2-william', 'en-AU-WilliamMultilingualNeural'),
    ('3-florian', 'de-DE-FlorianMultilingualNeural'),
    ('4-remy', 'fr-FR-RemyMultilingualNeural'),
    ('5-giuseppe', 'it-IT-GiuseppeMultilingualNeural'),
    ('6-dmitry-tolko-russkiy', 'ru-RU-DmitryNeural'),
]
ZAPAS = ['en-US-ChristopherNeural', 'en-GB-RyanNeural', 'en-US-GuyNeural', 'en-US-EricNeural', 'en-GB-ThomasNeural']


async def main(out):
    os.makedirs(out, exist_ok=True)
    est = {v['ShortName'] for v in await edge_tts.list_voices()}
    zapas = [z for z in ZAPAS if z in est]
    sdelano, oshibki = [], []

    async def sdelat(imya, g):
        tekst = TEKST if 'ru-RU' not in g else TEKST.split(' Hey chat')[0] + ' Всем привет в чате! Кто, по-вашему, победит в этом раунде?'
        for popytka in range(3):
            try:
                await edge_tts.Communicate(tekst, g).save(os.path.join(out, imya + '.mp3'))
                return True
            except Exception as e:
                oshibki.append('%s: %s' % (g, type(e).__name__))
                await asyncio.sleep(3)
        return False

    for imya, g in GOLOSA:
        kandidaty = ([g] if g in est else []) + zapas
        for gg in kandidaty:
            im = imya if gg == g else imya.split('-')[0] + '-' + gg.split('-')[2].replace('MultilingualNeural', '').replace('Neural', '').lower()
            if await sdelat(im, gg):
                sdelano.append('%s.mp3 — %s' % (im, gg))
                if gg in zapas:
                    zapas.remove(gg)
                break
    if oshibki:
        print('Сбои:', '; '.join(oshibki))
    open(os.path.join(out, 'SPISOK.txt'), 'w').write('\n'.join(sdelano) + '\n')
    print('\n'.join(sdelano))


asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else 'docs/golosa'))
