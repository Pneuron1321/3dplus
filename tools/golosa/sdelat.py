#!/usr/bin/env python3
"""Образцы мужских голосов для Плюса. 29.10 — шесть голосов как есть; 30.10 (Александр: «какие-то феминизированные») —
те же голоса НИЖЕ (edge-tts pitch, как `golos nizhe N` у Плюса): docs/golosa/nizhe/*.mp3. Многоязычные говорят и
по-русски, и по-английски; Дмитрий — только по-русски."""
import asyncio
import os
import sys

import edge_tts

TEKST = ('Привет, Саша! Я Плюс, твой соведущий. Слева полиция — сворачивай во двор, я прикрою. '
         'Hey chat, welcome to the stream! Who do you think wins this round?')
RU = TEKST.split(' Hey chat')[0] + ' Всем привет в чате! Кто, по-вашему, победит в этом раунде?'
GOLOSA = [   # (имя файла, голос, на сколько ниже, Гц)
    ('1-andrew-nizhe-20', 'en-US-AndrewMultilingualNeural', 20),
    ('2-andrew-nizhe-35', 'en-US-AndrewMultilingualNeural', 35),
    ('3-brian-nizhe-30', 'en-US-BrianMultilingualNeural', 30),
    ('4-william-nizhe-30', 'en-AU-WilliamMultilingualNeural', 30),
    ('5-florian-nizhe-25', 'de-DE-FlorianMultilingualNeural', 25),
    ('6-dmitry-nizhe-20-tolko-russkiy', 'ru-RU-DmitryNeural', 20),
]


async def main(out):
    os.makedirs(out, exist_ok=True)
    sdelano = []
    for imya, g, hz in GOLOSA:
        for popytka in range(3):
            try:
                await edge_tts.Communicate(RU if g.startswith('ru-') else TEKST, g, pitch='-%dHz' % hz).save(
                    os.path.join(out, imya + '.mp3'))
                sdelano.append('%s.mp3 — %s, ниже на %d Гц' % (imya, g, hz))
                break
            except Exception as e:
                print(g, type(e).__name__)
                await asyncio.sleep(3)
    open(os.path.join(out, 'SPISOK.txt'), 'w').write('\n'.join(sdelano) + '\n')
    print('\n'.join(sdelano))


asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else 'docs/golosa/nizhe'))
