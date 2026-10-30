#!/usr/bin/env python3
"""Tripo API V3 (международный хост): баланс и multiview-to-model по 4 видам.

Ключ подставляет прокси окружения (сеть *.tripo3d.ai), либо берётся из TRIPO_API_KEY.
Ключ не печатается.

  python3 tripo_mv.py balans
  python3 tripo_mv.py mv OUT_DIR front.png left.png back.png right.png [--model v3.1-20260211]
"""
import json, os, sys, time, urllib.request, uuid

BAZA = "https://openapi.tripo3d.ai/v3"
KLUCH = os.environ.get("TRIPO_API_KEY") or "tsk_proxy"  # заглушку заменяет прокси
VIDY = ["front", "left", "back", "right"]


def zapros(metod, put, telo=None, tip="application/json"):
    req = urllib.request.Request(BAZA + put, data=telo, method=metod)
    req.add_header("Authorization", "Bearer " + KLUCH)
    if telo is not None:
        req.add_header("Content-Type", tip)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"{metod} {put}: HTTP {e.code} {e.read().decode(errors='replace')[:400]}")


def zagruzi(put_k_faylu):
    g = "----plus" + uuid.uuid4().hex
    with open(put_k_faylu, "rb") as f:
        dannye = f.read()
    telo = (f"--{g}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{os.path.basename(put_k_faylu)}\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + dannye + f"\r\n--{g}--\r\n".encode()
    otv = zapros("POST", "/files", telo, "multipart/form-data; boundary=" + g)
    return otv["data"]["file_token"]


def skachay(url, kuda):
    with urllib.request.urlopen(url, timeout=300) as r, open(kuda, "wb") as f:
        f.write(r.read())


def mv(out, fayly, model):
    os.makedirs(out, exist_ok=True)
    inputs = []
    for vid, f in zip(VIDY, fayly):
        tok = zagruzi(f)
        print(vid, "загружен")
        inputs.append({vid: {"file_token": tok}})
    telo = {"inputs": inputs, "model": model, "texture": True, "pbr": True,
            "texture_quality": "detailed", "geometry_quality": "detailed"}
    otv = zapros("POST", "/generation/multiview-to-model", json.dumps(telo).encode())
    tid = otv["data"]["task_id"]
    print("задача", tid)
    while True:
        time.sleep(3)
        z = zapros("GET", f"/tasks/{tid}")["data"]
        st = z.get("status")
        print(st, z.get("progress", ""), flush=True)
        if st == "success":
            break
        if st in ("failed", "cancelled", "banned", "expired"):
            sys.exit(json.dumps(z, ensure_ascii=False)[:800])
    json.dump(z, open(os.path.join(out, "zadacha.json"), "w"), ensure_ascii=False, indent=1)
    vyhod = z.get("output", {})
    for pole, imya in (("model_url", "model.glb"), ("pbr_model_url", "model-pbr.glb"),
                       ("rendered_image_url", "render.webp")):
        if vyhod.get(pole):
            skachay(vyhod[pole], os.path.join(out, imya))
            print("скачан", imya)


if __name__ == "__main__":
    a = sys.argv[1:]
    if a and a[0] == "balans":
        print(json.dumps(zapros("GET", "/account/balance"), ensure_ascii=False))
    elif len(a) >= 6 and a[0] == "mv":
        model = a[a.index("--model") + 1] if "--model" in a else "v3.1-20260211"
        mv(a[1], a[2:6], model)
    else:
        sys.exit(__doc__)
