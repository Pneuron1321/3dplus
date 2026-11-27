#!/usr/bin/env python3
"""WOFF 1.0 -> TTF: распаковать таблицы (zlib) и собрать sfnt заново. python3 woff2ttf.py in.woff out.ttf"""
import struct
import sys
import zlib


def main(src, dst):
    d = open(src, 'rb').read()
    sig, flavor, length, n = struct.unpack('>4sIIH', d[:14])
    assert sig == b'wOFF', 'не WOFF 1.0'
    tabl = []
    for i in range(n):
        tag, off, clen, olen, csum = struct.unpack('>4sIIII', d[44 + i * 20:64 + i * 20])
        raw = d[off:off + clen]
        tabl.append((tag, zlib.decompress(raw) if clen < olen else raw, csum))
    tabl.sort(key=lambda t: t[0])
    es = max(k for k in range(16) if (1 << k) <= n)
    sr = (1 << es) * 16
    out = bytearray(struct.pack('>IHHHH', flavor, n, sr, es, n * 16 - sr))
    off = 12 + 16 * n
    body = bytearray()
    for tag, data, csum in tabl:
        out += struct.pack('>4sIII', tag, csum, off + len(body), len(data))
        body += data + b'\0' * (-len(data) % 4)
    open(dst, 'wb').write(bytes(out + body))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
