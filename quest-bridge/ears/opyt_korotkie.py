"""Опыт (для сведения): короткая команда сразу за «плюс плюс», одним дыханием.

В шлеме 06.10 «плюс плюс окно копи копи» слитно дало Whisper «Так, ну, копи-копи»
(речь 0,7 с): начало фразы в кольце оказалось после «окно». С паузой после
«плюс плюс» или с двойным «плюс плюс» — верно. Здесь: где приложение начинает
фразу (конец слов вызова по Vosk) и что слышно, если начать раньше на 0,15–0,5 с.
Запуск на GitHub из папки quest-bridge: python ears/opyt_korotkie.py
"""
import asyncio
import importlib.machinery
import importlib.util
import json
import os

from vosk import KaldiRecognizer

import proverka as P

FRAZY = ['плюс плюс окно копи копи', 'плюс плюс рация копи копи', 'плюс плюс обнови копи копи',
         'плюс плюс повтори копи копи', 'плюс плюс посмотри копи копи']
GOLOSA = ['ru-RU-DmitryNeural', 'ru-RU-SvetlanaNeural']
OTSTUP = (0.0, 0.15, 0.3, 0.5)
# срез слов вызова — тот же, что в ушах Termux (shlem/ushi)
_ld = importlib.machinery.SourceFileLoader(
    'ushi', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'shlem', 'ushi'))
USHI = importlib.util.module_from_spec(importlib.util.spec_from_loader('ushi', _ld))
_ld.exec_module(USHI)


def uslyshat(pcm):
    r = KaldiRecognizer(P.MODELS['ru'], 16000)
    r.SetWords(True)
    for i in range(0, len(pcm), 3200):
        r.AcceptWaveform(pcm[i:i + 3200])
    return json.loads(r.FinalResult())


async def main():
    for golos in GOLOSA:
        for t in FRAZY:
            pcm = await P.speak(golos, t)
            if pcm is None:
                print('НЕТ ЗВУКА', golos, t)
                continue
            P.KRAY[:] = []
            got, rest, how, sec = P.slushat(pcm)
            vse = uslyshat(pcm)
            slova = ' '.join('%s[%.2f–%.2f]' % (w['word'], w['start'] - 0.5, w['end'] - 0.5)
                             for w in vse.get('result', []))
            if not P.KRAY:
                print('::notice title=korotkie::%s «%s» вызова нет (%s) | слова: %s' % (golos[6:-6], t, got, slova),
                      flush=True)
                continue
            frm, now, lead = P.KRAY
            stroki = []
            for o in OTSTUP:
                s = max(0, frm - int(o * 16000) * 2)
                stroki.append('−%.2f: «%s»' % (o, uslyshat(pcm[s:]).get('text', '')))
            s_vyzovom = uslyshat(pcm[lead:]).get('text', '')
            stroki.append('с вызова (%.2f с): «%s» → «%s»' % (
                (frm - lead) / 32000, s_vyzovom, USHI.bez_vyzova(s_vyzovom, (frm - lead) / 32000)))
            stroka = '%s «%s» %s, начало фразы %.2f с | %s | слова: %s' % (
                golos[6:-6], t, how, frm / 32000 - 0.5, ' · '.join(stroki), slova)
            print(stroka, flush=True)
            print('::notice title=korotkie::' + stroka, flush=True)   # журналы CI отсюда не читаются


if __name__ == '__main__':
    asyncio.run(main())
